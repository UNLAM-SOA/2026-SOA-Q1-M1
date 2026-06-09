#!/usr/bin/env bash
# Setup de la tabla DynamoDB pawgate_notifications + actualizar IAM policies
# de las lambdas pawgate-api-handler y pawgate-event-ingest para que puedan
# leer/escribir en la nueva tabla.
#
# Idempotente: si ya existe la tabla / policy, salta.
#
# Usage: bash Backend/setup-notifications.sh

set -e

REGION=us-east-1
ACCOUNT_ID=075138626693
TABLE=pawgate_notifications

API_LAMBDA=pawgate-api-handler
INGEST_LAMBDA=pawgate-event-ingest

echo "═══════════════════════════════════════════════════════════"
echo "  Setup tabla $TABLE + IAM policies"
echo "═══════════════════════════════════════════════════════════"

# ============================================================
# 1) Crear tabla DDB
# ============================================================
echo ""
echo "▶ 1/4 — Verificando tabla DDB $TABLE..."

if aws dynamodb describe-table --region $REGION --table-name $TABLE >/dev/null 2>&1; then
  echo "   ✔ Tabla $TABLE ya existe"
else
  echo "   Creando tabla $TABLE..."
  aws dynamodb create-table \
    --region $REGION \
    --table-name $TABLE \
    --attribute-definitions \
      AttributeName=user_email,AttributeType=S \
      AttributeName=notif_id,AttributeType=S \
    --key-schema \
      AttributeName=user_email,KeyType=HASH \
      AttributeName=notif_id,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST >/dev/null

  echo "   Esperando que la tabla este ACTIVE..."
  aws dynamodb wait table-exists --region $REGION --table-name $TABLE
  echo "   ✔ Tabla $TABLE creada"
fi

# ============================================================
# 2) Habilitar TTL automatico (ttl_epoch -> borra a los 30 dias)
# ============================================================
echo ""
echo "▶ 2/4 — Configurando TTL en columna ttl_epoch..."
TTL_STATUS=$(aws dynamodb describe-time-to-live \
  --region $REGION \
  --table-name $TABLE \
  --query 'TimeToLiveDescription.TimeToLiveStatus' \
  --output text 2>/dev/null || echo "DISABLED")

if [ "$TTL_STATUS" = "ENABLED" ] || [ "$TTL_STATUS" = "ENABLING" ]; then
  echo "   ✔ TTL ya esta $TTL_STATUS"
else
  aws dynamodb update-time-to-live \
    --region $REGION \
    --table-name $TABLE \
    --time-to-live-specification "Enabled=true,AttributeName=ttl_epoch" >/dev/null
  echo "   ✔ TTL habilitado (atributo: ttl_epoch)"
fi

# ============================================================
# 3) IAM: agregar permisos a las lambdas
# ============================================================
TABLE_ARN="arn:aws:dynamodb:$REGION:$ACCOUNT_ID:table/$TABLE"

POLICY_DOC=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:DeleteItem",
        "dynamodb:BatchWriteItem"
      ],
      "Resource": "$TABLE_ARN"
    }
  ]
}
EOF
)

attach_inline_policy() {
  local lambda_name="$1"
  local policy_name="$2"

  echo "   Lambda: $lambda_name"
  ROLE_ARN=$(aws lambda get-function-configuration \
    --function-name "$lambda_name" \
    --region $REGION \
    --query 'Role' --output text 2>/dev/null || echo "")
  if [ -z "$ROLE_ARN" ]; then
    echo "     ⚠ Lambda no encontrada, saltando"
    return
  fi
  ROLE_NAME=$(basename "$ROLE_ARN")

  aws iam put-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-name "$policy_name" \
    --policy-document "$POLICY_DOC" >/dev/null
  echo "     ✔ Policy $policy_name attached a $ROLE_NAME"
}

echo ""
echo "▶ 3/4 — Adjuntando policies inline a las lambdas..."
attach_inline_policy "$API_LAMBDA"   "pawgate-notifications-rw"
attach_inline_policy "$INGEST_LAMBDA" "pawgate-notifications-rw"

# ============================================================
# 4) Setear env var NOTIFICATIONS_TABLE en las lambdas
# ============================================================
echo ""
echo "▶ 4/4 — Actualizando env vars de las lambdas..."

update_env() {
  local lambda_name="$1"
  echo "   Lambda: $lambda_name"

  CURRENT_ENV=$(aws lambda get-function-configuration \
    --function-name "$lambda_name" \
    --region $REGION \
    --query 'Environment.Variables' --output json 2>/dev/null || echo "{}")

  if [ "$CURRENT_ENV" = "null" ] || [ -z "$CURRENT_ENV" ]; then
    CURRENT_ENV="{}"
  fi

  NEW_ENV=$(echo "$CURRENT_ENV" | python3 -c "
import json, sys
e = json.load(sys.stdin)
e['NOTIFICATIONS_TABLE'] = '$TABLE'
print(json.dumps({'Variables': e}))
")

  aws lambda update-function-configuration \
    --function-name "$lambda_name" \
    --region $REGION \
    --environment "$NEW_ENV" >/dev/null
  echo "     ✔ NOTIFICATIONS_TABLE=$TABLE seteado"
}

update_env "$API_LAMBDA"
update_env "$INGEST_LAMBDA"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✔ Setup notifications completo"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Proximos pasos:"
echo "  1) Redeploy lambdas:"
echo "     cd Backend/lambdas/apiHandler   && zip -r ../apiHandler.zip lambda_function.py"
echo "     cd Backend/lambdas/eventIngest  && zip -r ../eventIngest.zip lambda_function.py"
echo "     aws lambda update-function-code --function-name $API_LAMBDA   --zip-file fileb://Backend/lambdas/apiHandler.zip   --region $REGION"
echo "     aws lambda update-function-code --function-name $INGEST_LAMBDA --zip-file fileb://Backend/lambdas/eventIngest.zip  --region $REGION"
echo "  2) Correr setup-horarios-api.sh para crear las rutas en API Gateway:"
echo "     bash Backend/setup-horarios-api.sh"
