#!/usr/bin/env bash
# Generates throw-away P-256 key pairs for LOCAL development of internal service authentication:
# one pair per caller (the gateway; add a service name to let it call other services).
# Output goes to .dev-keys/ (git-ignored). Private keys are mode 600. Never reuse these outside local development;
# real environments need keys issued by the approved deployment platform (PD-08, not decided).
#
# Usage: tools/gen-dev-keys.sh [name ...]      (default: gateway)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${FINORA_KEYS_DIR:-$ROOT/.dev-keys}"
mkdir -p "$DIR"
chmod 700 "$DIR"
names=("$@")
[ ${#names[@]} -gt 0 ] || names=(gateway)
for name in "${names[@]}"; do
  case "$name" in *[!a-z0-9-]*|"") echo "invalid name: $name" >&2; exit 1;; esac
  if [ -f "$DIR/$name.key.pem" ]; then echo "exists: $DIR/$name.key.pem (kept)"; continue; fi
  # PKCS#8 private key + X.509 public key, the formats the services load.
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$DIR/$name.key.pem" 2>/dev/null
  chmod 600 "$DIR/$name.key.pem"
  openssl pkey -in "$DIR/$name.key.pem" -pubout -out "$DIR/$name.pub.pem"
  echo "created: $DIR/$name.key.pem and $DIR/$name.pub.pem"
done
