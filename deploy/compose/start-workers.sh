#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${1:-"$script_dir/.env"}
"$script_dir/validate-config.sh" "$env_file"
compose() {
  docker compose --env-file "$env_file" -f "$script_dir/compose.yaml" "$@"
}
replicas=$(awk -F= '$1 == "WORKER_REPLICAS" { value=$2 } END { gsub(/[[:space:]]/, "", value); print value }' "$env_file")
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
  compose up -d postgres nats nats-bootstrap control-plane gateway
  exit 0
fi

set -- postgres nats nats-bootstrap control-plane gateway
slot=1
while [ "$slot" -le "$replicas" ]; do
  set -- "$@" "worker-$slot"
  slot=$((slot + 1))
done
compose up -d "$@"
