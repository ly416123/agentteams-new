#!/usr/bin/env sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <enrollment-base-url> <agent-uuid> <session-uuid> <oidc-token-file>" >&2
  exit 2
fi

base_url=$1
agent_id=$2
session_id=$3
oidc_token_file=$4
for uuid in "$agent_id" "$session_id"; do
  if ! printf '%s\n' "$uuid" | awk '/^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/ { ok=1 } END { exit !ok }'; then
    echo "agent-uuid and session-uuid must be UUIDs" >&2
    exit 2
  fi
done
case "$base_url" in https://*|http://*) ;; *) echo "base-url must use HTTP or HTTPS" >&2; exit 2 ;; esac
if [ ! -r "$oidc_token_file" ] || [ ! -s "$oidc_token_file" ]; then
  echo "OIDC token file must be readable and non-empty" >&2
  exit 2
fi

tmp_dir=${TMPDIR:-/tmp}
umask 077
auth_file=$(mktemp "$tmp_dir/worker-revoke-auth.XXXXXX")
trap 'rm -f "$auth_file"' EXIT HUP INT TERM
oidc_token=$(cat "$oidc_token_file")
printf 'Authorization: Bearer %s\n' "$oidc_token" > "$auth_file"
unset oidc_token
curl --silent --show-error --fail --max-time 30 --request DELETE \
  --header "@$auth_file" \
  "$base_url/api/v1/agents/$agent_id/sessions/$session_id"
echo "Revoked Worker session $session_id for future Gateway connections. An already connected Worker is not disconnected by this endpoint."
