"""
Lambda: pawgate-api-handler
============================

Backend REST API de PawGate. Sirve los endpoints que consume la app Android.

Trigger:
    API Gateway (REST API, proxy integration)

Endpoints:

  PUBLICOS (no requieren JWT):
    POST /auth/signup
    POST /auth/confirm
    POST /auth/login

  AUTENTICADOS (header Authorization: Bearer <idToken>):

    GET  /devices/{id}/history?from=<ts>&to=<ts>
    POST /devices/{id}/cmd/{cmd}                  cmd ∈ {open,block,unblock,call,cancel}

    GET    /devices/{id}/schedules
    POST   /devices/{id}/schedules                body: {nombre, hora_inicio, hora_fin, dias, activo}
    PUT    /devices/{id}/schedules/{schedule_id}  body: {nombre, hora_inicio, hora_fin, dias, activo}
    DELETE /devices/{id}/schedules/{schedule_id}

    GET    /devices/{id}/metrics/today            -> {openings_today, last_door_event_at, last_door_event_type}
    GET    /devices/{id}/state                    -> {lock_state, updated_at, currently_in_horario}
    POST   /devices/{id}/state/override-unblock   -> setea lock_state=MANUAL_UNBLOCKED + publish unblock
    POST   /devices/{id}/state/override-block     -> setea lock_state=MANUAL_BLOCKED + publish block

Modelo de schedules (horarios = ventanas en que la puerta queda DESBLOQUEADA):
    nombre        string
    hora_inicio   "HH:MM" (minutos deben ser 00 o 30)
    hora_fin      "HH:MM" (minutos deben ser 00 o 30)
    dias          lista de chars en {"L","M","X","J","V","S","D"}
    activo        bool
"""

import decimal
import json
import logging
import os
import uuid
from datetime import datetime, timedelta, timezone

import boto3
from botocore.exceptions import ClientError


class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            return int(obj) if obj % 1 == 0 else float(obj)
        return super().default(obj)


def json_dumps(obj):
    return json.dumps(obj, cls=DecimalEncoder)


logger = logging.getLogger()
logger.setLevel(logging.INFO)

cognito = boto3.client("cognito-idp")
ddb = boto3.resource("dynamodb")
iot_data = boto3.client("iot-data")
sns = boto3.client("sns")

USER_POOL_ID = os.environ["USER_POOL_ID"]
APP_CLIENT_ID = os.environ["APP_CLIENT_ID"]
EVENTS_TABLE = os.environ.get("EVENTS_TABLE", "pawgate_events")
SCHEDULES_TABLE = os.environ.get("SCHEDULES_TABLE", "pawgate_schedules")
DEVICE_STATE_TABLE = os.environ.get("DEVICE_STATE_TABLE", "pawgate_device_state")
FCM_ENDPOINTS_TABLE = os.environ.get("FCM_ENDPOINTS_TABLE", "pawgate_fcm_endpoints")
FCM_PLATFORM_APP_ARN = os.environ.get("FCM_PLATFORM_APP_ARN", "").strip()
NOTIFICATIONS_TABLE = os.environ.get("NOTIFICATIONS_TABLE", "pawgate_notifications")

events_table = ddb.Table(EVENTS_TABLE)
schedules_table = ddb.Table(SCHEDULES_TABLE)
device_state_table = ddb.Table(DEVICE_STATE_TABLE)
fcm_endpoints_table = ddb.Table(FCM_ENDPOINTS_TABLE)
notifications_table = ddb.Table(NOTIFICATIONS_TABLE)

VALID_DAYS = {"L", "M", "X", "J", "V", "S", "D"}
VALID_LOCK_STATES = {"AUTO_BLOCKED", "AUTO_UNBLOCKED", "MANUAL_UNBLOCKED", "MANUAL_BLOCKED"}


# ============================================================
# RESPONSE HELPERS
# ============================================================

def _response(status_code, body, headers=None):
    default_headers = {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Content-Type,Authorization",
        "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE,OPTIONS",
    }
    if headers:
        default_headers.update(headers)
    return {
        "statusCode": status_code,
        "headers": default_headers,
        "body": json_dumps(body),
    }


def _ok(body):              return _response(200, body)
def _created(body):         return _response(201, body)
def _no_content():          return _response(204, {})
def _bad_request(message):  return _response(400, {"error": message})
def _unauthorized(msg):     return _response(401, {"error": msg})
def _not_found(msg):        return _response(404, {"error": msg})
def _server_error(msg):     return _response(500, {"error": msg})


# ============================================================
# MAIN HANDLER (router)
# ============================================================

def lambda_handler(event, context):
    logger.info("Event: %s", json.dumps(event))
    method = event.get("httpMethod", "")
    path = event.get("path", "")

    if method == "OPTIONS":
        return _response(204, {})

    try:
        body_raw = event.get("body") or "{}"
        try:
            body = json.loads(body_raw) if body_raw else {}
        except json.JSONDecodeError:
            return _bad_request("invalid JSON body")

        # ===== Auth =====
        if method == "POST" and path == "/auth/signup":   return handle_signup(body)
        if method == "POST" and path == "/auth/confirm":  return handle_confirm(body)
        if method == "POST" and path == "/auth/login":    return handle_login(body)
        if method == "POST" and path == "/auth/refresh":  return handle_refresh(body)

        path_params = event.get("pathParameters") or {}
        query_params = event.get("queryStringParameters") or {}
        device_id = path_params.get("id")
        schedule_id = path_params.get("schedule_id")
        cmd = path_params.get("cmd")

        # ===== Device =====
        if method == "GET" and path.endswith("/history"):
            return handle_history(device_id, query_params)

        if method == "POST" and "/cmd/" in path:
            actor = _extract_user_email(event)
            return handle_cmd(device_id, cmd, body, actor=actor)

        # ===== Schedules CRUD =====
        if method == "GET" and path.endswith("/schedules"):
            return handle_list_schedules(device_id)
        if method == "POST" and path.endswith("/schedules"):
            actor = _extract_user_email(event)
            return handle_create_schedule(device_id, body, actor=actor)
        if method == "PUT" and "/schedules/" in path:
            actor = _extract_user_email(event)
            return handle_update_schedule(device_id, schedule_id, body, actor=actor)
        if method == "DELETE" and "/schedules/" in path:
            actor = _extract_user_email(event)
            return handle_delete_schedule(device_id, schedule_id, actor=actor)

        # ===== Metrics =====
        if method == "GET" and path.endswith("/metrics/today"):
            return handle_metrics_today(device_id)

        # ===== Device info (telemetria del ESP32) =====
        if method == "GET" and path.endswith("/info"):
            return handle_get_info(device_id)

        # ===== FCM push token (Fase 20) =====
        # POST /users/me/fcm-token   body: {token}
        # DELETE /users/me/fcm-token
        if path.endswith("/users/me/fcm-token"):
            user_email = _extract_user_email(event)
            if not user_email:
                return _response(401, {"error": "no user identity in token"})
            if method == "POST":
                return handle_register_fcm_token(user_email, body)
            if method == "DELETE":
                return handle_unregister_fcm_token(user_email)

        # ===== Notificaciones (bandeja persistida en pawgate_notifications) =====
        # GET    /users/me/notifications?limit=&onlyUnread=
        # GET    /users/me/notifications/unread-count
        # POST   /users/me/notifications/read         (marca TODAS leidas)
        # POST   /users/me/notifications/{notif_id}/read   (marca UNA leida)
        if "/users/me/notifications" in path:
            user_email = _extract_user_email(event)
            if not user_email:
                return _response(401, {"error": "no user identity in token"})

            query_params = event.get("queryStringParameters") or {}
            path_params = event.get("pathParameters") or {}

            if method == "GET" and path.endswith("/notifications/unread-count"):
                return handle_notifications_unread_count(user_email)
            if method == "POST" and path.endswith("/notifications/read"):
                return handle_notifications_mark_all_read(user_email)
            if method == "POST" and path.endswith("/read") and "/notifications/" in path:
                notif_id = path_params.get("notif_id")
                if not notif_id:
                    # fallback: parsear de path /users/me/notifications/{id}/read
                    parts = path.rstrip("/").split("/")
                    if len(parts) >= 2 and parts[-1] == "read":
                        notif_id = parts[-2]
                return handle_notifications_mark_one_read(user_email, notif_id)
            if method == "GET" and path.endswith("/notifications"):
                return handle_notifications_list(user_email, query_params)

        # ===== Device state =====
        if method == "GET" and path.endswith("/state"):
            return handle_get_state(device_id)
        if method == "POST" and path.endswith("/state/override-unblock"):
            actor = _extract_user_email(event)
            return handle_override_unblock(device_id, actor=actor)
        if method == "POST" and path.endswith("/state/override-block"):
            actor = _extract_user_email(event)
            return handle_override_block(device_id, actor=actor)

        return _response(404, {"error": f"route not found: {method} {path}"})

    except Exception as e:
        logger.exception("Unhandled error")
        return _server_error(str(e))


