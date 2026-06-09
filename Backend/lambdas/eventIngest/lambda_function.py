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

# Cliente SNS para mandar push via Platform Application. Si la variable
# FCM_PLATFORM_APP_ARN no esta seteada, las notificaciones se skipean
# silenciosamente (eventIngest sigue funcionando como antes).
sns = boto3.client("sns")
FCM_PLATFORM_APP_ARN = os.environ.get("FCM_PLATFORM_APP_ARN", "").strip()

TTL_DAYS = int(os.environ.get("TTL_DAYS", "90"))

# Que tipos de eventos generan notificacion push. El resto (sensor,
# telemetry, los propios cancel/unblock del user) NO suenan el celular.
NOTIFIABLE_EVENT_TYPES = {"opened", "closed", "blocked"}


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

    # 2) Construir sort key. Timestamp del DEVICE (no del server) para que el
    #    orden refleje cuando paso el evento fisicamente, no cuando AWS lo recibio.
    #    Padded a 13 digitos para que el ordenamiento lexicografico = cronologico.
    ts_device = int(event.get("ts", event.get("server_ts", 0)))
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
    Manda push notification via SNS Platform Application a todos los users
    registrados en pawgate_fcm_endpoints. Es 'broadcast' porque para este
    proyecto familia/equipo todos los users registrados son owners del unico
    device pawgate-001. Si en el futuro hay multi-device, agregar una tabla
    pawgate_device_owners y filtrar.

    Si la Platform Application ARN no esta configurada, skipea sin error.
    """
    if not FCM_PLATFORM_APP_ARN:
        return
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
        logger.info("No FCM endpoints registered, skipping push")
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
    gcm_payload = json.dumps({
        "data": {
            "device_id":  device_id,
            "event_type": event_type or "",
            "direction":  direction or "",
            "title":      title,
            "body":       body,
            "ts":         str(payload.get("ts", "")),
        }
    })
    message = json.dumps({"GCM": gcm_payload})

    sent = 0
    for ep in endpoints:
        arn = ep.get("endpoint_arn")
        if not arn:
            continue
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
    """Devuelve (title, body) en castellano para mostrar en el celular."""
    if event_type == "opened":
        if direction == "in":
            return "PawGate", "🐾 Puerta abierta hacia adentro"
        if direction == "out":
            return "PawGate", "🐾 Puerta abierta hacia afuera"
        return "PawGate", "🐾 Puerta abierta"
    if event_type == "closed":
        return "PawGate", "Puerta cerrada"
    if event_type == "blocked":
        return "PawGate", "🔒 Puerta bloqueada"
    return "PawGate", "Evento de la puerta"


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
