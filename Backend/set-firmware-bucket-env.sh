#!/usr/bin/env bash
#
# Setea (o re-setea) la env var FIRMWARE_BUCKET en la lambda apiHandler
# PRESERVANDO todas las demas env vars que ya tenia.
#
# CONTEXTO: el comando `aws lambda update-function-configuration --environment`
# REEMPLAZA el dict completo de Variables, no mergea. Si lo usas con solo una
# variable, te volas USER_POOL_ID / APP_CLIENT_ID / etc y la lambda revienta
# en module-time con KeyError.
#
# Este script:
#   1) Lee TODAS las env vars actuales con get-function-configuration.
#   2) Agrega o reemplaza FIRMWARE_BUCKET en ese dict.
#   3) Llama update-function-configuration con el dict completo.
#
# Idempotente: se puede correr varias veces sin romper nada.
#
# Usage: bash Backend/set-firmware-bucket-env.sh

set -e

REGION=us-east-1
ACCOUNT_ID=075138626693
LAMBDA=pawgate-api-handler
NEW_VAR_NAME=FIRMWARE_BUCKET
NEW_VAR_VALUE="pawgate-firmware-${ACCOUNT_ID}"

echo "═══════════════════════════════════════════════════════════"
echo "  Set $NEW_VAR_NAME en $LAMBDA (preservando otras vars)"
echo "═══════════════════════════════════════════════════════════"

# 1) Leer env vars actuales
echo ""
echo "▶ 1/2 — Leyendo env vars actuales..."
CURRENT_ENV=$(aws lambda get-function-configuration \
  --function-name "$LAMBDA" \
  --region "$REGION" \
  --query 'Environment.Variables' --output json 2>/dev/null || echo "{}")
if [ "$CURRENT_ENV" = "null" ] || [ -z "$CURRENT_ENV" ]; then
  CURRENT_ENV="{}"
fi
echo "   Env actual:"
echo "$CURRENT_ENV" | python3 -m json.tool | sed 's/^/     /'

# 2) Mergear FIRMWARE_BUCKET y updatear
echo ""
echo "▶ 2/2 — Mergeando $NEW_VAR_NAME=$NEW_VAR_VALUE y actualizando..."

NEW_ENV=$(echo "$CURRENT_ENV" | python3 -c "
import json, sys
e = json.load(sys.stdin)
e['$NEW_VAR_NAME'] = '$NEW_VAR_VALUE'
print(json.dumps({'Variables': e}))
")

aws lambda update-function-configuration \
  --function-name "$LAMBDA" \
  --region "$REGION" \
  --environment "$NEW_ENV" >/dev/null

echo "   ✔ Actualizado."
echo ""
echo "Env vars finales:"
aws lambda get-function-configuration \
  --function-name "$LAMBDA" \
  --region "$REGION" \
  --query 'Environment.Variables' --output json | python3 -m json.tool | sed 's/^/  /'

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✔ OK. La lambda deberia poder bootear de nuevo."
echo "═══════════════════════════════════════════════════════════"
