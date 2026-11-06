#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${1:-"$script_dir/.env"}
compose_project=${COMPOSE_PROJECT_NAME:-agentteams}
case "$compose_project" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo "Invalid Compose project name" >&2; exit 2 ;; esac
"$script_dir/validate-config.sh" "$env_file"
compose() {
  docker compose -p "$compose_project" --env-file "$env_file" -f "$script_dir/compose.yaml" "$@"
}
deployment_mode=$(awk -F= '$1 == "DEPLOYMENT_MODE" { value=$2 } END { gsub(/[[:space:]]/, "", value); print value }' "$env_file")
replicas=$(awk -F= '$1 == "WORKER_REPLICAS" { value=$2 } END { gsub(/[[:space:]]/, "", value); print value }' "$env_file")
gateway_image=agentteams-new/gateway:v0.1.5-session-bound-artifacts-0156f2c
if [ "$deployment_mode" = "self-hosted-small" ] || [ "$replicas" -gt 0 ]; then
  control_plane_image=$(python3 - "$env_file" <<'PY'
from pathlib import Path
import sys

value = ""
for line in Path(sys.argv[1]).read_text().splitlines():
    key, separator, candidate = line.partition("=")
    if separator and key == "CONTROL_PLANE_IMAGE":
        value = candidate.strip().strip("\"'")
print(value)
PY
  )
  echo "Building the pinned TASK_EVENT-hardened Control Plane from agentteams-new's source patch"
  python3 "$script_dir/build-control-plane-hardening.py" "$control_plane_image"
  echo "Building the pinned session-bound Artifact Gateway from agentteams-new's source patch"
  python3 "$script_dir/build-gateway-hardening.py" "$gateway_image"
fi
if [ "$replicas" -gt 0 ]; then
  worker_image=agentteams-new/agent-worker:v0.1.5-attempt-capability-0156f2c
  echo "Building the pinned Attempt-isolated Worker from agentteams-new's source patch"
  python3 "$script_dir/build-worker-hardening.py" "$worker_image"
fi
storage_enabled=$(awk -F= '$1 == "AGENTTEAMS_STORAGE_ENABLED" { value=$2 } END { gsub(/[[:space:]]/, "", value); print value }' "$env_file")

case "$replicas" in
  ''|*[!0-9]*) echo "WORKER_REPLICAS must be an integer from 0 through 8" >&2; exit 2 ;;
esac
if [ "$replicas" -lt 0 ] || [ "$replicas" -gt 8 ]; then
  echo "WORKER_REPLICAS must be between 0 and 8" >&2
  exit 2
fi
if [ "$storage_enabled" = "true" ]; then
  echo "Starting SeaweedFS S3 and waiting for the endpoint"
  compose --profile storage up -d seaweedfs
  attempt=0
  until compose --profile storage exec -T seaweedfs sh -ec 'code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8333/); [ "$code" -ge 200 ] && [ "$code" -lt 500 ]' >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then echo "SeaweedFS did not become ready within 120 seconds" >&2; exit 1; fi
    sleep 2
  done
fi
if [ "$replicas" -eq 0 ]; then
  echo "WORKER_REPLICAS=0; starting infrastructure without Workers"
  GATEWAY_IMAGE="$gateway_image" compose up -d postgres nats nats-bootstrap control-plane gateway
  exit 0
fi

set -- postgres nats nats-bootstrap control-plane gateway
slot=1
while [ "$slot" -le "$replicas" ]; do
  set -- "$@" "worker-$slot"
  slot=$((slot + 1))
done
GATEWAY_IMAGE="$gateway_image" WORKER_IMAGE="$worker_image" compose --profile secure-api --profile worker-pool up -d "$@" worker-enrollment credential-proxy
