#!/usr/bin/env sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "Usage: $0 <env-file> <new-backup-directory> [compose-project]" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=$1
bundle=$2
project=${3:-${COMPOSE_PROJECT_NAME:-agentteams}}
compose_file=${COMPOSE_FILE:-$script_dir/compose.yaml}
case "$project" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo "Invalid Compose project name" >&2; exit 2 ;; esac
if [ -e "$bundle" ]; then echo "Backup destination already exists: $bundle" >&2; exit 2; fi
compose() { docker compose -p "$project" --env-file "$env_file" -f "$compose_file" "$@"; }
postgres_id=$(compose ps -q postgres)
nats_id=$(compose ps -q nats)
if [ -z "$postgres_id" ] || [ -z "$nats_id" ]; then
  echo "PostgreSQL and NATS must already be running in the selected Compose project" >&2
  exit 2
fi
running_services=$(compose ps --status running --services)
application_services=$(printf '%s\n' "$running_services" \
  | awk '$0 != "postgres" && $0 != "nats" && $0 != "seaweedfs" && NF')
if [ -n "$application_services" ]; then
  echo "Application services must be stopped before backup: $application_services" >&2
  exit 2
fi
mkdir -p "$(dirname -- "$bundle")"
umask 077
mkdir "$bundle"
mkdir "$bundle/nats"
chmod 0777 "$bundle/nats"
restart_storage=false
cleanup() {
  if [ "$restart_storage" = true ]; then compose --profile storage start seaweedfs >/dev/null 2>&1 || true; fi
  find "$bundle" -type d -exec chmod 700 {} \; 2>/dev/null || true
  find "$bundle" -type f -exec chmod 600 {} \; 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

compose exec -T postgres pg_isready -U agentteams -d agentteams >/dev/null
compose exec -T nats wget -qO- http://127.0.0.1:8222/healthz | grep -q ok

# Pause all task producers/consumers before invoking this script. A single
# quiescent interval is required so the PostgreSQL dump and stream snapshots
# describe the same application state.
compose exec -T postgres sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' \
  > "$bundle/postgres.dump"
chmod 600 "$bundle/postgres.dump"

network="${project}_data"
docker network inspect "$network" >/dev/null
for stream in TASK_EVENTS AGENT_EVENTS CONTROL_EVENTS DEADLETTER_EVENTS; do
  docker run --rm --network "$network" -v "$bundle/nats:/backup" \
    natsio/nats-box:0.14.0 \
    nats --server nats://nats:4222 stream backup --no-progress "$stream" "/backup/$stream"
done
# The NATS container writes root-owned mode-0600 files. Return ownership to
# the invoking user before host-side checksumming and the final permission lock.
docker run --rm -v "$bundle/nats:/backup" alpine:3.21 \
  chown -R "$(id -u):$(id -g)" /backup

storage_enabled=$(awk -F= '$1 == "AGENTTEAMS_STORAGE_ENABLED" { gsub(/[[:space:]]/, "", $2); print $2 }' "$env_file")
storage_archive=false
if [ "$storage_enabled" = true ]; then
  storage_id=$(compose --profile storage ps -q seaweedfs)
  if [ -z "$storage_id" ]; then echo "Artifact storage is enabled but SeaweedFS is not running" >&2; exit 2; fi
  compose --profile storage stop seaweedfs
  restart_storage=true
  docker run --rm -v "${project}_seaweedfs-data:/source:ro" \
    alpine:3.21 tar -C /source -czf - . > "$bundle/seaweedfs.tar.gz"
  storage_archive=true
fi

if command -v sha256sum >/dev/null 2>&1; then
  if [ "$storage_archive" = true ]; then
    (cd "$bundle" && find postgres.dump nats seaweedfs.tar.gz -type f -exec sha256sum {} + > SHA256SUMS)
  else
    (cd "$bundle" && find postgres.dump nats -type f -exec sha256sum {} + > SHA256SUMS)
  fi
else
  if [ "$storage_archive" = true ]; then
    (cd "$bundle" && find postgres.dump nats seaweedfs.tar.gz -type f -exec shasum -a 256 {} + > SHA256SUMS)
  else
    (cd "$bundle" && find postgres.dump nats -type f -exec shasum -a 256 {} + > SHA256SUMS)
  fi
fi
chmod 600 "$bundle/SHA256SUMS"
python3 - "$bundle" "$project" "$storage_archive" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

bundle = Path(sys.argv[1])
manifest = {
    "format_version": 1,
    "source_compose_project": sys.argv[2],
    "created_at": datetime.now(timezone.utc).isoformat(),
    "postgres_dump": "postgres.dump",
    "nats_streams": ["TASK_EVENTS", "AGENT_EVENTS", "CONTROL_EVENTS", "DEADLETTER_EVENTS"],
    "seaweedfs_archive": "seaweedfs.tar.gz" if sys.argv[3] == "true" else None,
    "consistency_requirement": "application producers and consumers were quiesced during backup",
}
(bundle / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(bundle / "manifest.json").chmod(0o600)
PY
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$bundle" && sha256sum manifest.json > MANIFEST.SHA256)
else
  (cd "$bundle" && shasum -a 256 manifest.json > MANIFEST.SHA256)
fi
find "$bundle" -type d -exec chmod 700 {} \;
find "$bundle" -type f -exec chmod 600 {} \;
echo "Backup created at $bundle; verify it before moving the bundle off-host."
