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

api_auth_enabled=$(value API_AUTH_ENABLED)
api_auth_enabled=${api_auth_enabled:-false}
case "$api_auth_enabled" in
  true|false) ;;
  *) echo "API_AUTH_ENABLED must be true or false" >&2; exit 2 ;;
esac
if [ "$replicas" -gt 0 ] && [ "$api_auth_enabled" != "true" ]; then
  echo "API_AUTH_ENABLED=true is required before starting Workers" >&2
  exit 2
fi
if [ "$replicas" -gt 0 ]; then
  enrollment_port=$(value ENROLLMENT_PORT)
  enrollment_port=${enrollment_port:-8090}
  case "$enrollment_port" in *[!0-9]*|'') echo "ENROLLMENT_PORT must be a TCP port number" >&2; exit 2 ;; esac
  if [ "$enrollment_port" -lt 1 ] || [ "$enrollment_port" -gt 65535 ]; then
    echo "ENROLLMENT_PORT must be between 1 and 65535" >&2
    exit 2
  fi
fi
if [ "$api_auth_enabled" = "true" ]; then
  for key in OIDC_ISSUER_URI OIDC_JWK_SET_URI; do
    oidc_value=$(value "$key")
    case "$oidc_value" in
      https://*) ;;
      http://*) echo "$key must use HTTPS when API_AUTH_ENABLED=true" >&2; exit 2 ;;
      *) echo "$key must be an absolute HTTPS URL when API_AUTH_ENABLED=true" >&2; exit 2 ;;
    esac
  done
  audience=$(value OIDC_AUDIENCE)
  if [ -z "$audience" ]; then echo "OIDC_AUDIENCE is required when API_AUTH_ENABLED=true" >&2; exit 2; fi
  enrollment_db_password=$(value WORKER_ENROLLMENT_DB_PASSWORD)
  if [ "${#enrollment_db_password}" -lt 24 ] \
      || [ "$enrollment_db_password" = "replace-with-a-unique-enrollment-db-password" ]; then
    echo "Set a unique WORKER_ENROLLMENT_DB_PASSWORD of at least 24 characters" >&2
    exit 2
  fi
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
    credential_file=$(value "WORKER_${slot}_CREDENTIALS_FILE")
    case "$credential_file" in
      /*) ;;
      *) credential_file="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd)/$credential_file" ;;
    esac
    if [ ! -r "$credential_file" ] || ! grep -Eq '^AGENTTEAMS_GATEWAY_AUTH_TOKEN=ats_[A-Za-z0-9_-]{40,}$' "$credential_file"; then
      echo "WORKER_${slot}_CREDENTIALS_FILE must contain an enrolled Gateway token" >&2
      exit 2
    fi
    if mode=$(stat -f '%Lp' "$credential_file" 2>/dev/null); then :; else mode=$(stat -c '%a' "$credential_file"); fi
    if [ "$((0$mode & 077))" -ne 0 ]; then
      echo "Worker credential file must not be readable by group or other users: $credential_file" >&2
      exit 2
    fi
    slot=$((slot + 1))
  done
fi

echo "Compose configuration prerequisites are valid (Workers: $replicas)"
