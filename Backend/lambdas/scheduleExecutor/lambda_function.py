"""
Lambda: pawgate-schedule-executor
==================================

Cron de 30 minutos. Cada vez que dispara, evalua la state machine del lock
del device contra los schedules activos.

Modelo:
    Un schedule = ventana en que la puerta queda DESBLOQUEADA.
    Fuera de cualquier schedule, la puerta debe estar bloqueada.

State machine (campo lock_state en pawgate_device_state):

    AUTO_BLOCKED       AUTO_UNBLOCKED       MANUAL_UNBLOCKED
        |                   |                     |
    in_horario          in_horario           in_horario
        ↓                   ↓                     ↓
    AUTO_UNBLOCKED      AUTO_UNBLOCKED       AUTO_UNBLOCKED
    (publish unblock)   (no-op)              (no-op, override consumido)

    AUTO_BLOCKED       AUTO_UNBLOCKED       MANUAL_UNBLOCKED
        |                   |                     |
    fuera de horario    fuera de horario     fuera de horario
        ↓                   ↓                     ↓
    AUTO_BLOCKED        AUTO_BLOCKED         MANUAL_UNBLOCKED
    (no-op)             (publish block)      (no-op, override sigue activo)

Idempotencia:
    Si el state ya es el correcto, no publicamos. Asi el cron puede ejecutarse
    multiples veces sin re-disparar cmds.

Trigger:
    EventBridge cron(0,30 * * * ? *)
"""

import json
import logging
import os
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import boto3
from botocore.exceptions import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

ddb = boto3.resource("dynamodb")
iot_data = boto3.client("iot-data")

SCHEDULES_TABLE = os.environ.get("SCHEDULES_TABLE", "pawgate_schedules")
DEVICE_STATE_TABLE = os.environ.get("DEVICE_STATE_TABLE", "pawgate_device_state")

schedules_table = ddb.Table(SCHEDULES_TABLE)
device_state_table = ddb.Table(DEVICE_STATE_TABLE)

TZ_LOCAL = ZoneInfo("America/Argentina/Buenos_Aires")
DAY_MAP = ["L", "M", "X", "J", "V", "S", "D"]


def lambda_handler(event, context):
    now_local = datetime.now(TZ_LOCAL)
    current_day = DAY_MAP[now_local.weekday()]
    current_min = now_local.hour * 60 + now_local.minute
    logger.info("Tick: day=%s time=%02d:%02d", current_day, now_local.hour, now_local.minute)

    # Agrupar schedules por device_id (scan completo)
    try:
        result = schedules_table.scan()
    except ClientError as e:
        logger.error("Scan schedules failed: %s", e)
        raise

    schedules_by_device = {}
    for s in result.get("Items", []):
        if not s.get("activo"):
            continue
        schedules_by_device.setdefault(s["device_id"], []).append(s)

    # Si no hay schedules en la tabla, evaluamos igual los devices que tengan state
    # registrado (asi siguen recibiendo block si corresponde).
    try:
        state_result = device_state_table.scan()
        for st in state_result.get("Items", []):
            schedules_by_device.setdefault(st["device_id"], [])
    except ClientError as e:
        logger.warning("Scan device_state failed (ignoring): %s", e)

    transitions = 0
    for device_id, schedules in schedules_by_device.items():
        if _evaluate_device(device_id, schedules, current_day, current_min):
            transitions += 1

    logger.info("Done. devices_evaluated=%d transitions=%d",
                len(schedules_by_device), transitions)
    return {"devices_evaluated": len(schedules_by_device), "transitions": transitions}


def _evaluate_device(device_id, schedules, current_day, current_min):
    """Evalua un device contra sus schedules. Devuelve True si hubo transicion."""
    in_horario = _is_in_any_schedule(schedules, current_day, current_min)
    current_state = _get_lock_state(device_id)

    next_state, action = _transition(current_state, in_horario)

    if next_state == current_state and action is None:
        return False

    _set_lock_state(device_id, next_state)
    if action:
        _publish_cmd(device_id, action)
    logger.info("Device %s: %s -> %s (in_horario=%s, action=%s)",
                device_id, current_state, next_state, in_horario, action)
    return True


def _transition(current_state, in_horario):
    """Devuelve (next_state, cmd_a_publicar_o_None) segun la state machine."""
    if in_horario:
        if current_state == "AUTO_BLOCKED":
            return "AUTO_UNBLOCKED", "unblock"
        if current_state == "AUTO_UNBLOCKED":
            return "AUTO_UNBLOCKED", None
        if current_state == "MANUAL_UNBLOCKED":
            # Override consumido al entrar a horario natural.
            return "AUTO_UNBLOCKED", None
    else:
        if current_state == "AUTO_BLOCKED":
            return "AUTO_BLOCKED", None
        if current_state == "AUTO_UNBLOCKED":
            return "AUTO_BLOCKED", "block"
        if current_state == "MANUAL_UNBLOCKED":
            return "MANUAL_UNBLOCKED", None
    # Defensivo: state desconocido -> reset a AUTO_BLOCKED
    return "AUTO_BLOCKED", "block"


def _is_in_any_schedule(schedules, current_day, current_min):
    for s in schedules:
        if current_day not in (s.get("dias") or []):
            continue
        try:
            h1, m1 = map(int, s["hora_inicio"].split(":"))
            h2, m2 = map(int, s["hora_fin"].split(":"))
        except (ValueError, KeyError):
            continue
        inicio_min = h1 * 60 + m1
        fin_min = h2 * 60 + m2
        if fin_min > inicio_min:
            if inicio_min <= current_min < fin_min:
                return True
        else:
            # Cruza medianoche
            if current_min >= inicio_min or current_min < fin_min:
                return True
    return False


# ============================================================
# DEVICE STATE HELPERS
# ============================================================

def _get_lock_state(device_id):
    try:
        resp = device_state_table.get_item(Key={"device_id": device_id})
        item = resp.get("Item")
        if item:
            return item.get("lock_state", "AUTO_BLOCKED")
    except ClientError as e:
        logger.error("get_lock_state failed: %s", e)
    return "AUTO_BLOCKED"


def _set_lock_state(device_id, lock_state):
    try:
        device_state_table.put_item(Item={
            "device_id": device_id,
            "lock_state": lock_state,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        })
    except ClientError as e:
        logger.error("set_lock_state failed: %s", e)


def _publish_cmd(device_id, cmd):
    topic = f"pawgate/{device_id}/cmd/{cmd}"
    payload = {
        "source": "schedule_executor",
        "ts": int(datetime.now(timezone.utc).timestamp() * 1000),
    }
    try:
        iot_data.publish(topic=topic, qos=1, payload=json.dumps(payload))
        logger.info("⬆ PUB %s", topic)
    except ClientError as e:
        logger.error("IoT publish failed: %s", e)
