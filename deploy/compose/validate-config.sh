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
if [ "${#password}" -lt 24 ] || [ "$password" = "replace-with-a-long-random-password" ]; then
  echo "Set POSTGRES_PASSWORD to a unique value of at least 24 characters in $env_file" >&2
  exit 2
fi

storage_enabled=$(value AGENTTEAMS_STORAGE_ENABLED)
case "$storage_enabled" in
  true|false) ;;
  *) echo "AGENTTEAMS_STORAGE_ENABLED must be true or false" >&2; exit 2 ;;
esac
if [ "$storage_enabled" = "true" ]; then
  object_store_access_key=$(value OBJECT_STORE_ACCESS_KEY)
  object_store_secret_key=$(value OBJECT_STORE_SECRET_KEY)
  if [ "${#object_store_access_key}" -lt 3 ] || [ "${#object_store_secret_key}" -lt 24 ] \
      || [ "$object_store_secret_key" = "replace-with-a-32-character-object-store-secret" ]; then
    echo "Set OBJECT_STORE_ACCESS_KEY (3+ chars) and OBJECT_STORE_SECRET_KEY (24+ chars) before enabling storage" >&2
    exit 2
  fi
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
  if [ ! -r "$token_file" ] || [ ! -s "$token_file" ] || [ "${token_file##*/}" = "model-token.example" ]; then
    echo "Set MODEL_TOKEN_FILE to a readable, non-empty model token file before starting Workers" >&2
    exit 2
  fi
  slot=1
  seen_ids=" "
  while [ "$slot" -le "$replicas" ]; do
    id=$(value "WORKER_${slot}_AGENT_ID")
    if ! printf '%s\n' "$id" | awk '/^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/ { ok=1 } END { exit !ok }'; then
      echo "WORKER_${slot}_AGENT_ID must be a UUID registered with this Control Plane" >&2
      exit 2
    fi
    normalized_id=$(printf '%s' "$id" | tr '[:upper:]' '[:lower:]')
    case "$seen_ids" in
      *" $normalized_id "*) echo "Worker Agent UUIDs must be unique" >&2; exit 2 ;;
    esac
    seen_ids="$seen_ids$normalized_id "
    slot=$((slot + 1))
  done
fi

echo "Compose configuration prerequisites are valid (Workers: $replicas)"
