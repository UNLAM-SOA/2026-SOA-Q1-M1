"""
Lambda: pawgate-schedule-executor
==================================

Cron de 30 minutos. Cada vez que dispara, evalua la state machine del lock
del device contra los schedules activos.

Modelo:
    Un schedule = ventana en que la puerta queda DESBLOQUEADA.
    Fuera de cualquier schedule, la puerta debe estar bloqueada.

Modelo de override:
    El override MANUAL_BLOCKED / MANUAL_UNBLOCKED se libera cuando hay una
    TRANSICION de horarios activos (no solo cuando cambia el binario
    in_horario). Para detectar esto, persistimos `horario_marker` (string
    con los IDs de horarios actualmente activos sorted+joined). Si el marker
    cambia entre ticks, hay transicion -> override consumido.

    Ej: si tenes 2 horarios contiguos 16:30-17:00 y 17:00-18:30:
      16:59 -> marker = "{A}"
      17:01 -> marker = "{B}"
      Aunque in_horario sigue True, hay transicion -> override liberado.

State machine (despues de eventual liberacion de override):

  in_horario=True:
    AUTO_BLOCKED   -> AUTO_UNBLOCKED  (publish unblock)
    AUTO_UNBLOCKED -> AUTO_UNBLOCKED  (no-op)

  in_horario=False:
    AUTO_BLOCKED   -> AUTO_BLOCKED    (no-op)
    AUTO_UNBLOCKED -> AUTO_BLOCKED    (publish block)

Si NO hubo transicion de horarios, los overrides se mantienen:
  MANUAL_BLOCKED   -> MANUAL_BLOCKED
  MANUAL_UNBLOCKED -> MANUAL_UNBLOCKED

Idempotencia: si el state ya es el correcto, no publicamos.

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
    active_ids = _active_schedule_ids(schedules, current_day, current_min)
    in_horario = bool(active_ids)
    new_marker = _marker(active_ids)

    state_item = _get_state_item(device_id)
    current_state = state_item.get("lock_state", "AUTO_BLOCKED")
    last_marker = state_item.get("horario_marker", "")

    # Liberar override si hubo transicion de horarios activos.
    if current_state in ("MANUAL_BLOCKED", "MANUAL_UNBLOCKED") and last_marker != new_marker:
        logger.info("Device %s: override liberado (marker %s -> %s)",
                    device_id, last_marker, new_marker)
        current_state = "AUTO_BLOCKED" if current_state == "MANUAL_BLOCKED" else "AUTO_UNBLOCKED"

    next_state, action = _transition(current_state, in_horario)

    # Si nada cambio (state ni marker), no escribimos.
    if (next_state == state_item.get("lock_state") and action is None
            and new_marker == last_marker):
        return False

    _set_state(device_id, next_state, new_marker)
    if action:
        _publish_cmd(device_id, action)
    logger.info("Device %s: %s -> %s (in_horario=%s, marker=%s, action=%s)",
                device_id, state_item.get("lock_state"), next_state,
                in_horario, new_marker, action)
    return True


def _transition(current_state, in_horario):
    """Despues de la posible liberacion del override, aplica la state machine
       basica AUTO_BLOCKED <-> AUTO_UNBLOCKED. Los MANUAL_* que llegan aca
       significa que NO hubo transicion de horarios y el override sigue."""
    if in_horario:
        if current_state == "AUTO_BLOCKED":
            return "AUTO_UNBLOCKED", "unblock"
        if current_state == "AUTO_UNBLOCKED":
            return "AUTO_UNBLOCKED", None
        if current_state == "MANUAL_UNBLOCKED":
            return "MANUAL_UNBLOCKED", None
        if current_state == "MANUAL_BLOCKED":
            return "MANUAL_BLOCKED", None
    else:
        if current_state == "AUTO_BLOCKED":
            return "AUTO_BLOCKED", None
        if current_state == "AUTO_UNBLOCKED":
            return "AUTO_BLOCKED", "block"
        if current_state == "MANUAL_UNBLOCKED":
            return "MANUAL_UNBLOCKED", None
        if current_state == "MANUAL_BLOCKED":
            return "MANUAL_BLOCKED", None
    return "AUTO_BLOCKED", "block"


def _active_schedule_ids(schedules, current_day, current_min):
    """Devuelve la lista (sorted) de schedule_id que estan activos AHORA."""
    active = []
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
        is_active = (
            (fin_min > inicio_min and inicio_min <= current_min < fin_min)
            or (fin_min <= inicio_min and (current_min >= inicio_min or current_min < fin_min))
        )
        if is_active:
            active.append(s.get("schedule_id", ""))
    return sorted(active)


def _marker(active_ids):
    """String para comparar entre ticks. Vacio si no hay activos."""
    return "|".join(active_ids)


# ============================================================
# DEVICE STATE HELPERS
# ============================================================

def _get_state_item(device_id):
    """Devuelve el item completo (lock_state + horario_marker + updated_at).
       Si no existe, defaults.

       ConsistentRead=True evita race conditions con escrituras recientes del
       apiHandler. Sin esto, un override que recien se persistio podria
       leerse stale y el cron lo libera por verlo con marker vacio."""
    try:
        resp = device_state_table.get_item(
            Key={"device_id": device_id},
            ConsistentRead=True,
        )
        item = resp.get("Item")
        if item:
            return item
    except ClientError as e:
        logger.error("get_state_item failed: %s", e)
    return {"lock_state": "AUTO_BLOCKED", "horario_marker": ""}


def _set_state(device_id, lock_state, horario_marker):
    try:
        device_state_table.put_item(Item={
            "device_id": device_id,
            "lock_state": lock_state,
            "horario_marker": horario_marker,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        })
    except ClientError as e:
        logger.error("set_state failed: %s", e)


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
