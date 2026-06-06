#!/usr/bin/env bash
# Setup de los endpoints de Horarios + Device State en API Gateway.
#
# Idempotente: si los resources/methods ya existen, los saltea.
#
# Usage: bash Backend/setup-horarios-api.sh
#
# Pre-requisitos:
#   - REST API "pawgate-api" ya existe (creada en 15.C)
#   - Lambda "pawgate-api-handler" ya existe (creada en 15.C)
#   - Cognito Authorizer ya existe en la API (creada en 15.C)
#   - Tabla pawgate_device_state ya existe (crearla aparte si falta)

set -e

ACCOUNT_ID=075138626693
REGION=us-east-1
API_NAME=pawgate-api
LAMBDA_NAME=pawgate-api-handler
STAGE=prod

echo "═══════════════════════════════════════════════════════════"
echo "  Setup endpoints: Horarios + Device State"
echo "═══════════════════════════════════════════════════════════"

# ============================================================
# 1) Obtener IDs existentes (REST API + Lambda + Authorizer)
# ============================================================
echo ""
echo "▶ 1/6 — Detectando recursos existentes..."

REST_API_ID=$(aws apigateway get-rest-apis \
  --region $REGION \
  --query "items[?name=='$API_NAME'].id | [0]" \
  --output text)

if [ -z "$REST_API_ID" ] || [ "$REST_API_ID" = "None" ]; then
  echo "   ERROR: No se encontró la REST API '$API_NAME'. Corré primero setup-15c.sh."
  exit 1
fi
echo "   REST API ID: $REST_API_ID"

LAMBDA_ARN=$(aws lambda get-function \
  --function-name $LAMBDA_NAME \
  --region $REGION \
  --query 'Configuration.FunctionArn' --output text)
echo "   Lambda ARN: $LAMBDA_ARN"

LAMBDA_INVOKE_URI="arn:aws:apigateway:$REGION:lambda:path/2015-03-31/functions/$LAMBDA_ARN/invocations"

AUTHORIZER_ID=$(aws apigateway get-authorizers \
  --rest-api-id $REST_API_ID \
  --region $REGION \
  --query "items[?type=='COGNITO_USER_POOLS'].id | [0]" \
  --output text)

if [ -z "$AUTHORIZER_ID" ] || [ "$AUTHORIZER_ID" = "None" ]; then
  echo "   ERROR: No se encontró un Cognito Authorizer. Corré primero setup-15c.sh."
  exit 1
fi
echo "   Authorizer ID: $AUTHORIZER_ID"

# Helper: buscar resource id por path; devuelve "" si no existe
get_resource_id() {
  local path="$1"
  aws apigateway get-resources \
    --rest-api-id $REST_API_ID \
    --region $REGION \
    --limit 500 \
    --query "items[?path=='$path'].id | [0]" \
    --output text
}

DEVICES_ID_RESOURCE=$(get_resource_id "/devices/{id}")
if [ -z "$DEVICES_ID_RESOURCE" ] || [ "$DEVICES_ID_RESOURCE" = "None" ]; then
  echo "   ERROR: No se encontró el resource /devices/{id}. Corré primero setup-15c.sh."
  exit 1
fi
echo "   /devices/{id} resource ID: $DEVICES_ID_RESOURCE"

# ============================================================
# 2) Crear sub-resources nuevos (idempotente)
# ============================================================
echo ""
echo "▶ 2/6 — Asegurando sub-resources..."

# Helper: crea un resource si no existe; export <nombre>_ID con el id final.
ensure_resource() {
  local parent_id="$1"
  local part="$2"
  local full_path="$3"
  local existing
  existing=$(get_resource_id "$full_path")
  if [ -n "$existing" ] && [ "$existing" != "None" ]; then
    echo "   = $full_path (ya existe: $existing)"
    echo "$existing"
    return
  fi
  local new_id
  new_id=$(aws apigateway create-resource \
    --rest-api-id $REST_API_ID \
    --parent-id $parent_id \
    --path-part "$part" \
    --region $REGION \
    --query 'id' --output text)
  echo "   + $full_path (creado: $new_id)"
  echo "$new_id"
}

SCHEDULES_RES=$(ensure_resource "$DEVICES_ID_RESOURCE" "schedules" "/devices/{id}/schedules" | tail -1)
SCHEDULE_ID_RES=$(ensure_resource "$SCHEDULES_RES" "{schedule_id}" "/devices/{id}/schedules/{schedule_id}" | tail -1)
STATE_RES=$(ensure_resource "$DEVICES_ID_RESOURCE" "state" "/devices/{id}/state" | tail -1)
OVERRIDE_UNBLOCK_RES=$(ensure_resource "$STATE_RES" "override-unblock" "/devices/{id}/state/override-unblock" | tail -1)
OVERRIDE_BLOCK_RES=$(ensure_resource "$STATE_RES" "override-block" "/devices/{id}/state/override-block" | tail -1)

# ============================================================
# 3) Crear methods con Cognito Authorizer + Lambda proxy (idempotente)
# ============================================================
echo ""
echo "▶ 3/6 — Asegurando methods + integraciones..."

method_exists() {
  local resource_id="$1"
  local http_method="$2"
  aws apigateway get-method \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method $http_method \
    --region $REGION >/dev/null 2>&1
}