# ============================================================
# AUTH HANDLERS (sin cambios)
# ============================================================

def handle_signup(body):
    email = body.get("email")
    password = body.get("password")
    name = body.get("name", "")
    if not email or not password:
        return _bad_request("email y password son requeridos")
    try:
        cognito.sign_up(
            ClientId=APP_CLIENT_ID,
            Username=email,
            Password=password,
            UserAttributes=[
                {"Name": "email", "Value": email},
                {"Name": "name", "Value": name or email.split("@")[0]},
            ],
        )
        return _ok({"message": "user created, email verification sent", "email": email})
    except ClientError as e:
        code = e.response["Error"]["Code"]
        if code == "UsernameExistsException":
            return _response(409, {"error": "email already registered"})
        if code == "InvalidPasswordException":
            return _bad_request("password must be 8+ chars with upper, lower, number")
        if code == "InvalidParameterException":
            return _bad_request(e.response["Error"]["Message"])
        raise


def handle_confirm(body):
    email = body.get("email")
    code = body.get("code")
    if not email or not code:
        return _bad_request("email y code son requeridos")
    try:
        cognito.confirm_sign_up(ClientId=APP_CLIENT_ID, Username=email, ConfirmationCode=code)
        return _ok({"message": "email confirmed, you can now login"})
    except ClientError as e:
        code_err = e.response["Error"]["Code"]
        if code_err == "CodeMismatchException":
            return _unauthorized("invalid confirmation code")
        if code_err == "ExpiredCodeException":
            return _unauthorized("confirmation code expired")
        raise


def handle_login(body):
    email = body.get("email")
    password = body.get("password")
    if not email or not password:
        return _bad_request("email y password son requeridos")
    try:
        resp = cognito.initiate_auth(
            ClientId=APP_CLIENT_ID,
            AuthFlow="USER_PASSWORD_AUTH",
            AuthParameters={"USERNAME": email, "PASSWORD": password},
        )
        tokens = resp["AuthenticationResult"]
        # Audit: registramos el login para que aparezca en la bandeja de los
        # OTROS users (sirve para detectar accesos no autorizados). El user
        # que se logueo no necesita verlo, pero igual le aparece como "Vos".
        _audit_notify(email, "pawgate-001", "login", {})
        return _ok({
            "idToken":      tokens["IdToken"],
            "accessToken":  tokens["AccessToken"],
            "refreshToken": tokens["RefreshToken"],
            "expiresIn":    tokens["ExpiresIn"],
        })
    except ClientError as e:
        code = e.response["Error"]["Code"]
        if code in ("NotAuthorizedException", "UserNotFoundException"):
            return _unauthorized("email o password incorrecto")
        if code == "UserNotConfirmedException":
            return _unauthorized("email no confirmado, revisa tu inbox")
        raise


def handle_refresh(body):
    """
    POST /auth/refresh
    Body: {"refreshToken": "<refresh token de Cognito>"}

    Usa el refreshToken (vida util 30 dias) para obtener un nuevo idToken
    + accessToken (vida util 1 hora cada uno). Cognito NO devuelve un nuevo
    refreshToken en este flow — el cliente debe seguir usando el original
    hasta que se venza (a los 30 dias el user va a tener que loguearse de
    vuelta con email/password).

    Errores tipicos:
      - NotAuthorizedException: refresh token vencido o invalido (>30 dias
        sin uso, o el user cambio password, o el admin desactivo la cuenta).
        En este caso el cliente debe forzar logout y mostrar login.
    """
    refresh_token = body.get("refreshToken")
    if not refresh_token:
        return _bad_request("refreshToken requerido")
    try:
        resp = cognito.initiate_auth(
            ClientId=APP_CLIENT_ID,
            AuthFlow="REFRESH_TOKEN_AUTH",
            AuthParameters={"REFRESH_TOKEN": refresh_token},
        )
        tokens = resp["AuthenticationResult"]
        # OJO: no incluimos refreshToken en la respuesta porque Cognito no lo
        # devuelve aca. El cliente sigue usando el viejo.
        return _ok({
            "idToken":     tokens["IdToken"],
            "accessToken": tokens["AccessToken"],
            "expiresIn":   tokens.get("ExpiresIn", 3600),
        })
    except ClientError as e:
        code = e.response["Error"]["Code"]
        if code in ("NotAuthorizedException", "UserNotFoundException"):
            return _unauthorized("refresh token invalido o vencido")
        logger.exception("handle_refresh unexpected error")
        return _server_error(str(e))


# ============================================================
# DEVICE: history + cmd
# ============================================================

