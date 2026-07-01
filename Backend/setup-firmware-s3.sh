#!/usr/bin/env bash
#
# Setup del bucket S3 para hostear los binarios de firmware OTA.
# Idempotente: si ya existe el bucket / policy, salta.
#
# Usage: bash Backend/setup-firmware-s3.sh
#
# Lo que hace:
#   1) Crea el bucket pawgate-firmware-075138626693 en us-east-1.
#   2) Desbloquea el "Block Public Access" del bucket (lo dejamos publico
#      porque los binarios no contienen secretos y simplifica el ESP32:
#      no necesita TLS/SigV4 para descargar).
#   3) Aplica una bucket policy que permite s3:GetObject publico.
#   4) Adjunta una IAM policy inline a la lambda pawgate-api-handler para
#      que pueda hacer s3:GetObject del manifest.json (la lambda lo lee
#      en GET /devices/{id}/firmware/latest).
#
# Despues de correr esto, podes usar Backend/release-firmware.sh para subir
# binarios versionados.

set -e

REGION=us-east-1
ACCOUNT_ID=075138626693
BUCKET=pawgate-firmware-${ACCOUNT_ID}
API_LAMBDA=pawgate-api-handler

echo "═══════════════════════════════════════════════════════════"
echo "  Setup bucket $BUCKET para firmware OTA"
echo "═══════════════════════════════════════════════════════════"

# ============================================================
# 1) Crear bucket
# ============================================================
echo ""
echo "▶ 1/4 — Verificando bucket $BUCKET..."

if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "   ✔ Bucket $BUCKET ya existe"
else
  echo "   Creando bucket $BUCKET en $REGION..."
  # us-east-1 es el caso especial: NO se pasa LocationConstraint.
  aws s3api create-bucket \
    --bucket "$BUCKET" \
    --region "$REGION" >/dev/null
  echo "   ✔ Bucket $BUCKET creado"
fi

# ============================================================
# 2) Desbloquear Public Access Block
# ============================================================
echo ""
echo "▶ 2/4 — Desbloqueando Public Access Block..."
aws s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
    "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false" \
  >/dev/null
echo "   ✔ Public Access Block deshabilitado"

# ============================================================
# 3) Aplicar bucket policy (read publico)
# ============================================================
echo ""
echo "▶ 3/4 — Aplicando bucket policy (read publico)..."

BUCKET_POLICY=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::$BUCKET/*"
    }
  ]
}
EOF
)

aws s3api put-bucket-policy \
  --bucket "$BUCKET" \
  --policy "$BUCKET_POLICY" >/dev/null
echo "   ✔ Policy aplicada (cualquiera puede s3:GetObject)"

# ============================================================
# 4) Adjuntar IAM policy a la lambda apiHandler
# ============================================================
echo ""
echo "▶ 4/4 — Adjuntando IAM policy a $API_LAMBDA..."

ROLE_ARN=$(aws lambda get-function-configuration \
  --function-name "$API_LAMBDA" \
  --region "$REGION" \
  --query 'Role' --output text 2>/dev/null || echo "")

if [ -z "$ROLE_ARN" ]; then
  echo "   ⚠ Lambda $API_LAMBDA no encontrada, saltando IAM"
else
  ROLE_NAME=$(basename "$ROLE_ARN")
  LAMBDA_POLICY=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::$BUCKET/manifest.json"
    }
  ]
}
EOF
)
  aws iam put-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-name "pawgate-firmware-s3-read" \
    --policy-document "$LAMBDA_POLICY" >/dev/null
  echo "   ✔ Policy pawgate-firmware-s3-read attached a $ROLE_NAME"
fi

# ============================================================
# Resumen
# ============================================================
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✔ Setup completo"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Bucket:    s3://$BUCKET/"
echo "Base URL:  http://$BUCKET.s3.$REGION.amazonaws.com/"
echo ""
echo "Proximos pasos:"
echo "  1) Subir un firmware:"
echo "     bash Backend/release-firmware.sh 1.1.0"
echo "  2) La URL publica del .bin sera:"
echo "     http://$BUCKET.s3.$REGION.amazonaws.com/firmware-1.1.0.bin"
echo "  3) El manifest.json contiene la metadata de la version 'latest':"
echo "     http://$BUCKET.s3.$REGION.amazonaws.com/manifest.json"
