#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <env-file> <backup-directory> <new-compose-project>" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=$1
bundle=$(CDPATH= cd -- "$2" && pwd)
target=$3
compose_file=${COMPOSE_FILE:-$script_dir/compose.yaml}
case "$target" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo "Invalid Compose project name" >&2; exit 2 ;; esac
manifest="$bundle/manifest.json"
if [ ! -r "$manifest" ] || [ ! -s "$bundle/MANIFEST.SHA256" ] \
  || [ ! -s "$bundle/postgres.dump" ] || [ ! -s "$bundle/SHA256SUMS" ]; then
  echo "Backup bundle is incomplete" >&2
  exit 2
fi
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$bundle" && sha256sum -c MANIFEST.SHA256)
else
  (cd "$bundle" && shasum -a 256 -c MANIFEST.SHA256)
fi
python3 - "$manifest" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text())
expected = {"TASK_EVENTS", "AGENT_EVENTS", "CONTROL_EVENTS", "DEADLETTER_EVENTS"}
if manifest.get("format_version") != 1 or set(manifest.get("nats_streams", [])) != expected:
    raise SystemExit("Unsupported or incomplete backup manifest")
archive = manifest.get("seaweedfs_archive")
if archive not in (None, "seaweedfs.tar.gz"):
    raise SystemExit("Unsupported SeaweedFS archive in backup manifest")
if archive and not (Path(sys.argv[1]).parent / archive).is_file():
    raise SystemExit("SeaweedFS archive is missing")
PY
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$bundle" && sha256sum -c SHA256SUMS)
else
  (cd "$bundle" && shasum -a 256 -c SHA256SUMS)
fi

source_project=$(python3 - "$manifest" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text())["source_compose_project"])
PY
)
if [ "$target" = "$source_project" ]; then
  echo "Restore target must be a different, fresh Compose project" >&2
  exit 2
fi
if docker ps -aq --filter "label=com.docker.compose.project=$target" | grep -q .; then
  echo "Restore target already has Compose containers: $target" >&2
  exit 2
fi
if docker network ls -q --filter "label=com.docker.compose.project=$target" | grep -q .; then
  echo "Restore target already has Compose networks: $target" >&2
  exit 2
fi
storage_archive=$(python3 - "$manifest" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text()).get("seaweedfs_archive") or "")
PY
)
volumes="${target}_postgres-data ${target}_nats-data"
if [ -n "$storage_archive" ]; then volumes="$volumes ${target}_seaweedfs-data"; fi
for volume in $volumes; do
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    echo "Restore target volume already exists: $volume" >&2
    exit 2
  fi
done

compose() { docker compose -p "$target" --env-file "$env_file" -f "$compose_file" "$@"; }
restore_complete=false
cleanup() {
  result=$?
  if [ "$restore_complete" != true ]; then
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
compose up -d postgres nats
attempt=0
until compose exec -T postgres pg_isready -U agentteams -d agentteams >/dev/null 2>&1 \
  && compose exec -T nats wget -qO- http://127.0.0.1:8222/healthz 2>/dev/null | grep -q ok; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then echo "Fresh PostgreSQL/NATS did not become ready" >&2; exit 1; fi
  sleep 2
done
compose exec -T postgres sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < "$bundle/postgres.dump"

network="${target}_data"
for stream in TASK_EVENTS AGENT_EVENTS CONTROL_EVENTS DEADLETTER_EVENTS; do
  docker run --rm --network "$network" -v "$bundle/nats:/backup:ro" \
    natsio/nats-box:0.14.0 \
    nats --server nats://nats:4222 stream restore "/backup/$stream"
done
if [ -n "$storage_archive" ]; then
  docker volume create --label "com.docker.compose.project=$target" \
    --label com.docker.compose.volume=seaweedfs-data "${target}_seaweedfs-data" >/dev/null
  docker run --rm -i -v "${target}_seaweedfs-data:/restore" \
    alpine:3.21 tar -C /restore -xzf - < "$bundle/$storage_archive"
fi
restore_complete=true
echo "Restored into fresh project $target. Verify database and stream state before starting application services."
