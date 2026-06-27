"""
Lambda: pawgate-event-ingest
=============================

Recibe eventos del IoT Rule `pawgate_events_rule` y los persiste en la tabla
DynamoDB `pawgate_events`.

Trigger:
    IoT Rule SQL: SELECT *, topic() as topic, timestamp() as server_ts
                  FROM 'pawgate/+/events/+'

Payload de entrada (lo arma el SELECT de la rule):
    {
      "topic":      "pawgate/pawgate-001/events/door",
      "server_ts":  1780347796471,    // timestamp del broker
      "type":       "opened",          // campo del payload original publicado por el device
      "ts":         1780347796470      // timestamp del device cuando publico
      // ...resto del payload del device, lo que haya
    }

Se transforma a un item DDB con clave compuesta (device_id, ts_event) donde
ts_event es "<ts_padded>#<kind>#<type>" para que un range query por sort key
devuelva los eventos ordenados cronologicamente naturalmente.

TTL: 90 dias desde la insercion. DDB elimina los items vencidos sin costo.

Permisos requeridos (IAM role):
    - dynamodb:PutItem sobre pawgate_events
    - logs:CreateLogStream + PutLogEvents (built-in via AWSLambdaBasicExecutionRole)
"""

import json
import logging
import os
from datetime import datetime, timezone, timedelta

import boto3

# Logger CloudWatch (level INFO ya queda)
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Cliente DDB. Lo creamos a nivel modulo para que se reuse entre invocaciones
# warm (Lambda execution context reuse). Boto3 client crea HTTP keep-alive
# automaticamente, asi que reusar el cliente baja latencia ~30ms.
ddb = boto3.resource("dynamodb")
events_table = ddb.Table(os.environ.get("EVENTS_TABLE", "pawgate_events"))
device_state_table = ddb.Table(os.environ.get("DEVICE_STATE_TABLE", "pawgate_device_state"))
fcm_endpoints_table = ddb.Table(os.environ.get("FCM_ENDPOINTS_TABLE", "pawgate_fcm_endpoints"))
notifications_table = ddb.Table(os.environ.get("NOTIFICATIONS_TABLE", "pawgate_notifications"))

# Cliente SNS para mandar push via Platform Application. Si la variable
# FCM_PLATFORM_APP_ARN no esta seteada, las notificaciones se skipean
# silenciosamente (eventIngest sigue funcionando como antes).
sns = boto3.client("sns")
FCM_PLATFORM_APP_ARN = os.environ.get("FCM_PLATFORM_APP_ARN", "").strip()

TTL_DAYS = int(os.environ.get("TTL_DAYS", "90"))

# Que tipos de eventos del firmware GENERAN notificacion (push + persist).
# El resto (sensor crudo, telemetry, closed) va solo al historial.
#
# closed NO esta aca por diseño: cerrar la puerta despues de abrirla es
# un evento ruidoso (sucede ~5s despues de cada opened) y no aporta info
# nueva al user. Se ve solo en el historial.
NOTIFIABLE_EVENT_TYPES = {
    "opened",
    "blocked",
    "unblocked",
    "light_on",
    "light_off",
}

# Cuando el user dispara un cmd desde la app, el apiHandler ya persiste una
# notif tipo cmd_X con el actor. Luego el ESP32 ejecuta y publica el evento
# de confirmacion (opened/blocked/unblocked). Para no duplicar, eventIngest
# verifica si el mismo user tiene una notif cmd_X reciente (correspondiente
# al evento) y skipea la persistencia + push.
EVENT_TO_CMD_TYPE = {
    "opened":    "cmd_open",
    "blocked":   "cmd_block",
    "unblocked": "cmd_unblock",
}
# Ventana de tiempo para considerar el cmd como "padre" del evento. El ESP32
# tipicamente responde en <5s, asi que 10s tiene margen.
DEDUP_WINDOW_SECONDS = 10


