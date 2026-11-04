#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${1:-"$script_dir/.env"}
"$script_dir/validate-config.sh" "$env_file"
replicas=$(awk -F= '$1 == "WORKER_REPLICAS" { value=$2 } END { gsub(/[[:space:]]/, "", value); print value }' "$env_file")

case "$replicas" in
  ''|*[!0-9]*) echo "WORKER_REPLICAS must be an integer from 0 through 8" >&2; exit 2 ;;
esac
if [ "$replicas" -lt 0 ] || [ "$replicas" -gt 8 ]; then
  echo "WORKER_REPLICAS must be between 0 and 8" >&2
  exit 2
fi
if [ "$replicas" -eq 0 ]; then
  echo "WORKER_REPLICAS=0; starting infrastructure without Workers"
  exec docker compose --env-file "$env_file" -f "$script_dir/compose.yaml" up -d \
    postgres nats nats-bootstrap control-plane gateway
fi

set -- postgres nats nats-bootstrap control-plane gateway
slot=1
while [ "$slot" -le "$replicas" ]; do
  set -- "$@" "worker-$slot"
  slot=$((slot + 1))
done
exec docker compose --env-file "$env_file" -f "$script_dir/compose.yaml" up -d "$@"
