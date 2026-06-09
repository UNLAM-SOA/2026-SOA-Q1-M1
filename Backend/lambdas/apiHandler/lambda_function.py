"""
Lambda: pawgate-api-handler
============================

Backend REST API de PawGate. Sirve los endpoints que consume la app Android.

Trigger:
    API Gateway (REST API, proxy integration)

Endpoints:

  PUBLICOS (no requieren JWT — el Cognito Authorizer del API GW NO los protege):

    POST /auth/signup     body: {"email":..., "password":..., "name":...}
                          Cognito SignUp + email verification

    POST /auth/confirm    body: {"email":..., "code":...}
                          Confirma el email con el codigo recibido

    POST /auth/login      body: {"email":..., "password":...}
                          → {"idToken":..., "accessToken":..., "refreshToken":...}

  AUTENTICADOS (requieren header Authorization: Bearer <accessToken>):

    GET  /devices/{id}/history?from=<ts>&to=<ts>
                          Query a pawgate_events del device, rango por timestamp

    POST /devices/{id}/cmd/{cmd}
                          Publica al topic pawgate/{id}/cmd/{cmd} (relay a IoT Core)
                          cmd ∈ {open, block, unblock, call, cancel}

Estructura del response (siempre):
    {"statusCode": N, "headers": {...}, "body": "..."}
"""

import decimal
import json
import logging
import os
from datetime import datetime, timezone

import boto3
from botocore.exceptions import ClientError


class DecimalEncoder(json.JSONEncoder):
    """
    DynamoDB devuelve los numeros como decimal.Decimal (precision arbitraria).
    json.dumps stdlib no sabe serializarlos -> TypeError.
    Este encoder los convierte: si son enteros -> int, sino -> float.
    """
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            if obj % 1 == 0:
                return int(obj)
            return float(obj)
        return super().default(obj)


def json_dumps(obj):
    """Wrapper de json.dumps con DecimalEncoder. Usar siempre que el body pueda
    contener data leida desde DDB."""
    return json.dumps(obj, cls=DecimalEncoder)

# Logger CloudWatch
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Clientes AWS (reutilizados entre invocaciones warm)
cognito = boto3.client("cognito-idp")
ddb = boto3.resource("dynamodb")
iot_data = boto3.client("iot-data")

# Configuracion via env vars
USER_POOL_ID = os.environ["USER_POOL_ID"]
APP_CLIENT_ID = os.environ["APP_CLIENT_ID"]
EVENTS_TABLE = os.environ.get("EVENTS_TABLE", "pawgate_events")

events_table = ddb.Table(EVENTS_TABLE)


# ============================================================
# RESPONSE HELPERS
# ============================================================

def _response(status_code: int, body: dict, headers: dict = None) -> dict:
    """Helper para construir el response que API Gateway espera."""
    default_headers = {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",  # CORS (para web/dev tools)
        "Access-Control-Allow-Headers": "Content-Type,Authorization",
        "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE,OPTIONS",
    }
    if headers:
        default_headers.update(headers)
    return {
        "statusCode": status_code,
        "headers": default_headers,
        "body": json_dumps(body),  # usa DecimalEncoder para serializar Decimals de DDB
    }


def _ok(body: dict) -> dict:
    return _response(200, body)


def _bad_request(message: str) -> dict:
    return _response(400, {"error": message})


def _unauthorized(message: str) -> dict:
    return _response(401, {"error": message})


def _server_error(message: str) -> dict:
    return _response(500, {"error": message})


# ============================================================
# MAIN HANDLER (router)
# ============================================================

def lambda_handler(event, context):
    """Router. Parsea el path + method del event de API GW y delega al handler especifico."""
    logger.info("Event: %s", json.dumps(event))

    method = event.get("httpMethod", "")
    path = event.get("path", "")

    # CORS preflight - responder OK a OPTIONS sin tocar la logica
    if method == "OPTIONS":
        return _response(204, {})

    try:
        # Parsear body si viene JSON
        body_raw = event.get("body") or "{}"
        try:
            body = json.loads(body_raw) if body_raw else {}
        except json.JSONDecodeError:
            return _bad_request("invalid JSON body")

        # ===== Auth endpoints (publicos) =====
        if method == "POST" and path == "/auth/signup":
            return handle_signup(body)
        if method == "POST" and path == "/auth/confirm":
            return handle_confirm(body)
        if method == "POST" and path == "/auth/login":
            return handle_login(body)

        # ===== Device endpoints (requieren JWT) =====
        # API GW Cognito Authorizer ya valido el token y puso los claims
        # en requestContext.authorizer.claims
        path_params = event.get("pathParameters") or {}
        query_params = event.get("queryStringParameters") or {}

        if method == "GET" and path.startswith("/devices/") and path.endswith("/history"):
            return handle_history(path_params.get("id"), query_params)

        if method == "POST" and path.startswith("/devices/") and "/cmd/" in path:
            return handle_cmd(path_params.get("id"), path_params.get("cmd"), body)

        return _response(404, {"error": f"route not found: {method} {path}"})

    except Exception as e:
        logger.exception("Unhandled error")
        return _server_error(str(e))