def lambda_handler(event, context):
    """Entry point para AWS Lambda. event es el payload del IoT Rule SELECT."""
    logger.info("Received event: %s", json.dumps(event))

    # 1) Parsear el topic para extraer device_id y event_kind.
    #    Topic format: pawgate/<device_id>/events/<kind>
    topic = event.get("topic")
    if not topic:
        logger.error("Event sin campo 'topic' (no se puede inferir device_id). Skip.")
        return {"statusCode": 400, "error": "missing topic"}

    parts = topic.split("/")
    if len(parts) != 4 or parts[0] != "pawgate" or parts[2] != "events":
        logger.error("Topic con formato inesperado: %s", topic)
        return {"statusCode": 400, "error": "unexpected topic format"}

    device_id = parts[1]   # "pawgate-001"
    event_kind = parts[3]  # "door" | "sensor" | "telemetry" | etc

    # === Branch para telemetry ===
    # No queremos inundar el historial con un evento de telemetry cada 30s.
    # En cambio guardamos solo el ULTIMO snapshot en pawgate_device_state como
    # un map 'info'. El endpoint GET /devices/{id}/info la devuelve a la app.
    if event_kind == "telemetry":
        _update_device_info(device_id, event)
        return {"statusCode": 200, "device_id": device_id, "kind": "telemetry"}

    # 2) Construir sort key. Preferimos server_ts (epoch_ms inyectado por la
    #    IoT Rule via timestamp()) porque es UNIVERSAL: no depende de que el
    #    device tenga reloj sincronizado.
    #
    #    Algunos devices mandan `ts` con un valor que NO es epoch — el ESP32
    #    manda millis() desde boot (numero chico, p.ej. 8236), el simulador
    #    Python manda epoch_ms (numero grande). Si usamos ese `ts` como sort
    #    key, los eventos del ESP32 quedan ordenados al inicio (millis < 1e10)
    #    y los del simulador al final, mezclados sin sentido cronologico.
    #
    #    Heuristica: si `ts` se ve como epoch_ms valido (> 1.5e12 = 2017+),
    #    lo usamos. Sino, server_ts. Fallback final: time.time() * 1000.
    payload_ts = int(event.get("ts", 0))
    server_ts  = int(event.get("server_ts", 0))
    if payload_ts > 1_500_000_000_000:
        ts_device = payload_ts
    elif server_ts > 0:
        ts_device = server_ts
    else:
        ts_device = int(datetime.now(timezone.utc).timestamp() * 1000)
    # event_type: tomar "type" del payload del device; si no vino, usar el kind
    # del topic como fallback (mas legible que "unknown" cuando luego se filtra).
    event_type = event.get("type") or event_kind
    sort_key = f"{ts_device:013d}#{event_kind}#{event_type}"

    # direction: solo aplica para events tipo door (opened/closed).
    # Valores: "in"  (alguien entro a la casa, ej RFID disparo afuera)
    #         "out" (alguien salio al patio, ej ultrasonido disparo adentro)
    direction = event.get("direction")

    # 3) TTL: epoch SECONDS (no ms) cuando DDB debe purgar el item.
    ttl_epoch = int((datetime.now(timezone.utc) + timedelta(days=TTL_DAYS)).timestamp())

    # 4) Construir item. Guardamos el payload completo como JSON string para
    #    no perder informacion (ej. sensor.distance_cm, door.reason, etc).
    item = {
        "device_id":   device_id,
        "ts_event":    sort_key,
        "type":        event_kind,
        "event_type":  event_type,
        "payload":     json.dumps(event),
        "ttl_epoch":   ttl_epoch,
        "created_at":  datetime.now(timezone.utc).isoformat(),
    }
    if direction:
        item["direction"] = direction

    try:
        events_table.put_item(Item=item)
        logger.info("Stored: device_id=%s ts_event=%s", device_id, sort_key)
    except Exception as e:
        # Re-raise para que IoT Rule haga retry (3 intentos automaticos).
        logger.error("Failed to put_item: %s", e)
        raise

    # Push notification (best-effort). Si falla, NO retiramos el item ya
    # guardado: el historial sigue siendo correcto aunque la notif no llegue.
    try:
        _notify_owners(device_id, event_type, direction, event)
    except Exception as e:
        logger.warning("Push notification failed (ignored): %s", e)

    return {"statusCode": 200, "device_id": device_id, "ts_event": sort_key}


