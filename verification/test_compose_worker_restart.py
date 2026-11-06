import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch

import importlib.util


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "deploy/compose/restart-worker.py"
SPEC = importlib.util.spec_from_file_location("restart_worker", SCRIPT_PATH)
restart_worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(restart_worker)


class ComposeWorkerRestartTest(unittest.TestCase):
    def fixture(self):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        env = root / ".env"
        env.write_text(
            "API_AUTH_ENABLED=true\nWORKER_REPLICAS=1\nCONTROL_PLANE_PORT=18080\n"
            "WORKER_1_AGENT_ID=94b27ea2-263b-4fda-91e2-5d69ebcbb722\n"
        )
        token = root / "oidc-token"
        token.write_text("opaque-test-token\n")
        token.chmod(0o600)
        return temp, env, token

    def test_token_file_must_be_private_and_owned_by_current_user(self):
        temp, _, token = self.fixture()
        self.addCleanup(temp.cleanup)
        self.assertEqual(restart_worker.read_access_token(token), "opaque-test-token")
        token.chmod(0o640)
        with self.assertRaisesRegex(restart_worker.LifecycleError, "mode 0600"):
            restart_worker.read_access_token(token)

    def test_restart_drains_stops_restarts_and_activates_only_after_reconnect(self):
        temp, env, token = self.fixture()
        self.addCleanup(temp.cleanup)
        worker_id = "94b27ea2-263b-4fda-91e2-5d69ebcbb722"
        responses = [
            {"id": worker_id, "phase": "READY", "version": 3},
            {"id": "drain-op", "status": "PENDING"},
            {"id": "drain-op", "status": "DRAINED"},
            {"id": worker_id, "phase": "DRAINING", "version": 4},
            {"id": "activate-op", "type": "ACTIVATE", "status": "SUCCEEDED"},
            {"id": worker_id, "phase": "READY", "version": 4},
        ]
        compose = []
        with patch.object(restart_worker, "request_json", side_effect=responses) as request, \
                patch.object(restart_worker, "compose_command", side_effect=lambda *args: compose.append(args)), \
                patch.object(restart_worker.time, "sleep"):
            restart_worker.run(env, 1, token, 30)
        self.assertEqual([item[2] for item in compose], ["stop", "up"])
        self.assertEqual(compose[0][-1], "worker-1")
        self.assertEqual(compose[1][-3:], ("--no-build", "-d", "worker-1"))
        self.assertEqual(request.call_count, 6)
        self.assertEqual(request.call_args_list[1].args[2], "POST")
        self.assertEqual(request.call_args_list[1].args[3], f"/api/v1/agents/{worker_id}/operations/drain")

    def test_timeout_before_drained_never_stops_worker(self):
        temp, env, token = self.fixture()
        self.addCleanup(temp.cleanup)
        worker_id = "94b27ea2-263b-4fda-91e2-5d69ebcbb722"
        compose = []
        with patch.object(restart_worker, "request_json", side_effect=[
                {"id": worker_id, "phase": "READY", "version": 3},
                {"id": "drain-op", "status": "PENDING"},
                {"id": "drain-op", "status": "PENDING"},
        ]), patch.object(restart_worker, "compose_command", side_effect=lambda *args: compose.append(args)), \
                patch.object(restart_worker.time, "monotonic", side_effect=[0, 20]):
            with self.assertRaisesRegex(restart_worker.LifecycleError, "remains running"):
                restart_worker.run(env, 1, token, 10)
        self.assertEqual(compose, [])

    def test_activate_retries_only_until_gateway_reconnects(self):
        temp, env, token = self.fixture()
        self.addCleanup(temp.cleanup)
        worker_id = "94b27ea2-263b-4fda-91e2-5d69ebcbb722"
        responses = [
            {"id": worker_id, "phase": "READY", "version": 3},
            {"id": "drain-op", "status": "PENDING"},
            {"id": "drain-op", "status": "DRAINED"},
            {"id": worker_id, "phase": "DRAINING", "version": 4},
            restart_worker.LifecycleError("Control Plane returned HTTP 409: WORKER_NOT_ONLINE"),
            {"id": "activate-op", "type": "ACTIVATE", "status": "SUCCEEDED"},
            {"id": worker_id, "phase": "READY", "version": 4},
        ]
        with patch.object(restart_worker, "request_json", side_effect=responses), \
                patch.object(restart_worker, "compose_command"), \
                patch.object(restart_worker.time, "sleep"):
            restart_worker.run(env, 1, token, 30)


if __name__ == "__main__":
    unittest.main()