def handle_history(device_id, query_params):
    if not device_id:
        return _bad_request("device_id requerido")
    # Build tag para confirmar en CloudWatch que el lambda fue redeployado.
    logger.info("handle_history build=v2026-06-08-r3-downsample-5min device=%s params=%s",
                device_id, dict(query_params))
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    # Default = ultimos 30 dias. El chip 'Todas' del cliente manda from=null,
    # asi puede paginar hacia atras todo lo que el TTL (90d) conserve.
    from_ms = int(query_params.get("from", now_ms - 30 * 86400 * 1000))
    to_ms = int(query_params.get("to", now_ms))
    from_sk = f"{from_ms:013d}"
    to_sk = f"{to_ms:013d}#~"
    include_sensors = (query_params.get("include_sensors") or "").lower() == "true"
    cursor = query_params.get("cursor")

    base_kwargs = {
        "KeyConditionExpression": "device_id = :dev AND ts_event BETWEEN :from_sk AND :to_sk",
        "ExpressionAttributeValues": {":dev": device_id, ":from_sk": from_sk, ":to_sk": to_sk},
        "ScanIndexForward": False,
        "Limit": 500,  # read budget por DDB query (max efectivo ~1MB)
    }
    if not include_sensors:
        base_kwargs["FilterExpression"] = "#t <> :sensor"
        base_kwargs["ExpressionAttributeNames"] = {"#t": "type"}
        base_kwargs["ExpressionAttributeValues"][":sensor"] = "sensor"

    # Cursor del cliente -> ExclusiveStartKey de la primera query DDB
    import base64
    pagination_key = None
    if cursor:
        try:
            pagination_key = json.loads(base64.b64decode(cursor).decode())
        except Exception:
            return _bad_request("cursor invalido")

    # Auto-paginate INTERNO: seguimos pidiendo paginas DDB hasta tener target_size
    # items DESPUES del filter, o agotar el rango. Sin esto, si los primeros 200
    # raw son todos sensors, la respuesta venia con 0 items aunque hubiera mas
    # door events 'mas adelante'.
    target_size = 50
    items = []
    safety = 0
    # Downsampling de sensores cuando include_sensors=true: si publican cada
    # 5s, en 50 items entran solo ~4 minutos y los door events quedan
    # 'ahogados' debajo de cientos de telemetrias. Tomamos solo 1 sensor por
    # bucket de 5 minutos asi la pagina queda con mezcla utiles de sensors +
    # door events. Door events NUNCA se decimitan.
    sensor_bucket_ms = 5 * 60 * 1000  # 5 minutos
    last_sensor_bucket = None  # tracking entre paginas DDB

    while True:
        kwargs = dict(base_kwargs)
        if pagination_key:
            kwargs["ExclusiveStartKey"] = pagination_key
        try:
            result = events_table.query(**kwargs)
        except ClientError as e:
            logger.error("DDB query failed: %s", e)
            return _server_error("query failed")

        for raw_item in result.get("Items", []):
            if include_sensors and raw_item.get("type") == "sensor":
                # ts_event format: "<ms 013d>#<type>#<event_type>"
                try:
                    ts_ms = int(str(raw_item["ts_event"]).split("#", 1)[0])
                    bucket = ts_ms // sensor_bucket_ms
                    if bucket == last_sensor_bucket:
                        continue  # ya tengo un sensor de este bucket
                    last_sensor_bucket = bucket
                except (ValueError, IndexError, KeyError):
                    pass
            items.append(raw_item)

        pagination_key = result.get("LastEvaluatedKey")
        safety += 1
        if len(items) >= target_size or not pagination_key or safety >= 20:
            break

    # Si nos pasamos del target, truncar y construir cursor con el sort key
    # del ultimo item devuelto (ExclusiveStartKey funciona con la key, no
    # necesita el LastEvaluatedKey original de DDB).
    next_cursor_dict = None
    if len(items) > target_size:
        truncated = items[:target_size]
        last_item = truncated[-1]
        next_cursor_dict = {
            "device_id": last_item["device_id"],
            "ts_event": last_item["ts_event"],
        }
        items = truncated
    elif pagination_key:
        next_cursor_dict = pagination_key

    for it in items:
        if "payload" in it and isinstance(it["payload"], str):
            try:
                it["payload"] = json.loads(it["payload"])
            except json.JSONDecodeError:
                pass

    response = {
        "device_id": device_id,
        "from": from_ms,
        "to": to_ms,
        "count": len(items),
        "events": items,
    }
    if next_cursor_dict:
        response["next_cursor"] = base64.b64encode(
            json.dumps(next_cursor_dict, cls=DecimalEncoder).encode()).decode()

    return _ok(response)


def handle_cmd(device_id, cmd, body, actor=None):
    if not device_id or not cmd:
        return _bad_request("device_id y cmd son requeridos")
    allowed_cmds = {"open", "block", "unblock", "call", "cancel", "reboot"}
    if cmd not in allowed_cmds:
        return _bad_request(f"cmd '{cmd}' no permitido. Allowed: {allowed_cmds}")

    # Para block/unblock, sincronizar el lock_state en DDB. Sin esto el polling
    # del app leeria un state desactualizado y revertiria el flag local en 3s,
    # generando un loop visual de bloquear/desbloquear.
    if cmd in ("block", "unblock"):
        in_horario = _currently_in_horario(device_id)
        if cmd == "block":
            target_state = "MANUAL_BLOCKED" if in_horario else "AUTO_BLOCKED"
        else:
            target_state = "AUTO_UNBLOCKED" if in_horario else "MANUAL_UNBLOCKED"
        _set_device_state(device_id, target_state)

    # Para cmd=open, validar direction del body si vino. Valores aceptados:
    # 'in' (hacia adentro / casa) o 'out' (hacia afuera / patio). Si no vino,
    # el device lo decide (firmware real: segun sensor; simulator: alterna).
    if cmd == "open" and isinstance(body, dict):
        dir_val = body.get("direction")
        if dir_val is not None and dir_val not in ("in", "out"):
            return _bad_request("direction debe ser 'in' o 'out'")

    topic = f"pawgate/{device_id}/cmd/{cmd}"
    payload = {
        "source": "api",
        "ts": int(datetime.now(timezone.utc).timestamp() * 1000),
        **(body if isinstance(body, dict) else {}),
    }
    try:
        iot_data.publish(topic=topic, qos=1, payload=json.dumps(payload))
        logger.info("Published to %s: %s", topic, payload)
        # Audit/notif: registramos QUE el user ejecuto un cmd manual. El evento
        # del ESP32 (door opened) tambien se va a notificar via eventIngest,
        # pero con type=opened (sin actor). Aca lo registramos con actor=user
        # para que en la bandeja se vea "Vos abriste la puerta" antes de que
        # el ESP32 confirme con el evento real.
        _audit_notify(actor, device_id, f"cmd_{cmd}", body)
        return _ok({"queued": True, "topic": topic, "payload": payload})
    except ClientError as e:
        logger.error("IoT publish failed: %s", e)
        return _server_error("publish failed")


# ============================================================
# SCHEDULES CRUD
# ============================================================

def _validate_schedule(body):
    """Devuelve (errors_list, sanitized_dict). errors_list vacio = OK."""
    errors = []
    nombre = (body.get("nombre") or "").strip()
    hora_inicio = body.get("hora_inicio") or ""
    hora_fin = body.get("hora_fin") or ""
    dias = body.get("dias") or []
    activo = bool(body.get("activo", True))

    if len(nombre) < 3:
        errors.append("nombre debe tener al menos 3 caracteres")

    for label, val in (("hora_inicio", hora_inicio), ("hora_fin", hora_fin)):
        try:
            h, m = val.split(":")
            h = int(h); m = int(m)
            if not (0 <= h <= 23):
                errors.append(f"{label}: hora fuera de rango")
                continue
            if m not in (0, 30):
                errors.append(f"{label}: los minutos deben ser 00 o 30")
        except (ValueError, AttributeError):
            errors.append(f"{label}: formato invalido (esperado HH:MM)")

    if not isinstance(dias, list) or not dias:
        errors.append("dias debe ser lista no vacia")
    else:
        for d in dias:
            if d not in VALID_DAYS:
                errors.append(f"dias contiene valor invalido: {d}")
                break

    sanitized = {
        "nombre": nombre,
        "hora_inicio": hora_inicio,
        "hora_fin": hora_fin,
        "dias": dias,
        "activo": activo,
    }
    return errors, sanitized