def _notify_owners(device_id, event_type, direction, payload):
    """
    Para cada event_type 'notifiable':
      1) PERSISTE 1 fila por user en pawgate_notifications (para la bandeja
         de la app). ESTO SIEMPRE SUCEDE, independiente del FCM ARN. Asi
         aunque las push notifications esten deshabilitadas (o todavia no
         configuradas), las notifs aparecen en la pantalla al abrir la app.
      2) MANDA PUSH via SNS Platform App solo si FCM_PLATFORM_APP_ARN
         esta configurado. Best-effort: si falla, ya tenemos persistencia.

    Es 'broadcast' a todos los users registrados en pawgate_fcm_endpoints
    (el proyecto tiene 1 device unico). Si en el futuro hay multi-device,
    agregar una tabla pawgate_device_owners y filtrar.
    """
    if event_type not in NOTIFIABLE_EVENT_TYPES:
        return

    # Construir el cuerpo de la notif. Direction puede agregar contexto:
    # "Puerta abierta hacia adentro" vs solo "Puerta abierta".
    title, body = _format_notification(event_type, direction)

    # Scan small de la tabla (esperado: 1-5 endpoints maximo en este proyecto).
    # Si esto crece, mover a Query por device_owners.
    try:
        resp = fcm_endpoints_table.scan()
    except Exception as e:
        logger.error("Failed to scan fcm_endpoints: %s", e)
        return

    endpoints = resp.get("Items", [])
    if not endpoints:
        logger.info("No FCM endpoints registered; skipping persist + push")
        # Sin endpoints registrados no sabemos a que user_email pertenece la
        # notif, asi que no podemos persistir nada. Es esperable solo en el
        # bootstrap inicial cuando ningun device todavia se registro.
        return

    # ====== Persistencia 1-fila-por-user en pawgate_notifications ======
    # ANTES del dedupe de SNS: queremos guardar 1 notif para CADA user_email
    # registrado (asi cada user ve la notif al abrir la pantalla). El dedupe
    # de SNS es solo para evitar push duplicado al mismo device, no para
    # evitar persistir N notifs (eso sería bug).
    #
    # IMPORTANTE: esta persistencia SIEMPRE sucede aunque no haya FCM ARN
    # configurado. Asi un evento del firmware (perro abre puerta) aparece en
    # la bandeja de la app aunque el push no llegue.
    #
    # DEDUP cmd ↔ evento: si el user disparo un cmd_X reciente, este evento
    # del firmware es su confirmacion. apiHandler ya persistio la notif con
    # actor; no queremos persistir otra copia 'Por el dispositivo'. Si TODOS
    # los users tienen cmd_X reciente, terminamos la funcion aca (ni
    # persistencia ni push).
    #
    # Trackeamos {user_email -> notif_id} para despues incluir el notif_id
    # CORRECTO en el push de cada endpoint. Asi cuando el user tap el push,
    # la app sabe que notif marcar como leida.
    user_to_notif_id = {}
    skipped_users = set()
    for ep in endpoints:
        user_email = ep.get("user_email")
        if not user_email:
            continue
        if _has_recent_user_cmd_for(user_email, event_type):
            logger.info("dedup: skip event=%s user=%s (recent cmd in <%ds)",
                        event_type, user_email, DEDUP_WINDOW_SECONDS)
            skipped_users.add(user_email)
            continue
        nid = _persist_notification(user_email, device_id, event_type,
                                     direction, title, body, payload)
        if nid:
            user_to_notif_id[user_email] = nid

    # ====== A partir de aca: PUSH via SNS. Solo si hay ARN configurado. ======
    if not FCM_PLATFORM_APP_ARN:
        logger.info("FCM_PLATFORM_APP_ARN not set, persisted but no push")
        return

    # Deduplicar por endpoint_arn: si 2 user_emails distintos apuntan al
    # mismo device (ej: 2 cuentas logueadas en el mismo celular -> SNS
    # devuelve el mismo endpoint_arn para ambos), solo notificar UNA vez al
    # device. Sino llegan N push idénticas a la barra de notificaciones.
    seen_arns = set()
    unique_endpoints = []
    for ep in endpoints:
        arn = ep.get("endpoint_arn")
        if arn and arn not in seen_arns:
            seen_arns.add(arn)
            unique_endpoints.append(ep)
    endpoints = unique_endpoints

    # SNS espera el message ya envuelto en formato GCM/FCM. El campo "data"
    # es el que llega como msg.getData() en onMessageReceived del Android,
    # y "notification" es lo que FCM muestra automaticamente si la app esta
    # en background. Para tener UI consistente, usamos data-only y construimos
    # la notif en el cliente.
    #
    # IMPORTANTE: el payload se construye PER endpoint, porque el notif_id
    # cambia segun el user que vaya a recibir el push.
    sent = 0
    for ep in endpoints:
        arn = ep.get("endpoint_arn")
        if not arn:
            continue
        user_email = ep.get("user_email")
        # Si dedup skipeo a este user (porque tiene un cmd reciente),
        # tampoco le mandamos push — el cmd ya lo informo en pantalla.
        if user_email in skipped_users:
            continue
        notif_id = user_to_notif_id.get(user_email, "") if user_email else ""

        gcm_payload = json.dumps({
            "data": {
                "device_id":  device_id,
                "event_type": event_type or "",
                "direction":  direction or "",
                "title":      title,
                "body":       body,
                "ts":         str(payload.get("ts", "")),
                # notif_id permite que la app, al hacer tap en el push,
                # marque ESA notif especifica como leida (POST /{id}/read).
                "notif_id":   notif_id,
            }
        })
        message = json.dumps({"GCM": gcm_payload})

        try:
            sns.publish(TargetArn=arn, MessageStructure="json", Message=message)
            sent += 1
        except sns.exceptions.EndpointDisabledException:
            # Token vencio (app desinstalada o user revoco notificaciones).
            # Borramos el endpoint en SNS y la fila en DDB.
            logger.info("Endpoint disabled, cleaning up: %s", arn)
            try:
                sns.delete_endpoint(EndpointArn=arn)
            except Exception:
                pass
            try:
                fcm_endpoints_table.delete_item(
                    Key={"user_email": ep["user_email"]})
            except Exception:
                pass
        except Exception as e:
            logger.warning("sns.publish failed for endpoint %s: %s", arn, e)
    logger.info("Push sent to %d/%d endpoints (event=%s)",
                sent, len(endpoints), event_type)


