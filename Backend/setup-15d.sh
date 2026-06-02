#!/usr/bin/env bash
# Setup completo de Fase 15.D: tabla schedule_runs + Lambda scheduleExecutor
# + EventBridge cron cada 30 min.
#
# Usage: bash Backend/setup-15d.sh

set -e

ACCOUNT_ID=075138626693
REGION=us-east-1

echo "═══════════════════════════════════════════════════════════"
echo "  Fase 15.D — EventBridge + Lambda scheduleExecutor"
echo "═══════════════════════════════════════════════════════════"

# ============================================================
# 1) Tabla pawgate_schedule_runs (con TTL para auto-borrar markers viejos)
# ============================================================
echo ""
echo "▶ 1/5 — Creando tabla pawgate_schedule_runs..."

aws dynamodb create-table \
  --table-name pawgate_schedule_runs \
  --attribute-definitions AttributeName=run_key,AttributeType=S \
  --key-schema AttributeName=run_key,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION \
  >/dev/null 2>&1 || echo "   (tabla ya existia)"

# Esperar a que la tabla este ACTIVE antes de habilitar TTL
sleep 10

aws dynamodb update-time-to-live \
  --table-name pawgate_schedule_runs \
  --time-to-live-specification "Enabled=true, AttributeName=ttl_epoch" \
  --region $REGION \
  >/dev/null 2>&1 || echo "   (TTL ya estaba habilitado)"

echo "   ✓ pawgate_schedule_runs lista"

# ============================================================
# 2) IAM role del Lambda scheduleExecutor
# ============================================================
echo ""
echo "▶ 2/5 — Creando IAM role..."

aws iam create-role \
  --role-name pawgate-schedule-executor-role \
  --assume-role-policy-document file://Backend/iam-roles/lambda-event-ingest-trust.json \
  >/dev/null 2>&1 || echo "   (role ya existia)"

aws iam put-role-policy \
  --role-name pawgate-schedule-executor-role \
  --policy-name pawgate-schedule-executor-policy \
  --policy-document file://Backend/iam-roles/lambda-schedule-executor-policy.json

sleep 10  # propagacion IAM

# ============================================================
# 3) Lambda scheduleExecutor
# ============================================================
echo ""
echo "▶ 3/5 — Empaquetando y creando Lambda..."

cd Backend/lambdas/scheduleExecutor
zip -q scheduleExecutor.zip lambda_function.py
cd ../../..

aws lambda create-function \
  --function-name pawgate-schedule-executor \
  --runtime python3.12 \
  --role arn:aws:iam::${ACCOUNT_ID}:role/pawgate-schedule-executor-role \
  --handler lambda_function.lambda_handler \
  --timeout 30 \
  --memory-size 256 \
  --environment "Variables={SCHEDULES_TABLE=pawgate_schedules,RUNS_TABLE=pawgate_schedule_runs}" \
  --zip-file fileb://Backend/lambdas/scheduleExecutor/scheduleExecutor.zip \
  --region $REGION \
  >/dev/null 2>&1 || {
    echo "   (Lambda ya existia, actualizando codigo...)"
    aws lambda update-function-code \
      --function-name pawgate-schedule-executor \
      --zip-file fileb://Backend/lambdas/scheduleExecutor/scheduleExecutor.zip \
      --region $REGION >/dev/null
  }

echo "   ✓ Lambda creada"

# ============================================================
# 4) EventBridge rule (cron cada 30 min)
# ============================================================
echo ""
echo "▶ 4/5 — Creando EventBridge rule (cron 0,30 * * * ? *)..."

# Crear rule
aws events put-rule \
  --name pawgate-schedule-cron \
  --schedule-expression "cron(0,30 * * * ? *)" \
  --description "Dispara cada 30 min para ejecutar horarios de PawGate" \
  --region $REGION \
  >/dev/null

# Adjuntar la Lambda como target de la rule
aws events put-targets \
  --rule pawgate-schedule-cron \
  --targets "Id=1,Arn=arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:pawgate-schedule-executor" \
  --region $REGION \
  >/dev/null

# Permitir que EventBridge invoque la Lambda
aws lambda add-permission \
  --function-name pawgate-schedule-executor \
  --statement-id allow-eventbridge \
  --action lambda:InvokeFunction \
  --principal events.amazonaws.com \
  --source-arn "arn:aws:events:${REGION}:${ACCOUNT_ID}:rule/pawgate-schedule-cron" \
  --region $REGION \
  >/dev/null 2>&1 || echo "   (permission ya existia)"

echo "   ✓ EventBridge rule + target listos"

# ============================================================
# 5) Resumen + tests sugeridos
# ============================================================
echo ""
echo "▶ 5/5 — Done."
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✅ Fase 15.D deployment completo"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "  Lambda:          pawgate-schedule-executor"
echo "  EventBridge:     pawgate-schedule-cron"
echo "  Schedule:        cron(0,30 * * * ? *)  (cada 30 min)"
echo "  TZ:              America/Argentina/Buenos_Aires"
echo ""
echo "  Test smoke (sin esperar al cron):"
echo "    aws lambda invoke --function-name pawgate-schedule-executor /tmp/out.json"
echo "    cat /tmp/out.json"
echo ""
echo "  Insertar un horario de prueba (que va a disparar al PROXIMO slot de :00 o :30):"
echo ""
cat <<'EOF'
    NEXT_SLOT=$(python3 -c "
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
now = datetime.now(ZoneInfo('America/Argentina/Buenos_Aires'))
slot_min = 30 if now.minute < 30 else 0
slot_hour = now.hour + (1 if now.minute >= 30 else 0)
print(f'{slot_hour % 24:02d}:{slot_min:02d}')
")
    DAY_LETTER=$(python3 -c "
from datetime import datetime
from zoneinfo import ZoneInfo
print(['L','M','X','J','V','S','D'][datetime.now(ZoneInfo('America/Argentina/Buenos_Aires')).weekday()])
")

    echo "Proximo slot: $NEXT_SLOT del dia $DAY_LETTER"

    aws dynamodb put-item \
      --table-name pawgate_schedules \
      --item "{
        \"device_id\":   {\"S\": \"pawgate-001\"},
        \"schedule_id\": {\"S\": \"test-001\"},
        \"hora_inicio\": {\"S\": \"$NEXT_SLOT\"},
        \"hora_fin\":    {\"S\": \"23:30\"},
        \"dias\":        {\"L\": [{\"S\":\"$DAY_LETTER\"}]},
        \"tipo\":        {\"S\": \"abrir_auto\"},
        \"activo\":      {\"BOOL\": true},
        \"created_at\":  {\"S\": \"2026-06-01T00:00:00Z\"}
      }"

    # Despues del proximo XX:00 o XX:30, ver los logs:
    aws logs tail /aws/lambda/pawgate-schedule-executor --since 5m --follow
EOF
echo ""