def handle_list_schedules(device_id):
    if not device_id:
        return _bad_request("device_id requerido")
    try:
        result = schedules_table.query(
            KeyConditionExpression="device_id = :dev",
            ExpressionAttributeValues={":dev": device_id},
        )
    except ClientError as e:
        logger.error("DDB query schedules failed: %s", e)
        return _server_error("query failed")
    return _ok({"device_id": device_id, "schedules": result.get("Items", [])})


def handle_create_schedule(device_id, body, actor=None):
    if not device_id:
        return _bad_request("device_id requerido")
    errors, sanitized = _validate_schedule(body)
    if errors:
        return _bad_request("; ".join(errors))

    schedule_id = str(uuid.uuid4())
    item = {
        "device_id": device_id,
        "schedule_id": schedule_id,
        **sanitized,
        "created_at": _iso_now(),
        "updated_at": _iso_now(),
    }
    try:
        schedules_table.put_item(Item=item)
    except ClientError as e:
        logger.error("DDB put schedule failed: %s", e)
        return _server_error("create failed")
    _audit_notify(actor, device_id, "schedule_created", {"nombre": sanitized["nombre"]})
    return _created(item)


def handle_update_schedule(device_id, schedule_id, body, actor=None):
    if not device_id or not schedule_id:
        return _bad_request("device_id y schedule_id son requeridos")
    errors, sanitized = _validate_schedule(body)
    if errors:
        return _bad_request("; ".join(errors))

    try:
        schedules_table.update_item(
            Key={"device_id": device_id, "schedule_id": schedule_id},
            UpdateExpression=(
                "SET nombre = :n, hora_inicio = :hi, hora_fin = :hf, "
                "dias = :d, activo = :a, updated_at = :u"
            ),
            ExpressionAttributeValues={
                ":n":  sanitized["nombre"],
                ":hi": sanitized["hora_inicio"],
                ":hf": sanitized["hora_fin"],
                ":d":  sanitized["dias"],
                ":a":  sanitized["activo"],
                ":u":  _iso_now(),
            },
            ConditionExpression="attribute_exists(device_id) AND attribute_exists(schedule_id)",
            ReturnValues="ALL_NEW",
        )
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return _not_found("schedule not found")
        logger.error("DDB update schedule failed: %s", e)
        return _server_error("update failed")

    item = {"device_id": device_id, "schedule_id": schedule_id, **sanitized}
    _audit_notify(actor, device_id, "schedule_updated", {"nombre": sanitized["nombre"]})
    return _ok(item)


def handle_delete_schedule(device_id, schedule_id, actor=None):
    if not device_id or not schedule_id:
        return _bad_request("device_id y schedule_id son requeridos")
    # Antes de borrar leemos el nombre para incluirlo en la notif.
    nombre = None
    try:
        existing = schedules_table.get_item(
            Key={"device_id": device_id, "schedule_id": schedule_id}
        ).get("Item")
        if existing:
            nombre = existing.get("nombre")
    except ClientError:
        pass

    try:
        schedules_table.delete_item(
            Key={"device_id": device_id, "schedule_id": schedule_id},
            ConditionExpression="attribute_exists(schedule_id)",
        )
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return _not_found("schedule not found")
        logger.error("DDB delete schedule failed: %s", e)
        return _server_error("delete failed")
    _audit_notify(actor, device_id, "schedule_deleted",
                  {"nombre": nombre or "(sin nombre)"})
    return _no_content()


# ============================================================
# DEVICE STATE + OVERRIDE
# ============================================================

def handle_get_state(device_id):
    if not device_id:
        return _bad_request("device_id requerido")
    state = _get_device_state(device_id)
    in_horario = _currently_in_horario(device_id)
    return _ok({
        "device_id": device_id,
        "lock_state": state["lock_state"],
        "updated_at": state.get("updated_at"),
        "currently_in_horario": in_horario,
    })


def _extract_user_email(event):
    """
    Toma el email del Cognito ID token via API Gateway authorizer claims.
    El authorizer ya valido el token, asi que confiamos en las claims.
    """
    try:
        claims = event["requestContext"]["authorizer"]["claims"]
        return claims.get("email") or claims.get("cognito:username") or ""
    except (KeyError, TypeError):
        return ""


def handle_register_fcm_token(user_email, body):
    """
    POST /users/me/fcm-token
    Body: {"token": "<fcm device token>"}

    Llama a SNS createPlatformEndpoint para registrar el token en la
    Platform Application y guarda el ARN en pawgate_fcm_endpoints
    (PK user_email). Si el user ya tenia un endpoint, lo reemplaza.
    """
    if not FCM_PLATFORM_APP_ARN:
        return _server_error("FCM_PLATFORM_APP_ARN no configurado en lambda")
    token = (body.get("token") or "").strip()
    if not token:
        return _bad_request("token requerido")

    # 1) Si ya hay un endpoint registrado para este user, lo borramos antes
    #    de crear uno nuevo (asi SNS no acumula endpoints muertos por el
    #    mismo user con tokens viejos).
    try:
        old = fcm_endpoints_table.get_item(Key={"user_email": user_email}).get("Item")
        if old and old.get("endpoint_arn"):
            try:
                sns.delete_endpoint(EndpointArn=old["endpoint_arn"])
            except Exception:
                pass
    except Exception:
        pass

    # 2) Crear el endpoint en SNS. Si el token ya estaba registrado en SNS
    #    bajo otro user, SNS reusa el ARN existente.
    try:
        resp = sns.create_platform_endpoint(
            PlatformApplicationArn=FCM_PLATFORM_APP_ARN,
            Token=token,
        )
        endpoint_arn = resp["EndpointArn"]
    except Exception as e:
        logger.exception("sns.create_platform_endpoint failed")
        return _server_error(f"sns.create_platform_endpoint failed: {e}")

    # 3) Reactivar el endpoint en caso de que SNS lo hubiera dejado disabled
    #    por un push fallido previo (UNREGISTERED).
    try:
        sns.set_endpoint_attributes(
            EndpointArn=endpoint_arn,
            Attributes={"Enabled": "true", "Token": token},
        )
    except Exception as e:
        logger.warning("set_endpoint_attributes failed: %s", e)

    # 4) Antes de persistir, limpiar OTRAS filas que apunten al MISMO
    #    endpoint_arn (mismo device, otro user previamente logueado).
    #    Sino, cuando llega un evento, _notify_owners hace scan, encuentra
    #    N filas con el mismo ARN, y publica N push notifications al mismo
    #    device. La deduplicacion en eventIngest tambien lo cubre como
    #    safety net, pero limpiar aca evita acumular registros muertos.
    try:
        existing = fcm_endpoints_table.scan(
            FilterExpression="endpoint_arn = :arn AND user_email <> :ue",
            ExpressionAttributeValues={
                ":arn": endpoint_arn,
                ":ue":  user_email,
            },
        ).get("Items", [])
        for stale in existing:
            try:
                fcm_endpoints_table.delete_item(
                    Key={"user_email": stale["user_email"]})
                logger.info("Cleaned stale FCM mapping user=%s -> arn=%s",
                            stale["user_email"], endpoint_arn)
            except Exception:
                pass
    except Exception as e:
        logger.warning("scan-and-clean stale endpoints failed: %s", e)

    # 5) Persistir el mapeo user_email -> endpoint_arn en DDB.
    fcm_endpoints_table.put_item(Item={
        "user_email":   user_email,
        "endpoint_arn": endpoint_arn,
        "updated_at":   datetime.now(timezone.utc).isoformat(),
    })
    logger.info("Registered FCM endpoint user=%s arn=%s", user_email, endpoint_arn)
    return _ok({"registered": True, "endpoint_arn": endpoint_arn})


