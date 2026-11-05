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

deployment_mode=$(value DEPLOYMENT_MODE)
case "$deployment_mode" in
  local-dev|self-hosted-small) ;;
  *) echo "DEPLOYMENT_MODE must be local-dev or self-hosted-small" >&2; exit 2 ;;
esac

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
if [ "$deployment_mode" != "local-dev" ] && [ "$api_auth_enabled" != "true" ]; then
  echo "API_AUTH_ENABLED=true is required outside local-dev" >&2
  exit 2
fi
if [ "$deployment_mode" = "self-hosted-small" ]; then
  control_plane_source_build=$(value CONTROL_PLANE_BUILD_FROM_SOURCE)
  control_plane_image=$(value CONTROL_PLANE_IMAGE)
  if [ "$control_plane_source_build" != "true" ] \
      || [ "$control_plane_image" != "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c" ]; then
    echo "self-hosted-small requires the pinned TASK_EVENT-hardened Control Plane source build" >&2
    exit 2
  fi
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
  credential_proxy_db_password=$(value CREDENTIAL_PROXY_DB_PASSWORD)
  if [ "${#credential_proxy_db_password}" -lt 24 ] \
      || [ "$credential_proxy_db_password" = "replace-with-a-unique-credential-proxy-db-password" ]; then
    echo "Set a unique CREDENTIAL_PROXY_DB_PASSWORD of at least 24 characters" >&2
    exit 2
  fi
fi

