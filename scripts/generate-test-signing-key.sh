#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-signing}"
KEYSTORE="${OUT_DIR}/llm-bt-test.jks"
ALIAS="${SIGNING_ALIAS:-llm-bt-test}"

if [[ -z "${SIGNING_PASSWORD:-}" ]]; then
  echo "ERRO: defina SIGNING_PASSWORD." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

rm -f "$KEYSTORE"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$SIGNING_PASSWORD" \
  -keypass "$SIGNING_PASSWORD" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -dname "CN=LLM-BT Test, OU=Development, O=LLM-BT, L=Ouro Preto do Oeste, ST=RO, C=BR" \
  -noprompt

echo "Keystore gerado: $KEYSTORE"