def handle_unregister_fcm_token(user_email):
    """DELETE /users/me/fcm-token — usado en logout."""
    try:
        item = fcm_endpoints_table.get_item(Key={"user_email": user_email}).get("Item")
    except Exception as e:
        logger.error("fcm_endpoints get_item failed: %s", e)
        return _server_error("DDB error")
    if not item:
        return _ok({"unregistered": False, "reason": "no token registered"})
    arn = item.get("endpoint_arn")
    if arn:
        try:
            sns.delete_endpoint(EndpointArn=arn)
        except Exception as e:
            logger.warning("sns.delete_endpoint failed: %s", e)
    try:
        fcm_endpoints_table.delete_item(Key={"user_email": user_email})
    except Exception as e:
        logger.error("fcm_endpoints delete_item failed: %s", e)
    return _ok({"unregistered": True})


def handle_get_info(device_id):
    """GET /devices/{id}/info

    Devuelve el ultimo snapshot de telemetria que publico el device en el
    topic events/telemetry. Lo guarda eventIngest en pawgate_device_state
    como atributo 'info' cada 30s.

    Si el device nunca publico telemetria (recien creado, o solo simulator
    sin teletry loop activo), devolvemos un objeto con online=false y los
    campos en null/0.
    """
    if not device_id:
        return _bad_request("device_id requerido")
    state = _get_device_state(device_id)
    info = state.get("info") or {}
    info_updated_at = state.get("info_updated_at")

    # online: heuristica simple -- si el ultimo telemetry fue hace <2 minutos,
    # consideramos al device online. Sino offline. Como el simulator publica
    # cada 30s, 2 min de margen tolera ~3 paquetes perdidos.
    online = False
    if info_updated_at:
        try:
            last = datetime.fromisoformat(info_updated_at.replace("Z", "+00:00"))
            now = datetime.now(timezone.utc)
            online = (now - last).total_seconds() < 120
        except Exception:
            online = False

    return _ok({
        "device_id":        device_id,
        "online":           online,
        "info_updated_at":  info_updated_at,
        "uptime_s":         int(info.get("uptime_s", 0) or 0),
        "rssi_dbm":         int(info.get("rssi_dbm", 0) or 0),
        "free_heap_kb":     int(info.get("free_heap_kb", 0) or 0),
        "total_heap_kb":    int(info.get("total_heap_kb", 0) or 0),
        "flash_used_kb":    int(info.get("flash_used_kb", 0) or 0),
        "flash_total_kb":   int(info.get("flash_total_kb", 0) or 0),
        "cpu_temp_c":       str(info.get("cpu_temp_c", "") or ""),
        "local_ip":         str(info.get("local_ip", "") or ""),
        "firmware_version": str(info.get("firmware_version", "") or ""),
        "hardware_model":   str(info.get("hardware_model", "") or ""),
        # WiFi info (W14)
        "wifi_ssid":        str(info.get("wifi_ssid", "") or ""),
        "wifi_bssid":       str(info.get("wifi_bssid", "") or ""),
        "wifi_band":        str(info.get("wifi_band", "") or ""),
        "wifi_gateway":     str(info.get("wifi_gateway", "") or ""),
        "wifi_security":    str(info.get("wifi_security", "") or ""),
    })


def handle_metrics_today(device_id):
    """GET /devices/{id}/metrics/today

    Calcula SERVER-SIDE las metricas del dia:
      - openings_today        cantidad de aperturas (event_type=opened)
      - last_door_event_*     info del ultimo evento door
      - light_minutes_today   minutos totales que la luz estuvo encendida
      - light_state           "on" / "off" — estado actual de la luz
      - light_state_at        timestamp del ultimo cambio de luz

    Iteramos TODOS los events del dia (door + light_on/off) y los agrupamos
    en pasada unica. light_minutes calcula la suma de duraciones entre cada
    par (light_on -> light_off). Si la luz quedo encendida cuando empezo el
    dia (ultimo evento de ayer fue light_on), se cuenta desde 00:00.
    """
    if not device_id:
        return _bad_request("device_id requerido")

    # Rango de hoy en zona horaria local (Argentina)
    from zoneinfo import ZoneInfo
    tz = ZoneInfo("America/Argentina/Buenos_Aires")
    now_local = datetime.now(tz)
    start_local = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
    from_ms = int(start_local.timestamp() * 1000)
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

    from_sk = f"{from_ms:013d}"
    to_sk = f"{now_ms:013d}#~"

    openings_count = 0
    last_door_event = None  # primer item iterado (mas reciente por ScanIndexForward=False)
    # Para light: coleccionamos todos los light_on/off del dia ORDENADOS por
    # ts_event ascendente, y al final calculamos durations.
    light_events = []  # list of (ts_ms, "on" | "off", created_at_iso)
    pagination_key = None
    safety_pages = 0

    while True:
        kwargs = {
            "KeyConditionExpression":
                "device_id = :dev AND ts_event BETWEEN :from_sk AND :to_sk",
            # Aceptamos type=door (que incluye opened/closed/blocked/unblocked
            # Y light_on/light_off — el firmware los publica todos al mismo
            # topic events/door, asi que el event_kind queda en 'door' para
            # todos). El sensor crudo (type=sensor) lo descartamos.
            "FilterExpression": "#t = :door",
            "ExpressionAttributeNames": {"#t": "type"},
            "ExpressionAttributeValues": {
                ":dev": device_id,
                ":from_sk": from_sk,
                ":to_sk": to_sk,
                ":door": "door",
            },
            "ScanIndexForward": False,
            "Limit": 200,  # tamano de chunk raw; FilterExpression reduce post-read
        }
        if pagination_key:
            kwargs["ExclusiveStartKey"] = pagination_key

        try:
            result = events_table.query(**kwargs)
        except ClientError as e:
            logger.error("metrics query failed: %s", e)
            return _server_error("metrics query failed")

        items = result.get("Items", [])
        for item in items:
            etype = item.get("event_type")
            if etype in ("light_on", "light_off"):
                # ts_event = "{epoch_ms_13}#{kind}#{event_type}" — extraer ts.
                try:
                    ts_ms = int(item["ts_event"].split("#", 1)[0])
                except (KeyError, ValueError):
                    continue
                state_short = "on" if etype == "light_on" else "off"
                light_events.append((ts_ms, state_short, item.get("created_at")))
                continue
            # Door events (opened/closed/blocked/unblocked): tomar el ultimo
            # para el resumen y contar opens.
            if last_door_event is None:
                last_door_event = item
            if etype == "opened":
                openings_count += 1

        pagination_key = result.get("LastEvaluatedKey")
        if not pagination_key:
            break
        safety_pages += 1
        if safety_pages > 50:  # 50 pages * 200 items = 10k items, mas que suficiente
            logger.warning("metrics_today hit page limit, count may be partial")
            break

    light_minutes, light_state, light_state_at = _compute_light_metrics(
        light_events, from_ms, now_ms)

    response = {
        "device_id":           device_id,
        "from_ms":             from_ms,
        "to_ms":               now_ms,
        "openings_today":      openings_count,
        "light_minutes_today": light_minutes,
        "light_state":         light_state,
        "light_state_at":      light_state_at,
    }
    if last_door_event:
        response["last_door_event_at"] = last_door_event.get("created_at")
        response["last_door_event_type"] = last_door_event.get("event_type")
        if "direction" in last_door_event:
            response["last_door_event_direction"] = last_door_event["direction"]

    return _ok(response)


