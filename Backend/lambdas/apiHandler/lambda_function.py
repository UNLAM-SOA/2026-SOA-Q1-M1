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
from datetime import datetime, timezone

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

USER_POOL_ID = os.environ["USER_POOL_ID"]
APP_CLIENT_ID = os.environ["APP_CLIENT_ID"]
EVENTS_TABLE = os.environ.get("EVENTS_TABLE", "pawgate_events")
SCHEDULES_TABLE = os.environ.get("SCHEDULES_TABLE", "pawgate_schedules")
DEVICE_STATE_TABLE = os.environ.get("DEVICE_STATE_TABLE", "pawgate_device_state")

events_table = ddb.Table(EVENTS_TABLE)
schedules_table = ddb.Table(SCHEDULES_TABLE)
device_state_table = ddb.Table(DEVICE_STATE_TABLE)

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

        path_params = event.get("pathParameters") or {}
        query_params = event.get("queryStringParameters") or {}
        device_id = path_params.get("id")
        schedule_id = path_params.get("schedule_id")
        cmd = path_params.get("cmd")

        # ===== Device =====
        if method == "GET" and path.endswith("/history"):
            return handle_history(device_id, query_params)

        if method == "POST" and "/cmd/" in path:
            return handle_cmd(device_id, cmd, body)

        # ===== Schedules CRUD =====
        if method == "GET" and path.endswith("/schedules"):
            return handle_list_schedules(device_id)
        if method == "POST" and path.endswith("/schedules"):
            return handle_create_schedule(device_id, body)
        if method == "PUT" and "/schedules/" in path:
            return handle_update_schedule(device_id, schedule_id, body)
        if method == "DELETE" and "/schedules/" in path:
            return handle_delete_schedule(device_id, schedule_id)

        # ===== Metrics =====
        if method == "GET" and path.endswith("/metrics/today"):
            return handle_metrics_today(device_id)

        # ===== Device state =====
        if method == "GET" and path.endswith("/state"):
            return handle_get_state(device_id)
        if method == "POST" and path.endswith("/state/override-unblock"):
            return handle_override_unblock(device_id)
        if method == "POST" and path.endswith("/state/override-block"):
            return handle_override_block(device_id)

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


# ============================================================
# DEVICE: history + cmd
# ============================================================

def handle_history(device_id, query_params):
    if not device_id:
        return _bad_request("device_id requerido")
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    from_ms = int(query_params.get("from", now_ms - 86400 * 1000))
    to_ms = int(query_params.get("to", now_ms))
    from_sk = f"{from_ms:013d}"
    to_sk = f"{to_ms:013d}#~"
    include_sensors = (query_params.get("include_sensors") or "").lower() == "true"
    cursor = query_params.get("cursor")

    query_kwargs = {
        "KeyConditionExpression": "device_id = :dev AND ts_event BETWEEN :from_sk AND :to_sk",
        "ExpressionAttributeValues": {":dev": device_id, ":from_sk": from_sk, ":to_sk": to_sk},
        "ScanIndexForward": False,
        "Limit": 50,  # tamano de pagina (post filter puede quedar menor)
    }
    if not include_sensors:
        query_kwargs["FilterExpression"] = "#t <> :sensor"
        query_kwargs["ExpressionAttributeNames"] = {"#t": "type"}
        query_kwargs["ExpressionAttributeValues"][":sensor"] = "sensor"

    # Pagination: cursor es el LastEvaluatedKey serializado base64+JSON
    if cursor:
        import base64
        try:
            last_key = json.loads(base64.b64decode(cursor).decode())
            query_kwargs["ExclusiveStartKey"] = last_key
        except Exception:
            return _bad_request("cursor invalido")

    try:
        result = events_table.query(**query_kwargs)
    except ClientError as e:
        logger.error("DDB query failed: %s", e)
        return _server_error("query failed")

    items = result.get("Items", [])
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

    # Si DDB indica que hay mas, devolvemos cursor para la proxima pagina.
    last_evaluated = result.get("LastEvaluatedKey")
    if last_evaluated:
        import base64
        response["next_cursor"] = base64.b64encode(
            json.dumps(last_evaluated, cls=DecimalEncoder).encode()).decode()

    return _ok(response)


def handle_cmd(device_id, cmd, body):
    if not device_id or not cmd:
        return _bad_request("device_id y cmd son requeridos")
    allowed_cmds = {"open", "block", "unblock", "call", "cancel"}
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


def handle_create_schedule(device_id, body):
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
    return _created(item)


def handle_update_schedule(device_id, schedule_id, body):
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
    return _ok(item)


def handle_delete_schedule(device_id, schedule_id):
    if not device_id or not schedule_id:
        return _bad_request("device_id y schedule_id son requeridos")
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


def handle_metrics_today(device_id):
    """GET /devices/{id}/metrics/today

    Cuenta SERVER-SIDE las aperturas (event_type=opened) del dia y devuelve
    info del ultimo evento door. Paginamos internamente con LastEvaluatedKey
    para no perder eventos si el dia tiene muchos sensors mezclados.

    El cliente solo recibe el numero final ya calculado, no tiene que iterar
    paginas ni filtrar localmente.
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
    pagination_key = None
    safety_pages = 0

    while True:
        kwargs = {
            "KeyConditionExpression":
                "device_id = :dev AND ts_event BETWEEN :from_sk AND :to_sk",
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
            if last_door_event is None:
                last_door_event = item
            if item.get("event_type") == "opened":
                openings_count += 1

        pagination_key = result.get("LastEvaluatedKey")
        if not pagination_key:
            break
        safety_pages += 1
        if safety_pages > 50:  # 50 pages * 200 items = 10k items, mas que suficiente
            logger.warning("metrics_today hit page limit, count may be partial")
            break

    response = {
        "device_id": device_id,
        "from_ms": from_ms,
        "to_ms": now_ms,
        "openings_today": openings_count,
    }
    if last_door_event:
        response["last_door_event_at"] = last_door_event.get("created_at")
        response["last_door_event_type"] = last_door_event.get("event_type")
        if "direction" in last_door_event:
            response["last_door_event_direction"] = last_door_event["direction"]

    return _ok(response)


def handle_override_unblock(device_id):
    """Manual override: el user desbloquea fuera de horario.
       Setea lock_state=MANUAL_UNBLOCKED y publica cmd/unblock al device.
       El cron lo respeta hasta que entre a un horario natural."""
    return _do_override(device_id, "MANUAL_UNBLOCKED", "unblock")


def handle_override_block(device_id):
    """Manual override: el user bloquea dentro de horario.
       Setea lock_state=MANUAL_BLOCKED y publica cmd/block al device.
       El cron lo respeta hasta que salga del horario natural."""
    return _do_override(device_id, "MANUAL_BLOCKED", "block")


def _do_override(device_id, target_state, cmd):
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
