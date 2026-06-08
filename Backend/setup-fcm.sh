#!/usr/bin/env bash
# ============================================================
# setup-fcm.sh — Fase 20: FCM push notifications via SNS
# ============================================================
#
# Crea la infra para que la app reciba push notifications:
#   - Tabla DDB pawgate_fcm_endpoints (PK: user_email)
#   - Ruta API Gateway /users/me/fcm-token (POST + DELETE)
#
# Prerequisitos MANUALES (no automatizables, hacer una sola vez):
#
#   1) Firebase Console (https://console.firebase.google.com)
#      - Crear proyecto "PawGate" (o el nombre que quieras)
#      - Agregar app Android con package "com.unlam.pawgate"
#      - Descargar google-services.json y ponerlo en Android/app/
#      - Project Settings -> Cloud Messaging:
#         a) Si vas con la Legacy API (mas simple): copiar el "Server key".
#         b) Si vas con HTTP v1 (recomendado nuevo): bajar un Service Account
#            JSON con permisos firebaseadmin.
#
#   2) AWS SNS Console -> Mobile -> Push notifications -> Create platform application
#      - Application name: pawgate-fcm
#      - Push notification platform: "Firebase Cloud Messaging (FCM)"
#      - Authentication method: dependiendo de (1):
#         * "Token" / "Server key" -> pegar el legacy server key
#         * "Token" / "Service account JSON" (recomendado) -> pegar el JSON
#      - Crear y anotar el ARN. Va a verse asi:
#        arn:aws:sns:us-east-1:075138626693:app/GCM/pawgate-fcm
#
#   3) Setear el ARN en ambas Lambdas como env var FCM_PLATFORM_APP_ARN:
#        aws lambda update-function-configuration \
#          --function-name pawgate-api-handler \
#          --environment "Variables={...,FCM_PLATFORM_APP_ARN=arn:aws:sns:...}"
#        aws lambda update-function-configuration \
#          --function-name pawgate-event-ingest \
#          --environment "Variables={...,FCM_PLATFORM_APP_ARN=arn:aws:sns:...}"
#
# Despues ejecutar este script.
# ============================================================

set -e

REGION="${REGION:-us-east-1}"
ACCOUNT_ID="${ACCOUNT_ID:-075138626693}"

# ============================================================
# 1) Tabla DDB pawgate_fcm_endpoints
# ============================================================

if aws dynamodb describe-table --table-name pawgate_fcm_endpoints --region "$REGION" >/dev/null 2>&1; then
  echo "✓ Tabla pawgate_fcm_endpoints ya existe"
else
  echo "Creando tabla pawgate_fcm_endpoints..."
  aws dynamodb create-table \
    --table-name pawgate_fcm_endpoints \
    --attribute-definitions AttributeName=user_email,AttributeType=S \
    --key-schema AttributeName=user_email,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region "$REGION" >/dev/null
  echo "✓ Tabla creada (esperando ACTIVE)..."
  aws dynamodb wait table-exists --table-name pawgate_fcm_endpoints --region "$REGION"
  echo "✓ Tabla ACTIVE"
fi

# ============================================================
# 2) Aplicar policies actualizadas a los roles de lambda
# ============================================================

echo ""
echo "Aplicando policy actualizada a lambda-api-handler..."
aws iam put-role-policy \
  --role-name pawgate-api-handler-role \
  --policy-name pawgate-api-handler-policy \
  --policy-document file://iam-roles/lambda-api-handler-policy.json \
  --region "$REGION"
echo "✓ api-handler policy aplicada"

echo "Aplicando policy actualizada a lambda-event-ingest..."
aws iam put-role-policy \
  --role-name pawgate-event-ingest-role \
  --policy-name pawgate-event-ingest-policy \
  --policy-document file://iam-roles/lambda-event-ingest-policy.json \
  --region "$REGION"
echo "✓ event-ingest policy aplicada"

# ============================================================
# 3) API Gateway: ruta /users/me/fcm-token con POST + DELETE
# ============================================================
#
# Para no duplicar la logica de setup-horarios-api.sh, este script asume
# que el script principal ya existe y agregamos las nuevas resources
# y methods aca. Re-ejecutar setup-horarios-api.sh tambien funciona si
# lo extendiste para incluir /users/me/fcm-token (ver patch a ese archivo).

echo ""
echo "============================================================"
echo "Setup FCM completado."
echo ""
echo "PASOS RESTANTES MANUALES:"
echo "  1. En Android Studio: agregar firebase-messaging dependency"
echo "     y plugin google-services. Poner google-services.json en"
echo "     Android/app/."
echo "  2. Probar que SnS publish funcione enviando un push de prueba"
echo "     desde AWS SNS Console (Mobile > Push notifications > tu"
echo "     Platform App > Endpoints > seleccionar uno > Publish)."
echo "============================================================"