def _compute_light_metrics(light_events, from_ms, now_ms):
    """
    A partir de la lista [(ts_ms, "on"|"off", iso)] del dia, devuelve:
      (minutos_encendida, estado_actual_str, timestamp_iso_del_ultimo_cambio)

    Algoritmo:
      Ordena por ts ASC.
      Recorre tracking 'last_on_ts': cuando aparece 'on', guarda ts.
      Cuando aparece 'off' y hay last_on_ts, suma (off_ts - last_on_ts) al total.
      Si al final del recorrido sigue encendida (last_on_ts != None y no
      vino off), suma (now_ms - last_on_ts).

    Estado actual: el state del ULTIMO evento (mas reciente).
    """
    if not light_events:
        return 0, "off", None
    # Ordenar ASC por timestamp (vienen de DDB en DESC).
    light_events.sort(key=lambda x: x[0])

    total_on_ms = 0
    last_on_ts = None
    for ts_ms, state, _iso in light_events:
        if state == "on" and last_on_ts is None:
            last_on_ts = ts_ms
        elif state == "off" and last_on_ts is not None:
            total_on_ms += (ts_ms - last_on_ts)
            last_on_ts = None
    # Si quedo encendida al final, sumar hasta ahora.
    if last_on_ts is not None:
        total_on_ms += (now_ms - last_on_ts)

    # Estado actual = el del ultimo evento por ts (que es el ultimo de la list
    # ordenada ASC, o sea el final).
    last_ts, last_state, last_iso = light_events[-1]

    return total_on_ms // 60_000, last_state, last_iso


def handle_override_unblock(device_id, actor=None):
    """Manual override: el user desbloquea fuera de horario.
       Setea lock_state=MANUAL_UNBLOCKED y publica cmd/unblock al device.
       El cron lo respeta hasta que entre a un horario natural."""
    return _do_override(device_id, "MANUAL_UNBLOCKED", "unblock", actor=actor,
                         notif_type="override_unblock")


def handle_override_block(device_id, actor=None):
    """Manual override: el user bloquea dentro de horario.
       Setea lock_state=MANUAL_BLOCKED y publica cmd/block al device.
       El cron lo respeta hasta que salga del horario natural."""
    return _do_override(device_id, "MANUAL_BLOCKED", "block", actor=actor,
                         notif_type="override_block")


def _do_override(device_id, target_state, cmd, actor=None, notif_type=None):
    if not device_id:
        return _bad_request("device_id requerido")
    _set_device_state(device_id, target_state)
    topic = f"pawgate/{device_id}/cmd/{cmd}"
    payload = {
        "source": "api_override",
        "ts": int(datetime.now(timezone.utc).timestamp() * 1000),
    }
    try:
        iot_data.publish(topic=topic, qos=1, payload=json.dumps(payload))
    except ClientError as e:
        logger.error("IoT publish failed: %s", e)
        return _server_error("publish failed")
    if notif_type:
        _audit_notify(actor, device_id, notif_type, {"target_state": target_state})
    return _ok({"lock_state": target_state, "topic": topic, "payload": payload})


# ============================================================
# HELPERS DE STATE
# ============================================================

def _get_device_state(device_id):
    """Lee el state del device. Si no existe, devuelve default AUTO_BLOCKED.

    ConsistentRead=True: el endpoint /state lo consume el polling del app
    cada 3s; si pudiera leer stale, justo despues de un override veria
    AUTO_* viejo y la app revertiria el flag local."""
    try:
        resp = device_state_table.get_item(
            Key={"device_id": device_id},
            ConsistentRead=True,
        )
    except ClientError as e:
        logger.error("DDB get_state failed: %s", e)
        return {"lock_state": "AUTO_BLOCKED", "updated_at": None}
    item = resp.get("Item")
    if not item:
        return {"lock_state": "AUTO_BLOCKED", "updated_at": None}
    return item


def _set_device_state(device_id, lock_state):
    if lock_state not in VALID_LOCK_STATES:
        raise ValueError(f"invalid lock_state: {lock_state}")
    # Persistimos el horario_marker actual asi el cron sabe en que contexto
    # se activo el override. Si despues el set de horarios activos cambia,
    # el cron libera el override.
    marker = _current_horario_marker(device_id)
    logger.info("set_device_state device=%s lock_state=%s marker='%s'",
                device_id, lock_state, marker)
    device_state_table.put_item(Item={
        "device_id": device_id,
        "lock_state": lock_state,
        "horario_marker": marker,
        "updated_at": _iso_now(),
    })


def _current_horario_marker(device_id):
    """Devuelve el marker (sorted ids joined con '|') de horarios activos AHORA.
       Mismo formato que usa el scheduleExecutor."""
    try:
        resp = schedules_table.query(
            KeyConditionExpression="device_id = :dev",
            ExpressionAttributeValues={":dev": device_id},
        )
    except ClientError:
        return ""
    from zoneinfo import ZoneInfo
    tz = ZoneInfo("America/Argentina/Buenos_Aires")
    now = datetime.now(tz)
    day_map = ["L", "M", "X", "J", "V", "S", "D"]
    current_day = day_map[now.weekday()]
    current_min = now.hour * 60 + now.minute
    active = []
    for s in resp.get("Items", []):
        if not s.get("activo"):
            continue
        if current_day not in (s.get("dias") or []):
            continue
        try:
            h1, m1 = map(int, s["hora_inicio"].split(":"))
            h2, m2 = map(int, s["hora_fin"].split(":"))
        except (ValueError, KeyError, AttributeError):
            continue
        inicio_min = h1 * 60 + m1
        fin_min = h2 * 60 + m2
        is_active = (
            (fin_min > inicio_min and inicio_min <= current_min < fin_min)
            or (fin_min <= inicio_min and (current_min >= inicio_min or current_min < fin_min))
        )
        if is_active:
            active.append(s.get("schedule_id", ""))
    return "|".join(sorted(active))


