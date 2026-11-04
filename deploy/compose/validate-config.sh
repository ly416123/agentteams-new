#!/usr/bin/env sh
set -eu

env_file=${1:-"$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/.env"}
if [ ! -r "$env_file" ]; then
  echo "Cannot read environment file: $env_file" >&2
  exit 2
fi

value() {
  awk -F= -v key="$1" '$1 == key { value=substr($0, index($0, "=") + 1) } END { print value }' "$env_file" | sed 's/^"//; s/"$//; s/^'"'"'//; s/'"'"'$//'
}

replicas=$(value WORKER_REPLICAS)
case "$replicas" in
  ''|*[!0-9]*) echo "WORKER_REPLICAS must be an integer from 0 through 8" >&2; exit 2 ;;
esac
if [ "$replicas" -lt 0 ] || [ "$replicas" -gt 8 ]; then
  echo "WORKER_REPLICAS must be between 0 and 8" >&2
  exit 2
fi

password=$(value POSTGRES_PASSWORD)
if [ -z "$password" ] || [ "$password" = "replace-with-a-long-random-password" ]; then
  echo "Set POSTGRES_PASSWORD to a unique, strong value in $env_file" >&2
  exit 2
fi

for key in CONTROL_PLANE_IMAGE GATEWAY_IMAGE WORKER_IMAGE; do
  image=$(value "$key")
  case "$image" in ghcr.io/ly416123/*@sha256:*) ;; *) echo "$key must reference ghcr.io/ly416123 by digest" >&2; exit 2 ;; esac
  digest=${image##*@sha256:}
  if [ "$image" = "$digest" ] || [ "${#digest}" -ne 64 ]; then
    echo "$key must be an immutable GHCR image reference ending in @sha256:<64 hex chars>" >&2
    exit 2
  fi
  case "$digest" in *[!0123456789abcdefABCDEF]*) echo "$key has an invalid sha256 digest" >&2; exit 2 ;; esac
done

if [ "$replicas" -gt 0 ]; then
  token_file=$(value MODEL_TOKEN_FILE)
  case "$token_file" in
    /*) ;;
    *) token_file="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd)/$token_file" ;;
  esac
  if [ ! -r "$token_file" ] || [ "${token_file##*/}" = "model-token.example" ]; then
    echo "Set MODEL_TOKEN_FILE to a readable, real model token file before starting Workers" >&2
    exit 2
  fi
  slot=1
  while [ "$slot" -le "$replicas" ]; do
    id=$(value "WORKER_${slot}_AGENT_ID")
    if ! printf '%s\n' "$id" | awk '/^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/ { ok=1 } END { exit !ok }'; then
      echo "WORKER_${slot}_AGENT_ID must be a UUID registered with this Control Plane" >&2
      exit 2
    fi
    slot=$((slot + 1))
  done
fi

echo "Compose configuration prerequisites are valid (Workers: $replicas)"