def _format_notification(event_type, direction):
    """Devuelve (title, body) en castellano para mostrar en el celular.

    Eventos cubiertos (la lista cerrada del proyecto):
      - opened (in)   -> "Puerta abierta hacia adentro · por la mascota"
      - opened (out)  -> "Puerta abierta hacia afuera · por la mascota"
      - blocked       -> "Puerta bloqueada"
      - unblocked     -> "Puerta desbloqueada"
      - light_on      -> "Luz prendida"
      - light_off     -> "Luz apagada"

    closed, sensor_*, telemetry, etc. NO se notifican (van solo al historial).

    Cuando un user dispara cmd_open desde la app, apiHandler persiste su
    PROPIA notif con actor="<user>" antes de que el ESP32 emita 'opened'.
    Aca al ver el 'opened' lo persistimos como 'por la mascota'. En la
    bandeja vas a ver las dos lineas (la del cmd con actor + la del
    firmware). Es OK para parcial y trazable.
    """
    if event_type == "opened":
        if direction == "in":
            return "Puerta abierta hacia adentro", "Por la mascota"
        if direction == "out":
            return "Puerta abierta hacia afuera", "Por la mascota"
        return "Puerta abierta", "Por la mascota"
    if event_type == "blocked":
        return "Puerta bloqueada", "Por el dispositivo"
    if event_type == "unblocked":
        return "Puerta desbloqueada", "Por el dispositivo"
    if event_type == "light_on":
        return "Luz prendida", "Por el dispositivo"
    if event_type == "light_off":
        return "Luz apagada", "Por el dispositivo"
    return "Evento de la puerta", event_type or "evento"


def _has_recent_user_cmd_for(user_email, event_type):
    """
    True si el user_email tiene una notif tipo cmd_X reciente (< DEDUP_WINDOW)
    correspondiente al event_type del firmware. Usado para evitar duplicar
    'Puerta bloqueada · Por el dispositivo' + 'Puerta bloqueada · Por NAME'
    cuando el user ejecuto el cmd desde la app.

    Mapeo:
      opened    -> cmd_open
      blocked   -> cmd_block
      unblocked -> cmd_unblock

    Si event_type no esta en el mapeo (light_on/off por ej.), devuelve False
    porque esos eventos del firmware NO tienen contraparte de cmd.
    """
    cmd_type = EVENT_TO_CMD_TYPE.get(event_type)
    if not cmd_type:
        return False
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=DEDUP_WINDOW_SECONDS)
    cutoff_iso = cutoff.isoformat()
    try:
        # Como el SK esta invertido por ts (mas reciente = SK menor), default
        # Query devuelve los items mas recientes primero. Con Limit=20 cubrimos
        # el caso de varios eventos entre el cmd y la confirmacion.
        resp = notifications_table.query(
            KeyConditionExpression="user_email = :u",
            FilterExpression="#t = :type AND created_at > :since",
            ExpressionAttributeNames={"#t": "type"},
            ExpressionAttributeValues={
                ":u":     user_email,
                ":type":  cmd_type,
                ":since": cutoff_iso,
            },
            Limit=20,
        )
        return resp.get("Count", 0) > 0
    except Exception as e:
        # En caso de error, asumimos NO hay cmd reciente (mejor duplicar que
        # perder una notif legitima del firmware).
        logger.warning("dedup query failed (assume no recent cmd): %s", e)
        return False