def _currently_in_horario(device_id):
    """Evalua si AHORA mismo estamos dentro de algun schedule activo del device.

    Lee todos los schedules del device, filtra por activo y dia actual,
    y verifica si la hora actual cae dentro de [hora_inicio, hora_fin].
    Soporta cruce de medianoche (hora_fin < hora_inicio).
    """
    try:
        resp = schedules_table.query(
            KeyConditionExpression="device_id = :dev",
            ExpressionAttributeValues={":dev": device_id},
        )
    except ClientError:
        return False
    schedules = resp.get("Items", [])

    from zoneinfo import ZoneInfo
    tz = ZoneInfo("America/Argentina/Buenos_Aires")
    now = datetime.now(tz)
    day_map = ["L", "M", "X", "J", "V", "S", "D"]
    current_day = day_map[now.weekday()]
    current_min = now.hour * 60 + now.minute

    for s in schedules:
        if not s.get("activo"):
            continue
        if current_day not in (s.get("dias") or []):
            continue
        hi = s.get("hora_inicio", "")
        hf = s.get("hora_fin", "")
        try:
            h1, m1 = map(int, hi.split(":"))
            h2, m2 = map(int, hf.split(":"))
        except (ValueError, AttributeError):
            continue
        inicio_min = h1 * 60 + m1
        fin_min = h2 * 60 + m2
        if fin_min > inicio_min:
            if inicio_min <= current_min < fin_min:
                return True
        else:
            # Cruza medianoche: dentro si >= inicio o < fin
            if current_min >= inicio_min or current_min < fin_min:
                return True
    return False


def _iso_now():
    return datetime.now(timezone.utc).isoformat()


# ============================================================
# NOTIFICATIONS (bandeja persistente, Sub-fase B+C)
# ============================================================
#
# La tabla pawgate_notifications usa:
#   PK: user_email (S)
#   SK: notif_id   (S) = "{ts_inverted:013d}#{uuid8}"
#                        ts_inverted = 9_999_999_999_999 - timestamp_ms
#
# El SK invertido es el truco clave: como DynamoDB Query devuelve por
# defecto los SK en orden ASCENDENTE de string, y nosotros invertimos el
# timestamp, los items vienen automaticamente del MAS RECIENTE al mas viejo.
# Esto evita ScanIndexForward=False (que tambien funciona pero es menos
# ergonomico cuando paginamos en el cliente).
#
# Los items tienen ttl_epoch para que DynamoDB los borre solo a los 30 dias.


def handle_notifications_list(user_email, query_params):
    """GET /users/me/notifications?limit=&onlyUnread=

    Devuelve lista de notifs del user, mas recientes primero.
    Si onlyUnread=true, filtra solo las read=false.
    """
    try:
        limit = int(query_params.get("limit", "50"))
    except (TypeError, ValueError):
        limit = 50
    limit = max(1, min(limit, 100))
    only_unread = (query_params.get("onlyUnread", "").lower() == "true")

    # Como el SK ya esta invertido, ScanIndexForward=True (default) devuelve
    # del mas reciente al mas viejo.
    kwargs = {
        "KeyConditionExpression": "user_email = :u",
        "ExpressionAttributeValues": {":u": user_email},
        "Limit": limit,
    }
    if only_unread:
        # Reservamos 'read' porque es palabra reservada en DDB expressions.
        kwargs["FilterExpression"] = "#r = :false"
        kwargs["ExpressionAttributeNames"] = {"#r": "read"}
        kwargs["ExpressionAttributeValues"][":false"] = False
        # Con filter, traigo mas paginas para llenar el limit.
        kwargs["Limit"] = max(limit * 3, 100)

    items = []
    try:
        resp = notifications_table.query(**kwargs)
        items = resp.get("Items", [])
        # Si pidio onlyUnread, recortamos al limit real despues del filter.
        if only_unread:
            items = items[:limit]
    except ClientError as e:
        logger.error("notifications query failed: %s", e)
        return _server_error("query failed")

    return _ok({"items": [_notif_to_json(i) for i in items]})


def handle_notifications_unread_count(user_email):
    """GET /users/me/notifications/unread-count

    Devuelve {unread: N}. Hacemos Query con Select=COUNT y Filter por read=false.
    En este proyecto el volumen por user es chico (< 200 notifs), asi que
    un Query con scan-page es aceptable. Si crece, agregar un GSI por
    (user_email, read) y Count sobre el GSI.
    """
    total = 0
    last_key = None
    while True:
        kwargs = {
            "KeyConditionExpression": "user_email = :u",
            "ExpressionAttributeValues": {":u": user_email, ":false": False},
            "FilterExpression": "#r = :false",
            "ExpressionAttributeNames": {"#r": "read"},
            "Select": "COUNT",
        }
        if last_key:
            kwargs["ExclusiveStartKey"] = last_key
        try:
            resp = notifications_table.query(**kwargs)
        except ClientError as e:
            logger.error("unread-count query failed: %s", e)
            return _server_error("query failed")
        total += resp.get("Count", 0)
        last_key = resp.get("LastEvaluatedKey")
        if not last_key:
            break
        # Safety break: si pasa de 500 items unread, devolvemos lo que tengamos.
        if total >= 500:
            break
    return _ok({"unread": total})


def handle_notifications_mark_all_read(user_email):
    """POST /users/me/notifications/read

    Marca TODAS las notifs no leidas del user como leidas. Hacemos
    query+update item-por-item porque DDB no tiene un BATCH UPDATE.
    BatchWriteItem solo soporta Put/Delete, no Update.
    """
    updated = 0
    last_key = None
    while True:
        kwargs = {
            "KeyConditionExpression": "user_email = :u",
            "ExpressionAttributeValues": {":u": user_email, ":false": False},
            "FilterExpression": "#r = :false",
            "ExpressionAttributeNames": {"#r": "read"},
            "ProjectionExpression": "user_email, notif_id",
        }
        if last_key:
            kwargs["ExclusiveStartKey"] = last_key
        try:
            resp = notifications_table.query(**kwargs)
        except ClientError as e:
            logger.error("mark_all_read query failed: %s", e)
            return _server_error("query failed")

        for item in resp.get("Items", []):
            try:
                notifications_table.update_item(
                    Key={
                        "user_email": item["user_email"],
                        "notif_id":   item["notif_id"],
                    },
                    UpdateExpression="SET #r = :true",
                    ExpressionAttributeNames={"#r": "read"},
                    ExpressionAttributeValues={":true": True},
                )
                updated += 1
            except ClientError as e:
                logger.warning("update_item failed (ignored): %s", e)

        last_key = resp.get("LastEvaluatedKey")
        if not last_key:
            break
    return _ok({"updated": updated})


def handle_notifications_mark_one_read(user_email, notif_id):
    """POST /users/me/notifications/{notif_id}/read"""
    if not notif_id:
        return _bad_request("notif_id requerido")
    try:
        notifications_table.update_item(
            Key={"user_email": user_email, "notif_id": notif_id},
            UpdateExpression="SET #r = :true",
            ExpressionAttributeNames={"#r": "read"},
            ExpressionAttributeValues={":true": True},
            # ConditionExpression: que exista. Si no, devolvemos 404.
            ConditionExpression="attribute_exists(notif_id)",
        )
    except ClientError as e:
        code = e.response["Error"]["Code"]
        if code == "ConditionalCheckFailedException":
            return _response(404, {"error": "notif not found"})
        logger.error("mark_one_read failed: %s", e)
        return _server_error("update failed")
    return _ok({"ok": True})


def _notif_to_json(item):
    """Normaliza item de DDB a JSON para el cliente."""
    return {
        "notif_id":   item.get("notif_id"),
        "type":       item.get("type"),
        "title":      item.get("title"),
        "body":       item.get("body"),
        "device_id":  item.get("device_id"),
        "direction":  item.get("direction"),
        "actor":      item.get("actor"),      # quien lo ejecuto (Sub-fase D)
        "read":       bool(item.get("read", False)),
        "created_at": item.get("created_at"),
    }


