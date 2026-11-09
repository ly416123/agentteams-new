#!/usr/bin/env sh
set -eu

if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
  echo "Usage: $0 <enrollment-base-url> <slot-uuid> <oidc-token-file> <worker-env-file> [ttl-seconds]" >&2
  exit 2
fi

base_url=$1
slot_id=$2
oidc_token_file=$3
worker_env_file=$4
ttl_seconds=${5:-604800}
if ! printf '%s\n' "$slot_id" | awk '/^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/ { ok=1 } END { exit !ok }'; then
  echo "slot-uuid must be a UUID" >&2
  exit 2
fi
case "$base_url" in https://*|http://*) ;; *) echo "base-url must use HTTP or HTTPS" >&2; exit 2 ;; esac
case "$ttl_seconds" in ''|*[!0-9]*) echo "ttl-seconds must be an integer" >&2; exit 2 ;; esac
if [ "$ttl_seconds" -lt 3600 ] || [ "$ttl_seconds" -gt 2592000 ]; then
  echo "ttl-seconds must be between 3600 and 2592000" >&2
  exit 2
fi
if [ ! -r "$oidc_token_file" ] || [ ! -s "$oidc_token_file" ]; then
  echo "OIDC token file must be readable and non-empty" >&2
  exit 2
fi

parent_dir=$(dirname -- "$worker_env_file")
mkdir -p "$parent_dir"
chmod 700 "$parent_dir"
output_dir=$(CDPATH= cd -- "$parent_dir" && pwd)
output_path="$output_dir/$(basename -- "$worker_env_file")"
umask 077
auth_file=$(mktemp "$output_dir/.worker-slot-auth.XXXXXX")
response_file=$(mktemp "$output_dir/.worker-slot-response.XXXXXX")
output_tmp=$(mktemp "$output_dir/.worker-slot-runtime.XXXXXX")
trap 'rm -f "$auth_file" "$response_file" "$output_tmp"' EXIT HUP INT TERM
oidc_token=$(cat "$oidc_token_file")
printf 'Authorization: Bearer %s\nAccept: application/json\n' "$oidc_token" > "$auth_file"
unset oidc_token
request_body=$(jq -cn --argjson ttl "$ttl_seconds" '{ttlSeconds:$ttl}')
curl --silent --show-error --fail --max-time 30 \
  --header "@$auth_file" \
  --header 'Content-Type: application/json' \
  --data "$request_body" \
  --output "$response_file" \
  "$base_url/api/v1/worker-slots/$slot_id/sessions"

session_id=$(jq -er '.sessionId' "$response_file")
access_token=$(jq -er '.accessToken' "$response_file")
case "$access_token" in
  wslot_*) ;;
  *) echo "Enrollment API returned an invalid Worker slot token" >&2; exit 1 ;;
esac
token_body=${access_token#wslot_}
case "$token_body" in ''|*[!A-Za-z0-9_-]*) echo "Enrollment API returned an invalid Worker slot token" >&2; exit 1 ;; esac
if [ "${#token_body}" -lt 43 ]; then echo "Enrollment API returned a short Worker slot token" >&2; exit 1; fi
printf 'AGENTTEAMS_GATEWAY_AUTH_TOKEN=%s\n' "$access_token" > "$output_tmp"
chmod 600 "$output_tmp"
mv -f "$output_tmp" "$output_path"
unset access_token
echo "Issued Worker slot session $session_id for $slot_id. Gateway credential saved to $output_path with mode 0600; token was not printed."