control_plane_db_password=$(value CONTROL_PLANE_DB_PASSWORD)
if [ "${#control_plane_db_password}" -lt 24 ] \
    || [ "$control_plane_db_password" = "replace-with-a-unique-control-plane-db-password" ]; then
  echo "Set a unique CONTROL_PLANE_DB_PASSWORD of at least 24 characters" >&2
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
  if [ "$key" = "CONTROL_PLANE_IMAGE" ] \
      && [ "$deployment_mode" = "self-hosted-small" ] \
      && [ "$(value CONTROL_PLANE_BUILD_FROM_SOURCE)" = "true" ] \
      && [ "$image" = "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c" ]; then
    continue
  fi
  case "$image" in ghcr.io/ly416123/*@sha256:*) ;; *) echo "$key must reference ghcr.io/ly416123 by digest" >&2; exit 2 ;; esac
  digest=${image##*@sha256:}
  if [ "$image" = "$digest" ] || [ "${#digest}" -ne 64 ]; then
    echo "$key must be an immutable GHCR image reference ending in @sha256:<64 hex chars>" >&2
    exit 2
  fi
  case "$digest" in *[!0123456789abcdefABCDEF]*) echo "$key has an invalid sha256 digest" >&2; exit 2 ;; esac
done

if [ "$replicas" -gt 0 ]; then
  slot=1
  seen_ids=" "
  seen_proxy_tokens=" "
  proxy_uid=$(value CREDENTIAL_PROXY_UID)
  proxy_uid=${proxy_uid:-10001}
  proxy_gid=$(value CREDENTIAL_PROXY_GID)
  proxy_gid=${proxy_gid:-10001}
  case "$proxy_uid" in ''|*[!0-9]*) echo "CREDENTIAL_PROXY_UID must be a numeric user ID" >&2; exit 2 ;; esac
  case "$proxy_gid" in ''|*[!0-9]*) echo "CREDENTIAL_PROXY_GID must be a numeric group ID" >&2; exit 2 ;; esac
  provider_root=$(value PROVIDER_CREDENTIALS_DIR)
  if [ -z "$provider_root" ]; then echo "PROVIDER_CREDENTIALS_DIR is required when Workers are enabled" >&2; exit 2; fi
  case "$provider_root" in
    /*) ;;
    *) provider_root="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd)/$provider_root" ;;
  esac
  if [ ! -d "$provider_root" ] || [ -L "$provider_root" ]; then
    echo "PROVIDER_CREDENTIALS_DIR must be an existing non-symlink directory" >&2
    exit 2
  fi
  if provider_root_mode=$(stat -f '%Lp' "$provider_root" 2>/dev/null); then :; else provider_root_mode=$(stat -c '%a' "$provider_root"); fi
  if [ "$((0$provider_root_mode & 077))" -ne 0 ]; then
    echo "Provider credential directory must not be accessible by group or other users" >&2
    exit 2
  fi
  if provider_root_owner=$(stat -f '%u' "$provider_root" 2>/dev/null); then :; else provider_root_owner=$(stat -c '%u' "$provider_root"); fi
  if [ "$provider_root_owner" != "$proxy_uid" ]; then
    echo "Provider credential directory owner must match CREDENTIAL_PROXY_UID" >&2
    exit 2
  fi
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
    model_proxy_file=$(value "WORKER_${slot}_MODEL_PROXY_TOKEN_FILE")
    case "$model_proxy_file" in
      /*) ;;
      *) model_proxy_file="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd)/$model_proxy_file" ;;
    esac
    if [ ! -r "$model_proxy_file" ] || [ ! -s "$model_proxy_file" ]; then
      echo "WORKER_${slot}_MODEL_PROXY_TOKEN_FILE must point to a readable, non-empty proxy token file" >&2
      exit 2
    fi
    if model_proxy_mode=$(stat -f '%Lp' "$model_proxy_file" 2>/dev/null); then :; else model_proxy_mode=$(stat -c '%a' "$model_proxy_file"); fi
    if [ "$((0$model_proxy_mode & 077))" -ne 0 ]; then
      echo "Worker model proxy token file must not be readable by group or other users" >&2
      exit 2
    fi
    if model_proxy_owner=$(stat -f '%u' "$model_proxy_file" 2>/dev/null); then :; else model_proxy_owner=$(stat -c '%u' "$model_proxy_file"); fi
    if [ "$model_proxy_owner" != "$proxy_uid" ]; then
      echo "Worker model proxy token file owner must match CREDENTIAL_PROXY_UID" >&2
      exit 2
    fi
    proxy_token=$(cat "$model_proxy_file")
    case "$proxy_token" in atp_*) proxy_token_body=${proxy_token#atp_} ;; *) echo "Worker model proxy token file must contain an enrolled atp_ token" >&2; exit 2 ;; esac
    case "$proxy_token_body" in ''|*[!A-Za-z0-9_-]*) echo "Worker model proxy token file contains an invalid token" >&2; exit 2 ;; esac
    if [ "${#proxy_token_body}" -ne 43 ]; then echo "Worker model proxy token file contains a malformed token" >&2; exit 2; fi
    case "$seen_proxy_tokens" in
      *" $proxy_token "*) echo "Each active Worker must use a distinct model proxy token" >&2; exit 2 ;;
    esac
    seen_proxy_tokens="$seen_proxy_tokens$proxy_token "
    unset proxy_token proxy_token_body
    model_credential_ref=$(value "WORKER_${slot}_MODEL_CREDENTIAL_REF")
    if ! python3 - "$provider_root" "$model_credential_ref" "$proxy_uid" <<'PY'
import re
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
reference = sys.argv[2]
expected_uid = int(sys.argv[3])
if not reference.startswith("file:"):
    raise SystemExit(1)
parts = reference[5:].split("/")
if len(parts) < 4 or any(not re.fullmatch(r"[A-Za-z0-9_-]+", part) for part in parts):
    raise SystemExit(1)
resolved_root = root.resolve(strict=True)
target = resolved_root.joinpath(*parts)
cursor = resolved_root
for part in parts:
    cursor = cursor / part
    if cursor.is_symlink():
        raise SystemExit(1)
if not target.is_file():
    raise SystemExit(1)
target_stat = target.stat()
for directory in (resolved_root, *reversed(target.parents)):
    if not directory.is_relative_to(resolved_root):
        continue
    directory_stat = directory.stat()
    if directory_stat.st_uid != expected_uid or stat.S_IMODE(directory_stat.st_mode) & 0o077:
        raise SystemExit(1)
if target_stat.st_uid != expected_uid or stat.S_IMODE(target_stat.st_mode) & 0o077:
    raise SystemExit(1)
if not 0 < target_stat.st_size <= 64 * 1024:
    raise SystemExit(1)
PY
    then
      echo "WORKER_${slot}_MODEL_CREDENTIAL_REF must resolve to an owner-only file under the matching tenant/project path" >&2
      exit 2
    fi
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