# ============================================================
# AUDIT / TRAZABILIDAD (Sub-fase D)
# ============================================================
#
# Toda accion de un user autenticado (cmd, override, schedule CUD, login)
# se persiste como notif para TODOS los users registrados en
# pawgate_fcm_endpoints, con el campo actor=email para que en la app se vea
# quien lo hizo. El que hizo la accion se va a ver a si mismo como "Vos",
# el resto va a ver el nombre del que lo hizo.
#
# Diseño multi-user: si la familia entera tiene 3 cuentas activas y uno
# bloquea la puerta, los 3 ven en su bandeja "Puerta bloqueada por
# nombre_del_user". Para no spammear el celu del que disparo la accion con
# push, NO disparamos push (solo persistimos). Si en el futuro queremos push
# para acciones de OTROS users, podemos cambiarlo aca.

# Tipos soportados (13 + los 4 del firmware):
#   Comandos manuales:   cmd_open, cmd_block, cmd_unblock, cmd_call, cmd_cancel
#   Overrides:           override_unblock, override_block
#   Schedules:           schedule_created, schedule_updated, schedule_deleted
#   Auth:                login
#   ESP32/system:        opened, closed, blocked, unblocked  (los pone eventIngest)


def _audit_notify(actor_email, device_id, notif_type, extra):
    """
    Persiste una notif para CADA user registrado en pawgate_fcm_endpoints,
    con actor=actor_email. Si actor_email es None, se setea "system".

    Best-effort: errores no bloquean la respuesta al cliente. La notif
    es secundaria al efecto real (publish a IoT / put en DDB), por eso si
    DDB esta lento no queremos demorar al user.
    """
    try:
        title, body = _format_audit_notification(notif_type, actor_email, extra)
        if not title:
            return  # type no soportado, no hacemos nada
        users = _list_registered_users()
        if not users:
            return
        actor = actor_email or "system"
        now = datetime.now(timezone.utc)
        for user_email in users:
            _put_audit_notification(user_email, device_id, notif_type,
                                     title, body, actor, extra, now)
    except Exception as e:
        # Catch general: la trazabilidad no rompe la operacion principal.
        logger.warning("audit_notify failed (ignored): %s", e)


def _list_registered_users():
    """Devuelve set de user_email distintos en pawgate_fcm_endpoints.
       Es la fuente de verdad de 'usuarios activos del sistema'. Si tu cuenta
       no registro un FCM endpoint (ej: te logueaste en un device sin permisos
       de notif), no entras en este broadcast."""
    try:
        resp = fcm_endpoints_table.scan(
            ProjectionExpression="user_email"
        )
    except ClientError as e:
        logger.warning("scan fcm endpoints failed: %s", e)
        return set()
    return {it.get("user_email") for it in resp.get("Items", []) if it.get("user_email")}


def _put_audit_notification(user_email, device_id, notif_type,
                              title, body, actor, extra, now):
    """Inserta UNA fila en pawgate_notifications. Mismo schema que
       eventIngest._persist_notification (Sub-fase B)."""
    ts_ms = int(now.timestamp() * 1000)
    # Separador '_' (NO '#'). Ver _persist_notification en eventIngest.
    ts_inverted = 9_999_999_999_999 - ts_ms
    notif_id = f"{ts_inverted:013d}_{uuid.uuid4().hex[:8]}"

    item = {
        "user_email":  user_email,
        "notif_id":    notif_id,
        "type":        notif_type,
        "title":       title,
        "body":        body,
        "device_id":   device_id or "pawgate-001",
        "actor":       actor,
        "read":        False,
        "created_at":  now.isoformat(),
        "ttl_epoch":   int((now + timedelta(days=30)).timestamp()),
    }
    if isinstance(extra, dict) and extra.get("direction") in ("in", "out"):
        item["direction"] = extra["direction"]

    try:
        notifications_table.put_item(Item=item)
    except ClientError as e:
        logger.warning("put audit notif failed: %s", e)


def _format_audit_notification(notif_type, actor_email, extra):
    """Devuelve (title, body) en castellano. Si el type no se reconoce,
       devuelve (None, None) y la notif no se persiste."""
    actor_label = _short_actor_label(actor_email)
    extra = extra or {}

    # ----------------------------------------------------------------
    # LISTA CERRADA de tipos que PERSISTIMOS como notificacion.
    # Cualquier otro tipo retorna (None, None) y NO se persiste. Esos
    # eventos van solamente al historial (events table).
    #
    # SKIP por diseño:
    #   - cmd_cancel       -> solo historial
    #   - schedule_*       -> ABM de horarios va solo al historial
    #   - login            -> auditoria pero no notif
    # ----------------------------------------------------------------

    # --- Comandos manuales ---
    # Titles descriptivos del EFECTO (no del tipo). Asi en la bandeja se ve
    # "Puerta abierta · por Vos" en vez de "cmd_open · por Vos".
    if notif_type == "cmd_open":
        direction = extra.get("direction") if isinstance(extra, dict) else None
        if direction == "in":
            return "Puerta abierta hacia adentro", f"Por {actor_label}"
        if direction == "out":
            return "Puerta abierta hacia afuera", f"Por {actor_label}"
        return "Puerta abierta", f"Por {actor_label}"
    if notif_type in ("cmd_block", "override_block"):
        # cmd_block y override_block son indistinguibles para el user
        # (mismo efecto). Mostramos el mismo mensaje.
        return "Puerta bloqueada", f"Por {actor_label}"
    if notif_type in ("cmd_unblock", "override_unblock"):
        return "Puerta desbloqueada", f"Por {actor_label}"
    if notif_type == "cmd_call":
        return f"{actor_label} llamó a la mascota", "Activó la alerta sonora"

    # --- Schedules: activacion / desactivacion automatica por cron ---
    # (NO los created/updated/deleted del ABM — esos van solo al historial)
    if notif_type == "schedule_activated":
        nombre = extra.get("nombre", "") if isinstance(extra, dict) else ""
        # <b>...</b> lo renderiza el cliente con Html.fromHtml().
        return f"Horario <b>{nombre}</b> activado", \
               "El horario está corriendo ahora"
    if notif_type == "schedule_deactivated":
        nombre = extra.get("nombre", "") if isinstance(extra, dict) else ""
        return f"Horario <b>{nombre}</b> desactivado", \
               "El horario terminó"
    if notif_type == "schedule_block_end":
        return "Puerta bloqueada por fin de horario", \
               "Terminó el horario y la puerta se bloqueó automáticamente"
    if notif_type == "schedule_unblock_start":
        return "Puerta desbloqueada por inicio de horario", \
               "Empezó el horario y la puerta se desbloqueó automáticamente"

    # cmd_cancel, schedule_created, schedule_updated, schedule_deleted,
    # login y cualquier otro tipo NO se persiste como notif. Solo historial.
    return None, None


def _short_actor_label(actor_email):
    """Nombre amigable a partir del email. 'federico@gmail.com' -> 'federico'.
       Si es None o 'system', devuelve 'El sistema'."""
    if not actor_email or actor_email == "system":
        return "El sistema"
    if "@" in actor_email:
        return actor_email.split("@", 1)[0]
    return actor_email
