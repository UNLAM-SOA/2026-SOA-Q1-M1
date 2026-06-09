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

TTL_DAYS = int(os.environ.get("TTL_DAYS", "90"))


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
    event_kind = parts[3]  # "door" | "sensor" | etc

    # 2) Construir sort key. Timestamp del DEVICE (no del server) para que el
    #    orden refleje cuando paso el evento fisicamente, no cuando AWS lo recibio.
    #    Padded a 13 digitos para que el ordenamiento lexicografico = cronologico.
    ts_device = int(event.get("ts", event.get("server_ts", 0)))
    event_type = event.get("type", "unknown")  # "opened" | "closed" | etc
    sort_key = f"{ts_device:013d}#{event_kind}#{event_type}"

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

    try:
        events_table.put_item(Item=item)
        logger.info("Stored: device_id=%s ts_event=%s", device_id, sort_key)
    except Exception as e:
        # Re-raise para que IoT Rule haga retry (3 intentos automaticos).
        logger.error("Failed to put_item: %s", e)
        raise

    return {"statusCode": 200, "device_id": device_id, "ts_event": sort_key}
