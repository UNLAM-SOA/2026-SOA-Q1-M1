"""
Lambda: pawgate-schedule-executor
==================================

Cron de 30 minutos. Cada vez que dispara:
  1) Calcula el slot actual (hora:30 o hora:00) en zona horaria Argentina.
  2) Calcula el dia actual (L/M/X/J/V/S/D).
  3) Scan a pawgate_schedules de horarios activos.
  4) Para cada match (mismo dia + slot == hora_inicio o == hora_fin):
     - Resuelve qué cmd publicar segun el tipo de horario.
     - Idempotencia: ConditionalPut en pawgate_schedule_runs con clave
       <schedule_id>_<YYYY-MM-DD_HH-MM>_<action>. Si ya existe -> skip.
     - Publica via iot:Publish al topic pawgate/<device_id>/cmd/<cmd>.

Trigger:
    EventBridge cron(0,30 * * * ? *) — minuto 0 y 30 de cada hora UTC.
    Tiempo de ejecucion esperado: <2s por invocacion (depende de cuantos
    horarios haya en la tabla).

Tipos de horario soportados:
    "abrir_auto"     hora_inicio -> cmd/open
                     hora_fin    -> ignorado (la puerta se cierra sola)
    "modo_nocturno"  hora_inicio -> cmd/block
                     hora_fin    -> cmd/unblock
    "paseo"          hora_inicio -> cmd/open
                     hora_fin    -> ignorado

Restriccion UX:
    Los horarios se configuran SOLO en slots de 30 min (HH:00 o HH:30).
    Esto lo enforce la app Android en el TimePicker. Si llega un horario con
    minutos != 00 y != 30, simplemente no va a matchear (Lambda lo ignora).
"""

import json
import logging
import os
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo

import boto3
from botocore.exceptions import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

ddb = boto3.resource("dynamodb")
iot_data = boto3.client("iot-data")

SCHEDULES_TABLE = os.environ.get("SCHEDULES_TABLE", "pawgate_schedules")
RUNS_TABLE = os.environ.get("RUNS_TABLE", "pawgate_schedule_runs")

schedules_table = ddb.Table(SCHEDULES_TABLE)
runs_table = ddb.Table(RUNS_TABLE)

# Argentina no tiene DST asi que UTC-3 es fijo. Si en el futuro queremos soportar
# multiples zonas horarias, esto se hace por usuario (campo timezone en pawgate_users).
TZ_LOCAL = ZoneInfo("America/Argentina/Buenos_Aires")

# Mapeo de weekday() (0=Monday) a las letras que guardamos en el campo "dias".
DAY_MAP = ["L", "M", "X", "J", "V", "S", "D"]

# Que cmd publicar segun tipo + momento del horario.
TIPO_ACTIONS = {
    "abrir_auto":    {"inicio": "open",  "fin": None},
    "modo_nocturno": {"inicio": "block", "fin": "unblock"},
    "paseo":         {"inicio": "open",  "fin": None},
}


def lambda_handler(event, context):
    """Entry point. event es el evento de EventBridge, lo ignoramos."""
    now_local = datetime.now(TZ_LOCAL)
    current_day = DAY_MAP[now_local.weekday()]

    # Redondear el minuto al slot de 30: 0-29 -> 00, 30-59 -> 30.
    # Esto absorbe el jitter de Lambda (puede arrancar a XX:30:01 o XX:30:30).
    slot_minute = 0 if now_local.minute < 30 else 30
    slot_time = f"{now_local.hour:02d}:{slot_minute:02d}"
    slot_id = now_local.strftime(f"%Y-%m-%d_%H-{slot_minute:02d}")

    logger.info("Slot: %s · day=%s · slot_id=%s", slot_time, current_day, slot_id)

    # Scan de horarios activos. Para 100s de horarios esta bien; para 10K+
    # convendria un GSI por (activo, day_index) y query por dia.
    try:
        result = schedules_table.scan(
            FilterExpression="activo = :true",
            ExpressionAttributeValues={":true": True},
        )
    except ClientError as e:
        logger.error("Failed to scan schedules: %s", e)
        raise

    schedules = result.get("Items", [])
    logger.info("Schedules activos encontrados: %d", len(schedules))

    fired = 0
    skipped = 0

    for sched in schedules:
        device_id = sched.get("device_id")
        schedule_id = sched.get("schedule_id")
        tipo = sched.get("tipo", "abrir_auto")
        dias = sched.get("dias", [])
        hora_inicio = sched.get("hora_inicio")
        hora_fin = sched.get("hora_fin")

        # Filtro 1: día actual no esta en los dias del horario
        if current_day not in dias:
            continue

        # Filtro 2: matchear contra inicio y/o fin
        actions = TIPO_ACTIONS.get(tipo, {})

        for moment, slot in (("inicio", hora_inicio), ("fin", hora_fin)):
            cmd = actions.get(moment)
            if not cmd or not slot or slot != slot_time:
                continue

            # Idempotencia: clave unica por schedule + slot + moment
            run_key = f"{schedule_id}_{slot_id}_{moment}"

            try:
                _put_run_marker(run_key)
            except ClientError as e:
                if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                    logger.info("Already fired %s (skip)", run_key)
                    skipped += 1
                    continue
                raise

            # Publish al topic MQTT
            topic = f"pawgate/{device_id}/cmd/{cmd}"
            payload = {
                "source": "schedule",
                "schedule_id": schedule_id,
                "tipo": tipo,
                "moment": moment,
                "ts": int(datetime.now(timezone.utc).timestamp() * 1000),
            }
            iot_data.publish(
                topic=topic,
                qos=1,
                payload=json.dumps(payload),
            )
            logger.info("⬆ PUB %s (schedule_id=%s)", topic, schedule_id)
            fired += 1

    logger.info("Done. fired=%d skipped=%d total_active=%d", fired, skipped, len(schedules))
    return {
        "checked": len(schedules),
        "fired": fired,
        "skipped": skipped,
        "slot": slot_time,
        "day": current_day,
    }


def _put_run_marker(run_key: str):
    """ConditionalPut: solo escribe si la key no existe. Falla con
    ConditionalCheckFailedException si ya fue disparado en este slot."""
    ttl_epoch = int((datetime.now(timezone.utc) + timedelta(minutes=10)).timestamp())
    runs_table.put_item(
        Item={
            "run_key": run_key,
            "ttl_epoch": ttl_epoch,
        },
        ConditionExpression="attribute_not_exists(run_key)",
    )
