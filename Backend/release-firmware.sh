#!/usr/bin/env bash
#
# Release de un firmware nuevo a S3 para OTA.
#
# Lo que hace:
#   1) Compila Embebido/Proyecto_sin_Vibecoding con `pio run` (env esp32dev).
#   2) Calcula sha256 del .bin resultante.
#   3) Sube el .bin a s3://pawgate-firmware-<account>/firmware-<version>.bin.
#   4) Sobreescribe el manifest.json con la metadata de esta version como
#      "latest" — eso es lo que la app lee en GET /firmware/latest.
#
# Usage:
#   bash Backend/release-firmware.sh 1.1.0
#   bash Backend/release-firmware.sh 1.1.0 --skip-build   (si ya tenes el .bin)
#
# Requisitos:
#   - awscli configurado con la cuenta 075138626693
#   - pio en el PATH (pip install platformio o brew install platformio)
#   - sha256sum (linux) o shasum -a 256 (macOS)

set -e

# ============================================================
# Args + constantes
# ============================================================
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "ERROR: pasame la version. Ej: bash $0 1.1.0"
  exit 1
fi

SKIP_BUILD=0
if [ "${2:-}" = "--skip-build" ]; then
  SKIP_BUILD=1
fi

REGION=us-east-1
ACCOUNT_ID=075138626693
BUCKET=pawgate-firmware-${ACCOUNT_ID}

# Detectar la raiz del repo (este script vive en Backend/)
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FW_DIR="$REPO_ROOT/Embebido/Proyecto_sin_Vibecoding"
BIN_LOCAL="$FW_DIR/.pio/build/esp32dev/firmware.bin"
BIN_NAME="firmware-${VERSION}.bin"
S3_KEY="$BIN_NAME"
S3_URL="http://${BUCKET}.s3.${REGION}.amazonaws.com/${S3_KEY}"
MANIFEST_URL="http://${BUCKET}.s3.${REGION}.amazonaws.com/manifest.json"

echo "═══════════════════════════════════════════════════════════"
echo "  Release firmware v$VERSION → s3://$BUCKET/"
echo "═══════════════════════════════════════════════════════════"
echo "  REPO_ROOT  = $REPO_ROOT"
echo "  FW_DIR     = $FW_DIR"
echo "  BIN_LOCAL  = $BIN_LOCAL"
echo "  S3_URL     = $S3_URL"
echo ""

# ============================================================
# 1) Compilar (a menos que pasen --skip-build)
# ============================================================
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "▶ 1/4 — Compilando con pio run (env esp32dev)..."
  if ! command -v pio >/dev/null 2>&1; then
    echo "ERROR: pio no esta en PATH. Instalalo con: pip install platformio"
    exit 1
  fi
  (cd "$FW_DIR" && pio run -e esp32dev)
  if [ ! -f "$BIN_LOCAL" ]; then
    echo "ERROR: build OK pero no aparecio el .bin en $BIN_LOCAL"
    exit 1
  fi
  echo "   ✔ Compilado: $(ls -lh "$BIN_LOCAL" | awk '{print $5}') en $BIN_LOCAL"
else
  echo "▶ 1/4 — Skipping build (--skip-build)"
  if [ ! -f "$BIN_LOCAL" ]; then
    echo "ERROR: --skip-build pero no encuentro $BIN_LOCAL"
    exit 1
  fi
fi

# ============================================================
# 2) sha256 + size
# ============================================================
echo ""
echo "▶ 2/4 — Calculando sha256 y size..."
if command -v sha256sum >/dev/null 2>&1; then
  SHA256=$(sha256sum "$BIN_LOCAL" | awk '{print $1}')
else
  # macOS
  SHA256=$(shasum -a 256 "$BIN_LOCAL" | awk '{print $1}')
fi
SIZE=$(wc -c < "$BIN_LOCAL" | tr -d ' ')
echo "   sha256 = $SHA256"
echo "   size   = $SIZE bytes"

# ============================================================
# 3) Subir .bin a S3
# ============================================================
echo ""
echo "▶ 3/4 — Subiendo .bin a S3..."
aws s3 cp "$BIN_LOCAL" "s3://$BUCKET/$S3_KEY" \
  --region "$REGION" \
  --content-type "application/octet-stream" \
  --cache-control "public, max-age=300"
echo "   ✔ Subido a $S3_URL"

# ============================================================
# 4) Actualizar manifest.json en S3
# ============================================================
echo ""
echo "▶ 4/4 — Actualizando manifest.json..."

RELEASED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
MANIFEST=$(cat <<EOF
{
  "latest_version": "$VERSION",
  "url": "$S3_URL",
  "sha256": "$SHA256",
  "size": $SIZE,
  "released_at": "$RELEASED_AT",
  "release_notes": "OTA release $VERSION"
}
EOF
)

# Subimos el manifest a stdin con un tmpfile (mas portable que --body fileb://-)
TMP_MANIFEST=$(mktemp -t pawgate-manifest.XXXXXX)
echo "$MANIFEST" > "$TMP_MANIFEST"
aws s3 cp "$TMP_MANIFEST" "s3://$BUCKET/manifest.json" \
  --region "$REGION" \
  --content-type "application/json" \
  --cache-control "public, max-age=30"
rm -f "$TMP_MANIFEST"

echo "   ✔ Manifest actualizado: $MANIFEST_URL"

# ============================================================
# Resumen
# ============================================================
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✔ Release v$VERSION completo"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Para disparar la OTA en el ESP32 desde la app:"
echo "  - GET    https://<api>/devices/pawgate-001/firmware/latest"
echo "      devuelve: { latest_version, url, sha256, size, released_at }"
echo "  - POST   https://<api>/devices/pawgate-001/cmd/ota"
echo "      body:   { \"version\": \"$VERSION\" }"
echo "      (la lambda lee el manifest y publica MQTT con url+sha256)"
echo ""
echo "Para probar manual desde tu maquina (sin la app):"
echo "  aws iot-data publish --topic 'pawgate/pawgate-001/cmd/ota' --region $REGION \\"
echo "    --payload '$(printf '{"url":"%s","version":"%s","sha256":"%s"}' "$S3_URL" "$VERSION" "$SHA256" | base64)' \\"
echo "    --cli-binary-format raw-in-base64-out"
