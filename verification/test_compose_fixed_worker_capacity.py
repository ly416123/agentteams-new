import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
STARTER = ROOT / "deploy/compose/start-workers.sh"
EXAMPLE_ENV = ROOT / "deploy/compose/.env.example"


class ComposeFixedWorkerCapacityTest(unittest.TestCase):
    def invoke_startup(self, replicas: int) -> list[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            values = {}
            for line in EXAMPLE_ENV.read_text().splitlines():
                if line and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key] = value

            model_token = temp / "model-token"
            model_token.write_text("test-only-model-token\n")
            values.update({
                "API_AUTH_ENABLED": "true",
                "OIDC_ISSUER_URI": "https://id.example.test/issuer",
                "OIDC_JWK_SET_URI": "https://id.example.test/keys",
                "OIDC_AUDIENCE": "agentteams-test",
                "POSTGRES_PASSWORD": "test-postgres-password-with-32-chars",
                "WORKER_ENROLLMENT_DB_PASSWORD": "test-enrollment-password-with-32-chars",
                "WORKER_REPLICAS": str(replicas),
                "MODEL_TOKEN_FILE": str(model_token),
            })

            for slot in range(1, replicas + 1):
                credential_file = temp / f"worker-{slot}.runtime.env"
                credential_file.write_text(f"AGENTTEAMS_GATEWAY_AUTH_TOKEN=ats_{'A' * 48}\n")
                credential_file.chmod(0o600)
                values[f"WORKER_{slot}_CREDENTIALS_FILE"] = str(credential_file)

            env_file = temp / ".env"
            env_file.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")

            mock_bin = temp / "bin"
            mock_bin.mkdir()
            call_log = temp / "docker-call.txt"
            docker = mock_bin / "docker"
            docker.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$DOCKER_CALL_LOG\"\n")
            docker.chmod(0o700)

            environment = os.environ.copy()
            environment["PATH"] = f"{mock_bin}:{environment['PATH']}"
            environment["DOCKER_CALL_LOG"] = str(call_log)
            result = subprocess.run(
                [str(STARTER), str(env_file)],
                capture_output=True,
                text=True,
                env=environment,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = call_log.read_text().splitlines()
            self.assertEqual(len(calls), 1, calls)
            return shlex.split(calls[0])

    @staticmethod
    def services_after_up(args: list[str]) -> list[str]:
        up_index = args.index("up")
        detach_index = args.index("-d", up_index)
        return args[detach_index + 1:]

    def test_zero_workers_starts_only_shared_services(self) -> None:
        args = self.invoke_startup(0)
        self.assertEqual(
            self.services_after_up(args),
            ["postgres", "nats", "nats-bootstrap", "control-plane", "gateway"],
        )
        self.assertNotIn("--scale", args)

    def test_starts_exactly_the_configured_worker_slots(self) -> None:
        args = self.invoke_startup(2)
        services = self.services_after_up(args)
        self.assertEqual(
            services,
            ["postgres", "nats", "nats-bootstrap", "control-plane", "gateway", "worker-1", "worker-2", "worker-enrollment"],
        )
        self.assertNotIn("--scale", args)


if __name__ == "__main__":
    unittest.main()
