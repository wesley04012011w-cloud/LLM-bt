#!/usr/bin/env bash
set -euo pipefail

UNSIGNED="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
OUT="${2:-app/build/outputs/apk/release/app-release.apk}"
KEYSTORE="${SIGNING_KEYSTORE:-signing/llm-bt-test.jks}"
ALIAS="${SIGNING_ALIAS:-llm-bt-test}"

if [[ -z "${SIGNING_PASSWORD:-}" ]]; then
  echo "ERRO: defina SIGNING_PASSWORD." >&2
  exit 1
fi

if [[ ! -f "$UNSIGNED" ]]; then
  echo "ERRO: APK unsigned não encontrado: $UNSIGNED" >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
  echo "ERRO: keystore não encontrado: $KEYSTORE" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

BUILD_TOOLS_DIR="${ANDROID_HOME}/build-tools/36.0.0"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

if [[ ! -x "$ZIPALIGN" || ! -x "$APKSIGNER" ]]; then
  echo "ERRO: zipalign/apksigner não encontrados em $BUILD_TOOLS_DIR" >&2
  exit 1
fi

ALIGNED="$(mktemp --suffix=.aligned.apk)"
trap 'rm -f "$ALIGNED"' EXIT

"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$SIGNING_PASSWORD" \
  --key-pass "pass:$SIGNING_PASSWORD" \
  --out "$OUT" \
  "$ALIGNED"

"$APKSIGNER" verify --verbose --print-certs "$OUT"

echo "APK assinado: $OUT"