# ============================================================
# AUTH HANDLERS
# ============================================================

def handle_signup(body: dict) -> dict:
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
        return _ok({
            "message": "user created, email verification sent",
            "email": email,
        })
    except ClientError as e:
        code = e.response["Error"]["Code"]
        if code == "UsernameExistsException":
            return _response(409, {"error": "email already registered"})
        if code == "InvalidPasswordException":
            return _bad_request("password must be 8+ chars with upper, lower, number")
        if code == "InvalidParameterException":
            return _bad_request(e.response["Error"]["Message"])
        raise


def handle_confirm(body: dict) -> dict:
    email = body.get("email")
    code = body.get("code")
    if not email or not code:
        return _bad_request("email y code son requeridos")

    try:
        cognito.confirm_sign_up(
            ClientId=APP_CLIENT_ID,
            Username=email,
            ConfirmationCode=code,
        )
        return _ok({"message": "email confirmed, you can now login"})
    except ClientError as e:
        code_err = e.response["Error"]["Code"]
        if code_err == "CodeMismatchException":
            return _unauthorized("invalid confirmation code")
        if code_err == "ExpiredCodeException":
            return _unauthorized("confirmation code expired")
        raise


def handle_login(body: dict) -> dict:
    email = body.get("email")
    password = body.get("password")
    if not email or not password:
        return _bad_request("email y password son requeridos")

    try:
        resp = cognito.initiate_auth(
            ClientId=APP_CLIENT_ID,
            AuthFlow="USER_PASSWORD_AUTH",
            AuthParameters={
                "USERNAME": email,
                "PASSWORD": password,
            },
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
# DEVICE HANDLERS
# ============================================================

def handle_history(device_id: str, query_params: dict) -> dict:
    """GET /devices/{id}/history?from=<ts>&to=<ts>

    from y to en epoch ms (strings). Default: ultimas 24h.
    """
    if not device_id:
        return _bad_request("device_id requerido")

    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    from_ms = int(query_params.get("from", now_ms - 86400 * 1000))
    to_ms = int(query_params.get("to", now_ms))

    # Sort keys son strings con timestamp padded a 13 digitos
    from_sk = f"{from_ms:013d}"
    to_sk = f"{to_ms:013d}#~"  # '~' es high ASCII para incluir todos los SK con ese ts

    try:
        result = events_table.query(
            KeyConditionExpression=(
                "device_id = :dev AND ts_event BETWEEN :from_sk AND :to_sk"
            ),
            ExpressionAttributeValues={
                ":dev": device_id,
                ":from_sk": from_sk,
                ":to_sk": to_sk,
            },
            ScanIndexForward=False,  # mas recientes primero
            Limit=100,
        )
    except ClientError as e:
        logger.error("DDB query failed: %s", e)
        return _server_error("query failed")

    items = result.get("Items", [])
    # Sanitizar: el campo "payload" viene como JSON string, lo parseamos
    for it in items:
        if "payload" in it and isinstance(it["payload"], str):
            try:
                it["payload"] = json.loads(it["payload"])
            except json.JSONDecodeError:
                pass

    return _ok({
        "device_id": device_id,
        "from": from_ms,
        "to": to_ms,
        "count": len(items),
        "events": items,
    })


def handle_cmd(device_id: str, cmd: str, body: dict) -> dict:
    """POST /devices/{id}/cmd/{cmd}

    Relay HTTPS → MQTT. Publica al topic pawgate/{id}/cmd/{cmd}.
    """
    if not device_id or not cmd:
        return _bad_request("device_id y cmd son requeridos")

    allowed_cmds = {"open", "block", "unblock", "call", "cancel"}
    if cmd not in allowed_cmds:
        return _bad_request(f"cmd '{cmd}' no permitido. Allowed: {allowed_cmds}")

    topic = f"pawgate/{device_id}/cmd/{cmd}"
    payload = {
        "source": "api",
        "ts": int(datetime.now(timezone.utc).timestamp() * 1000),
        **(body if isinstance(body, dict) else {}),
    }

    try:
        iot_data.publish(
            topic=topic,
            qos=1,
            payload=json.dumps(payload),
        )
        logger.info("Published to %s: %s", topic, payload)
        return _ok({
            "queued": True,
            "topic": topic,
            "payload": payload,
        })
    except ClientError as e:
        logger.error("IoT publish failed: %s", e)
        return _server_error("publish failed")
