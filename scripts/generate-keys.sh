#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PRIV=auth-service/src/main/resources/keys/private_key.pem
PUB=common-security/src/main/resources/keys/public_key.pem

if [[ -f "$PRIV" && -f "$PUB" && "${1:-}" != "--force" ]]; then
  echo "Keys already exist. Use --force to regenerate (invalidates all issued tokens)."
  exit 0
fi

mkdir -p "$(dirname "$PRIV")" "$(dirname "$PUB")"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIV"
openssl rsa -pubout -in "$PRIV" -out "$PUB"
chmod 600 "$PRIV"
head -1 "$PRIV" | grep -q "BEGIN PRIVATE KEY" || { echo "Private key is not PKCS8"; exit 1; }
echo "Generated key pair. Rebuild all images: docker compose build"