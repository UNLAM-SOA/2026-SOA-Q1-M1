#!/usr/bin/env bash
#
# Actualiza la IoT Rule pawgate_events_rule para que su SQL capture TODOS los
# subtopics events/* (door, sensor, telemetry, ota, lo-que-venga).
#
# Por que: el archivo Backend/iot-rules/pawgate-events-rule.json tiene
#   SELECT *, topic() as topic, timestamp() as server_ts FROM 'pawgate/+/events/+'
# pero la rule deployada en AWS tenia un SQL restrictivo (ej. solo 'events/door'
# o 'events/telemetry') por lo que events/ota nunca invocaba eventIngest.
#
# Idempotente: se puede correr varias veces sin romper nada.

set -e

REGION=us-east-1
ACCOUNT_ID=075138626693
RULE_NAME=pawgate_events_rule           # nombre conocido en el repo
LAMBDA_ARN="arn:aws:lambda:$REGION:$ACCOUNT_ID:function:pawgate-event-ingest"

echo "═══════════════════════════════════════════════════════════"
echo "  Actualizando IoT Rule $RULE_NAME"
echo "═══════════════════════════════════════════════════════════"

# Si el nombre por default no existe, listar y avisar
if ! aws iot get-topic-rule --rule-name "$RULE_NAME" --region "$REGION" >/dev/null 2>&1; then
  echo ""
  echo "⚠ Rule '$RULE_NAME' no existe en AWS. Listando rules disponibles:"
  aws iot list-topic-rules --region "$REGION" --output table
  echo ""
  echo "Editá este script y cambiá RULE_NAME por el nombre correcto."
  exit 1
fi

PAYLOAD=$(cat <<EOF
{
  "sql": "SELECT *, topic() as topic, timestamp() as server_ts FROM 'pawgate/+/events/+'",
  "ruleDisabled": false,
  "awsIotSqlVersion": "2016-03-23",
  "description": "Captura eventos de los devices PawGate (door, sensor, telemetry, ota) y los persiste en DynamoDB via Lambda eventIngest.",
  "actions": [
    { "lambda": { "functionArn": "$LAMBDA_ARN" } }
  ]
}
EOF
)

echo ""
echo "▶ Aplicando nuevo SQL: SELECT * FROM 'pawgate/+/events/+'"
aws iot replace-topic-rule \
  --rule-name "$RULE_NAME" \
  --topic-rule-payload "$PAYLOAD" \
  --region "$REGION"

# IoT necesita permiso explicito para invocar la lambda. Lo agregamos siempre
# por si la lambda fue recreada / la permission borrada.
STATEMENT_ID="pawgate-iot-invoke-${RULE_NAME}"
aws lambda add-permission \
  --function-name pawgate-event-ingest \
  --statement-id "$STATEMENT_ID" \
  --action "lambda:InvokeFunction" \
  --principal "iot.amazonaws.com" \
  --source-arn "arn:aws:iot:$REGION:$ACCOUNT_ID:rule/$RULE_NAME" \
  --region "$REGION" 2>/dev/null \
  && echo "✔ Lambda permission agregada" \
  || echo "✔ Lambda permission ya existia (statement_id=$STATEMENT_ID)"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✔ Rule actualizada. Probala con:"
echo "    aws iot-data publish --topic 'pawgate/pawgate-001/events/ota' \\"
echo "      --region $REGION --payload '{\"type\":\"ota_test\"}' \\"
echo "      --cli-binary-format raw-in-base64-out"
echo "  Y verifica en CloudWatch de pawgate-event-ingest"
echo "═══════════════════════════════════════════════════════════"