ensure_method() {
  local resource_id="$1"
  local http_method="$2"
  local label="$3"

  if method_exists $resource_id $http_method; then
    echo "   = $label (ya existe)"
    return
  fi

  # PUT method con Cognito Authorizer
  aws apigateway put-method \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method $http_method \
    --authorization-type COGNITO_USER_POOLS \
    --authorizer-id $AUTHORIZER_ID \
    --region $REGION >/dev/null

  # PUT integration con Lambda proxy
  aws apigateway put-integration \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method $http_method \
    --type AWS_PROXY \
    --integration-http-method POST \
    --uri $LAMBDA_INVOKE_URI \
    --region $REGION >/dev/null

  echo "   + $label (creado)"
}

# CORS preflight (OPTIONS sin auth) para que el browser/Chrome no falle si se prueba desde web.
ensure_cors() {
  local resource_id="$1"
  local label="$2"

  if method_exists $resource_id OPTIONS; then
    echo "   = $label OPTIONS (ya existe)"
    return
  fi

  aws apigateway put-method \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method OPTIONS \
    --authorization-type NONE \
    --region $REGION >/dev/null

  aws apigateway put-integration \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method OPTIONS \
    --type MOCK \
    --request-templates '{"application/json":"{\"statusCode\":200}"}' \
    --region $REGION >/dev/null

  aws apigateway put-method-response \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method OPTIONS \
    --status-code 200 \
    --response-parameters \
      method.response.header.Access-Control-Allow-Origin=false,method.response.header.Access-Control-Allow-Headers=false,method.response.header.Access-Control-Allow-Methods=false \
    --region $REGION >/dev/null

  aws apigateway put-integration-response \
    --rest-api-id $REST_API_ID \
    --resource-id $resource_id \
    --http-method OPTIONS \
    --status-code 200 \
    --response-parameters \
      "method.response.header.Access-Control-Allow-Origin='*',method.response.header.Access-Control-Allow-Headers='Content-Type,Authorization',method.response.header.Access-Control-Allow-Methods='GET,POST,PUT,DELETE,OPTIONS'" \
    --region $REGION >/dev/null

  echo "   + $label OPTIONS (CORS preflight)"
}

# /devices/{id}/schedules         GET, POST
ensure_method $SCHEDULES_RES GET  "GET    /devices/{id}/schedules"
ensure_method $SCHEDULES_RES POST "POST   /devices/{id}/schedules"
ensure_cors   $SCHEDULES_RES      "       /devices/{id}/schedules"

# /devices/{id}/schedules/{schedule_id}  PUT, DELETE
ensure_method $SCHEDULE_ID_RES PUT    "PUT    /devices/{id}/schedules/{schedule_id}"
ensure_method $SCHEDULE_ID_RES DELETE "DELETE /devices/{id}/schedules/{schedule_id}"
ensure_cors   $SCHEDULE_ID_RES        "       /devices/{id}/schedules/{schedule_id}"

# /devices/{id}/state             GET
ensure_method $STATE_RES GET "GET    /devices/{id}/state"
ensure_cors   $STATE_RES     "       /devices/{id}/state"

# /devices/{id}/state/override-unblock   POST
ensure_method $OVERRIDE_UNBLOCK_RES POST "POST   /devices/{id}/state/override-unblock"
ensure_cors   $OVERRIDE_UNBLOCK_RES      "       /devices/{id}/state/override-unblock"

# /devices/{id}/state/override-block     POST
ensure_method $OVERRIDE_BLOCK_RES POST "POST   /devices/{id}/state/override-block"
ensure_cors   $OVERRIDE_BLOCK_RES      "       /devices/{id}/state/override-block"

# ============================================================
# 4) Lambda permission para que API GW pueda invocar el handler
# ============================================================
echo ""
echo "▶ 4/6 — Asegurando lambda permission para API Gateway..."

STATEMENT_ID="allow-apigw-horarios-$(date +%Y%m%d)"
aws lambda add-permission \
  --function-name $LAMBDA_NAME \
  --statement-id $STATEMENT_ID \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:$REGION:$ACCOUNT_ID:$REST_API_ID/*/*/*" \
  --region $REGION 2>/dev/null && echo "   + Permission $STATEMENT_ID agregado" \
  || echo "   = Permission ya estaba (o conflicto: probablemente ya cubierto por la wildcard de setup-15c)"

# ============================================================
# 5) Deploy del stage
# ============================================================
echo ""
echo "▶ 5/6 — Deploy al stage '$STAGE'..."

aws apigateway create-deployment \
  --rest-api-id $REST_API_ID \
  --stage-name $STAGE \
  --description "Add schedules + device state endpoints" \
  --region $REGION \
  --query 'id' --output text | xargs -I {} echo "   Deployment ID: {}"

# ============================================================
# 6) Resumen
# ============================================================
echo ""
echo "▶ 6/6 — Resumen"
echo ""
echo "Endpoints registrados (todos con Cognito Authorizer):"
echo "  GET    /devices/{id}/schedules"
echo "  POST   /devices/{id}/schedules"
echo "  PUT    /devices/{id}/schedules/{schedule_id}"
echo "  DELETE /devices/{id}/schedules/{schedule_id}"
echo "  GET    /devices/{id}/state"
echo "  POST   /devices/{id}/state/override-unblock"
echo "  POST   /devices/{id}/state/override-block"
echo ""
echo "Base URL: https://$REST_API_ID.execute-api.$REGION.amazonaws.com/$STAGE/"
echo ""
echo "✓ Listo."
