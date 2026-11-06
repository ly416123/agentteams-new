#!/usr/bin/env python3
"""Drain, restart, and reactivate one fixed Compose Worker slot safely."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent


class LifecycleError(RuntimeError):
    pass


def read_env(path: Path) -> dict[str, str]:
    if not path.is_file() or not os.access(path, os.R_OK):
        raise LifecycleError(f"cannot read environment file: {path}")
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values


def read_access_token(path: Path) -> str:
    try:
        info = path.lstat()
    except OSError as error:
        raise LifecycleError(f"cannot access OIDC access token file: {path}") from error
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        raise LifecycleError("OIDC access token must be a regular, non-symlink file")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise LifecycleError("OIDC access token file must be owned by this user with mode 0600")
    token = path.read_text(encoding="utf-8").strip()
    if not token or "\n" in token:
        raise LifecycleError("OIDC access token file is empty or malformed")
    return token


def request_json(base_url: str, token: str, method: str, path: str,
                 body: dict[str, Any] | None = None, idempotency_key: str | None = None) -> dict[str, Any]:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode("utf-8")
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(f"{base_url.rstrip('/')}{path}", data=payload,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        try:
            detail = json.loads(error.read().decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            detail = {}
        raise LifecycleError(f"Control Plane returned HTTP {error.code}: {detail.get('code', 'request failed')}") from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise LifecycleError(f"Control Plane request failed: {error.reason if hasattr(error, 'reason') else error}") from None
    try:
        value = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as error:
        raise LifecycleError("Control Plane returned invalid JSON") from error
    if not isinstance(value, dict):
        raise LifecycleError("Control Plane returned an unexpected response")
    return value


def compose_command(project: str, env_file: Path, *arguments: str) -> None:
    command = ["docker", "compose", "-p", project, "--env-file", str(env_file),
               "-f", str(SCRIPT_DIR / "compose.yaml"), "--profile", "secure-api",
               "--profile", "worker-pool", *arguments]
    subprocess.run(command, check=True)


def run(env_file: Path, slot: int, token_file: Path, timeout: int) -> None:
    values = read_env(env_file)
    if values.get("API_AUTH_ENABLED") != "true":
        raise LifecycleError("API_AUTH_ENABLED=true is required for Worker lifecycle operations")
    replicas = values.get("WORKER_REPLICAS", "")
    if not replicas.isdecimal() or not 1 <= int(replicas) <= 8 or slot > int(replicas):
        raise LifecycleError("slot must be within the configured active WORKER_REPLICAS range")
    agent_id = values.get(f"WORKER_{slot}_AGENT_ID", "")
    try:
        uuid.UUID(agent_id)
    except ValueError as error:
        raise LifecycleError(f"WORKER_{slot}_AGENT_ID must be a UUID") from error
    project = os.environ.get("COMPOSE_PROJECT_NAME", "agentteams")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project):
        raise LifecycleError("invalid COMPOSE_PROJECT_NAME")
    port = values.get("CONTROL_PLANE_PORT", "8080")
    if not port.isdecimal() or not 1 <= int(port) <= 65535:
        raise LifecycleError("CONTROL_PLANE_PORT must be a TCP port")
    token = read_access_token(token_file)
    base_url = f"http://127.0.0.1:{port}"
    agent_path = f"/api/v1/agents/{agent_id}"
    agent = request_json(base_url, token, "GET", agent_path)
    if agent.get("id") != agent_id or agent.get("phase") not in {"READY", "BUSY"}:
        raise LifecycleError(f"worker is not drainable (phase={agent.get('phase', 'unknown')})")

    drain_key = str(uuid.uuid4())
    drain = request_json(base_url, token, "POST", agent_path + "/operations/drain",
                         {"expectedVersion": agent["version"]}, drain_key)
    operation_id = drain.get("id")
    if not operation_id:
        raise LifecycleError("Drain request did not return an operation ID")
    print(f"Drain requested: operation={operation_id}")

    deadline = time.monotonic() + timeout
    operation_path = f"{agent_path}/operations/{operation_id}"
    while time.monotonic() < deadline:
        operation = request_json(base_url, token, "GET", operation_path)
        status = operation.get("status")
        if status == "DRAINED":
            break
        if status == "FAILED":
            raise LifecycleError("Drain failed; Worker was left running")
        time.sleep(2)
    else:
        raise LifecycleError(f"Drain timed out; Worker remains running. Inspect operation {operation_id} and retry after it reaches DRAINED")

    service = f"worker-{slot}"
    compose_command(project, env_file, "stop", service)
    compose_command(project, env_file, "up", "--no-build", "-d", service)
    print(f"Worker slot restarted: {service}; waiting for Gateway reconnect")

    refreshed = request_json(base_url, token, "GET", agent_path)
    if refreshed.get("phase") != "DRAINING":
        raise LifecycleError("Worker phase changed unexpectedly after restart; manual review required")
    activate_key = str(uuid.uuid4())
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            activated = request_json(base_url, token, "POST", agent_path + "/operations/activate",
                                     {"expectedVersion": refreshed["version"]}, activate_key)
            break
        except LifecycleError as error:
            if "WORKER_NOT_ONLINE" not in str(error):
                raise
            time.sleep(2)
    else:
        raise LifecycleError("Gateway did not reconnect before timeout; Worker remains safely DRAINING")

    if activated.get("type") != "ACTIVATE" or activated.get("status") != "SUCCEEDED":
        raise LifecycleError("Activate did not return a successful operation")
    final = request_json(base_url, token, "GET", agent_path)
    if final.get("phase") != "READY":
        raise LifecycleError(f"Activate succeeded but Worker phase is {final.get('phase', 'unknown')}")
    print(f"Worker slot {service} is READY (activate operation={activated.get('id')})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("slot", type=int, choices=range(1, 9), help="fixed Worker slot number (1..8)")
    parser.add_argument("oidc_token_file", type=Path, help="OIDC access-token file owned by you, mode 0600")
    parser.add_argument("--env-file", type=Path, default=SCRIPT_DIR / ".env")
    parser.add_argument("--timeout", type=int, default=600, help="drain/reconnect timeout in seconds")
    args = parser.parse_args()
    if args.timeout < 10 or args.timeout > 7200:
        parser.error("--timeout must be between 10 and 7200 seconds")
    try:
        run(args.env_file.resolve(), args.slot, args.oidc_token_file, args.timeout)
    except (LifecycleError, subprocess.CalledProcessError) as error:
        print(f"Worker restart stopped safely: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
