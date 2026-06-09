#!/usr/bin/env bash
# Setup completo de Fase 15.C: Cognito + Lambda apiHandler + API Gateway.
#
# Usage: bash Backend/setup-15c.sh
#
# Pre-requisitos:
#   - AWS CLI configurado con credenciales fede-dev (us-east-1)
#   - Lambda pawgate-event-ingest ya deployada (15.B)
#   - DDB tables ya creadas (15.A)

set -e  # exit on error

ACCOUNT_ID=075138626693
REGION=us-east-1

echo "═══════════════════════════════════════════════════════════"
echo "  Fase 15.C — Cognito + API Gateway + Lambda apiHandler"
echo "═══════════════════════════════════════════════════════════"

# ============================================================
# 1) COGNITO USER POOL
# ============================================================
echo ""
echo "▶ 1/6 — Creando Cognito User Pool..."

USER_POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name pawgate-users \
  --policies "PasswordPolicy={MinimumLength=8,RequireUppercase=true,RequireLowercase=true,RequireNumbers=true,RequireSymbols=false}" \
  --auto-verified-attributes email \
  --username-attributes email \
  --schema 'Name=email,Required=true,Mutable=true' 'Name=name,Required=true,Mutable=true' \
  --region $REGION \
  --query 'UserPool.Id' --output text)

echo "   User Pool ID: $USER_POOL_ID"

echo ""
echo "▶ 2/6 — Creando App Client..."

APP_CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $USER_POOL_ID \
  --client-name pawgate-mobile-app \
  --no-generate-secret \
  --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_REFRESH_TOKEN_AUTH \
  --token-validity-units "AccessToken=hours,IdToken=hours,RefreshToken=days" \
  --access-token-validity 1 \
  --id-token-validity 1 \
  --refresh-token-validity 30 \
  --region $REGION \
  --query 'UserPoolClient.ClientId' --output text)

echo "   App Client ID: $APP_CLIENT_ID"

# ============================================================
# 2) IAM ROLE PARA LAMBDA apiHandler
# ============================================================
echo ""
echo "▶ 3/6 — Creando IAM role para apiHandler..."

aws iam create-role \
  --role-name pawgate-api-handler-role \
  --assume-role-policy-document file://Backend/iam-roles/lambda-event-ingest-trust.json \
  >/dev/null 2>&1 || echo "   (role ya existia)"

aws iam put-role-policy \
  --role-name pawgate-api-handler-role \
  --policy-name pawgate-api-handler-policy \
  --policy-document file://Backend/iam-roles/lambda-api-handler-policy.json

# Esperar a que IAM propague el rol (toma ~5-10s)
sleep 10

# ============================================================
# 3) LAMBDA apiHandler
# ============================================================
echo ""
echo "▶ 4/6 — Empaquetando y creando Lambda apiHandler..."

cd Backend/lambdas/apiHandler
zip -q -r apiHandler.zip lambda_function.py
cd ../../..

aws lambda create-function \
  --function-name pawgate-api-handler \
  --runtime python3.12 \
  --role arn:aws:iam::${ACCOUNT_ID}:role/pawgate-api-handler-role \
  --handler lambda_function.lambda_handler \
  --timeout 15 \
  --memory-size 512 \
  --environment "Variables={USER_POOL_ID=$USER_POOL_ID,APP_CLIENT_ID=$APP_CLIENT_ID,EVENTS_TABLE=pawgate_events}" \
  --zip-file fileb://Backend/lambdas/apiHandler/apiHandler.zip \
  --region $REGION \
  >/dev/null

echo "   Lambda creada"

# ============================================================
# 4) API GATEWAY REST API
# ============================================================
echo ""
echo "▶ 5/6 — Creando API Gateway..."

API_ID=$(aws apigateway create-rest-api \
  --name pawgate-api \
  --description "PawGate REST API (auth + devices)" \
  --endpoint-configuration types=REGIONAL \
  --region $REGION \
  --query 'id' --output text)

echo "   API ID: $API_ID"

# Get root resource id
ROOT_ID=$(aws apigateway get-resources \
  --rest-api-id $API_ID \
  --region $REGION \
  --query 'items[0].id' --output text)

# Crear Cognito Authorizer (apunta al User Pool)
AUTH_ID=$(aws apigateway create-authorizer \
  --rest-api-id $API_ID \
  --name pawgate-cognito-auth \
  --type COGNITO_USER_POOLS \
  --provider-arns "arn:aws:cognito-idp:${REGION}:${ACCOUNT_ID}:userpool/${USER_POOL_ID}" \
  --identity-source 'method.request.header.Authorization' \
  --region $REGION \
  --query 'id' --output text)

echo "   Cognito Authorizer ID: $AUTH_ID"

LAMBDA_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:pawgate-api-handler"
INTEGRATION_URI="arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/${LAMBDA_ARN}/invocations"