def _persist_notification(user_email, device_id, event_type, direction,
                           title, body, payload):
    """
    Guarda 1 notif en pawgate_notifications para que el user la vea en su
    bandeja de la app aunque no estuviera online cuando llego el push.

    Schema:
      user_email   (S, PK)
      notif_id     (S, SK) = "{ts_inverted}#{uuid}" donde ts_inverted es
                   (9999999999999 - timestamp_ms) padded para que el ORDER
                   BY natural del SK (asc) coincida con orden chronologico
                   DESC del evento (mas reciente primero). Util para ListQuery
                   con Limit sin tener que ScanIndexForward=False.
      type         (S) — event_type del payload original
      direction    (S, opcional)
      title        (S)
      body         (S)
      device_id    (S)
      read         (BOOL) — false hasta que el user marca como leida
      created_at   (S) — ISO 8601
      ttl_epoch    (N) — borra automatico despues de 30 dias
    """
    import uuid
    now = datetime.now(timezone.utc)
    ts_ms = int(now.timestamp() * 1000)
    # Sort key invertido para que descending order = ASC sort.
    # Separador '_' (NO '#') porque '#' en el path de un URL es interpretado
    # como fragment-identifier. Aunque Retrofit lo URL-encodea a %23, API
    # Gateway tiene problemas conocidos decodificando %23 en path params:
    # el endpoint recibe el id truncado y devuelve 404 silencioso. Underscore
    # es 100% safe en URL paths.
    ts_inverted = 9_999_999_999_999 - ts_ms
    notif_id = f"{ts_inverted:013d}_{uuid.uuid4().hex[:8]}"

    item = {
        "user_email":  user_email,
        "notif_id":    notif_id,
        "type":        event_type or "",
        "title":       title,
        "body":        body,
        "device_id":   device_id,
        "read":        False,
        "created_at":  now.isoformat(),
        "ttl_epoch":   int((now + timedelta(days=30)).timestamp()),
    }
    if direction:
        item["direction"] = direction

    try:
        notifications_table.put_item(Item=item)
        logger.info("Persisted notif user=%s id=%s type=%s",
                    user_email, notif_id, event_type)
        return notif_id
    except Exception as e:
        logger.warning("Failed to persist notification (ignored): %s", e)
        return None


def _update_device_info(device_id: str, payload: dict):
    """
    Persiste el ultimo snapshot de telemetry en pawgate_device_state como
    atributo 'info'. UpdateExpression para no pisar lock_state ni horario_marker
    que setea apiHandler/scheduleExecutor.

    Estructura final del item:
      {
        device_id:       "pawgate-001",       # PK
        lock_state:      "AUTO_UNBLOCKED",    # lo manejan otros lambdas
        updated_at:      "2026-06-08T...",
        horario_marker:  "h1,h2",
        info: {                               # <-- esto
            uptime_s:         12345,
            rssi_dbm:         -45,
            free_heap_kb:     160,
            total_heap_kb:    320,
            flash_used_kb:    1200,
            flash_total_kb:   4096,
            cpu_temp_c:       42.5,
            local_ip:         "192.168.1.42",
            firmware_version: "sim-1.0.3",
            hardware_model:   "ESP32-SIM (Python)",
            updated_at:       "2026-06-08T..."   # cuando llego este snapshot
        }
      }
    """
    info = {
        "uptime_s":         int(payload.get("uptime_s", 0)),
        "rssi_dbm":         int(payload.get("rssi_dbm", 0)),
        "free_heap_kb":     int(payload.get("free_heap_kb", 0)),
        "total_heap_kb":    int(payload.get("total_heap_kb", 0)),
        "flash_used_kb":    int(payload.get("flash_used_kb", 0)),
        "flash_total_kb":   int(payload.get("flash_total_kb", 0)),
        "cpu_temp_c":       str(payload.get("cpu_temp_c", "")),
        "local_ip":         str(payload.get("local_ip", "")),
        "firmware_version": str(payload.get("firmware_version", "")),
        "hardware_model":   str(payload.get("hardware_model", "")),
        # WiFi info (W14)
        "wifi_ssid":        str(payload.get("wifi_ssid", "")),
        "wifi_bssid":       str(payload.get("wifi_bssid", "")),
        "wifi_band":        str(payload.get("wifi_band", "")),
        "wifi_gateway":     str(payload.get("wifi_gateway", "")),
        "wifi_security":    str(payload.get("wifi_security", "")),
        "updated_at":       datetime.now(timezone.utc).isoformat(),
    }
    try:
        device_state_table.update_item(
            Key={"device_id": device_id},
            UpdateExpression="SET info = :i, info_updated_at = :u",
            ExpressionAttributeValues={
                ":i": info,
                ":u": info["updated_at"],
            },
        )
        logger.info("Updated device_info device=%s uptime_s=%d", device_id, info["uptime_s"])
    except Exception as e:
        logger.error("Failed to update_item device_info: %s", e)
        raise