create_route() {
  local PARENT=$1
  local PATH_PART=$2
  local METHOD=$3
  local AUTH=$4   # NONE | COGNITO_USER_POOLS

  # Crear resource si no existe (returns existing if PATH_PART matches)
  RES=$(aws apigateway create-resource \
    --rest-api-id $API_ID \
    --parent-id $PARENT \
    --path-part "$PATH_PART" \
    --region $REGION \
    --query 'id' --output text 2>/dev/null || \
    aws apigateway get-resources --rest-api-id $API_ID --region $REGION \
      --query "items[?pathPart=='$PATH_PART' && parentId=='$PARENT'].id" --output text)

  # PUT method
  local AUTH_ARGS=""
  if [ "$AUTH" = "COGNITO" ]; then
    AUTH_ARGS="--authorization-type COGNITO_USER_POOLS --authorizer-id $AUTH_ID"
  else
    AUTH_ARGS="--authorization-type NONE"
  fi

  aws apigateway put-method \
    --rest-api-id $API_ID \
    --resource-id $RES \
    --http-method $METHOD \
    $AUTH_ARGS \
    --region $REGION >/dev/null

  aws apigateway put-integration \
    --rest-api-id $API_ID \
    --resource-id $RES \
    --http-method $METHOD \
    --type AWS_PROXY \
    --integration-http-method POST \
    --uri $INTEGRATION_URI \
    --region $REGION >/dev/null

  echo "   Created $METHOD $PATH_PART (auth: $AUTH, res_id: $RES)"
  echo $RES
}

# Crear estructura de recursos:
# /auth/signup, /auth/confirm, /auth/login (sin auth)
# /devices/{id}/history, /devices/{id}/cmd/{cmd} (con Cognito)

echo "   Creando rutas /auth/*..."
AUTH_ROOT=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $ROOT_ID --path-part "auth" --region $REGION --query 'id' --output text)
create_route $AUTH_ROOT "signup" POST NONE >/dev/null
create_route $AUTH_ROOT "confirm" POST NONE >/dev/null
create_route $AUTH_ROOT "login" POST NONE >/dev/null

echo "   Creando rutas /devices/{id}/*..."
DEV_ROOT=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $ROOT_ID --path-part "devices" --region $REGION --query 'id' --output text)
DEV_ID_RES=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $DEV_ROOT --path-part "{id}" --region $REGION --query 'id' --output text)

# /devices/{id}/history
HIST_RES=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $DEV_ID_RES --path-part "history" --region $REGION --query 'id' --output text)
aws apigateway put-method --rest-api-id $API_ID --resource-id $HIST_RES --http-method GET \
  --authorization-type COGNITO_USER_POOLS --authorizer-id $AUTH_ID --region $REGION >/dev/null
aws apigateway put-integration --rest-api-id $API_ID --resource-id $HIST_RES --http-method GET \
  --type AWS_PROXY --integration-http-method POST --uri $INTEGRATION_URI --region $REGION >/dev/null
echo "   Created GET /devices/{id}/history"

# /devices/{id}/cmd/{cmd}
CMD_ROOT=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $DEV_ID_RES --path-part "cmd" --region $REGION --query 'id' --output text)
CMD_RES=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $CMD_ROOT --path-part "{cmd}" --region $REGION --query 'id' --output text)
aws apigateway put-method --rest-api-id $API_ID --resource-id $CMD_RES --http-method POST \
  --authorization-type COGNITO_USER_POOLS --authorizer-id $AUTH_ID --region $REGION >/dev/null
aws apigateway put-integration --rest-api-id $API_ID --resource-id $CMD_RES --http-method POST \
  --type AWS_PROXY --integration-http-method POST --uri $INTEGRATION_URI --region $REGION >/dev/null
echo "   Created POST /devices/{id}/cmd/{cmd}"

# Permiso para que API GW invoque la Lambda
aws lambda add-permission \
  --function-name pawgate-api-handler \
  --statement-id allow-apigw \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:${REGION}:${ACCOUNT_ID}:${API_ID}/*/*/*" \
  --region $REGION >/dev/null 2>&1 || echo "   (permission ya existia)"

# ============================================================
# 5) DEPLOY al stage "prod"
# ============================================================
echo ""
echo "▶ 6/6 — Deploying al stage 'prod'..."

aws apigateway create-deployment \
  --rest-api-id $API_ID \
  --stage-name prod \
  --region $REGION >/dev/null

API_URL="https://${API_ID}.execute-api.${REGION}.amazonaws.com/prod"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✅ Fase 15.C deployment completo"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "  API Base URL:      $API_URL"
echo "  User Pool ID:      $USER_POOL_ID"
echo "  App Client ID:     $APP_CLIENT_ID"
echo "  API ID:            $API_ID"
echo ""
echo "  Guardalos:"
cat > Backend/setup-15c-output.json <<EOF
{
  "api_base_url": "$API_URL",
  "user_pool_id": "$USER_POOL_ID",
  "app_client_id": "$APP_CLIENT_ID",
  "api_id": "$API_ID",
  "authorizer_id": "$AUTH_ID",
  "region": "$REGION",
  "account_id": "$ACCOUNT_ID"
}
EOF
echo "  → guardado en Backend/setup-15c-output.json"
echo ""
echo "  Test smoke (signup):"
echo "    curl -X POST $API_URL/auth/signup \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"email\":\"test@pawgate.io\",\"password\":\"Test1234\",\"name\":\"Test\"}'"
echo ""
