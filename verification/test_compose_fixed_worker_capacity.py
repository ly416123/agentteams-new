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
    def invoke_startup(
        self,
        replicas: int,
        *,
        shared_proxy_tokens: bool = False,
        provider_secret_mode: int = 0o600,
        expected_error: str | None = None,
        project_name: str | None = None,
        deployment_mode: str = "local-dev",
    ) -> list[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            values = {}
            for line in EXAMPLE_ENV.read_text().splitlines():
                if line and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key] = value

            provider_root = temp / "provider-secrets"
            provider_file = provider_root / "tenant-a/project-a/team-a/chatgpt"
            provider_file.parent.mkdir(parents=True)
            provider_file.write_text("test-only-provider-token\n")
            provider_root.chmod(0o700)
            (provider_root / "tenant-a").chmod(0o700)
            (provider_root / "tenant-a/project-a").chmod(0o700)
            (provider_root / "tenant-a/project-a/team-a").chmod(0o700)
            provider_file.chmod(provider_secret_mode)
            values.update({
                "DEPLOYMENT_MODE": deployment_mode,
                "API_AUTH_ENABLED": "true",
                "OIDC_ISSUER_URI": "https://id.example.test/issuer",
                "OIDC_JWK_SET_URI": "https://id.example.test/keys",
                "OIDC_AUDIENCE": "agentteams-test",
                "POSTGRES_PASSWORD": "test-postgres-password-with-32-chars",
                "CONTROL_PLANE_DB_PASSWORD": "test-control-plane-password-with-32-chars",
                "WORKER_ENROLLMENT_DB_PASSWORD": "test-enrollment-password-with-32-chars",
                "CREDENTIAL_PROXY_DB_PASSWORD": "test-credential-proxy-password-with-32-chars",
                "CREDENTIAL_PROXY_UID": str(os.getuid()),
                "CREDENTIAL_PROXY_GID": str(os.getgid()),
                "PROVIDER_CREDENTIALS_DIR": str(provider_root),
                "WORKER_REPLICAS": str(replicas),
            })
            if deployment_mode == "self-hosted-small":
                values["CONTROL_PLANE_BUILD_FROM_SOURCE"] = "true"
                values["CONTROL_PLANE_IMAGE"] = (
                    "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c"
                )

            for slot in range(1, replicas + 1):
                slot_proxy_token = temp / f"worker-{slot}.model-proxy-token"
                slot_proxy_token.write_text(f"atp_{chr(64 + slot) * 43}\n")
                slot_proxy_token.chmod(0o600)
                values[f"WORKER_{slot}_MODEL_PROXY_TOKEN_FILE"] = str(
                    temp / "worker-1.model-proxy-token" if shared_proxy_tokens else slot_proxy_token
                )
                values[f"WORKER_{slot}_MODEL_CREDENTIAL_REF"] = "file:tenant-a/project-a/team-a/chatgpt"
                credential_file = temp / f"worker-{slot}.runtime.env"
                credential_file.write_text(f"AGENTTEAMS_GATEWAY_AUTH_TOKEN=ats_{'A' * 48}\n")
                credential_file.chmod(0o600)
                values[f"WORKER_{slot}_CREDENTIALS_FILE"] = str(credential_file)

            env_file = temp / ".env"
            env_file.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")

            mock_bin = temp / "bin"
            mock_bin.mkdir()
            call_log = temp / "docker-call.txt"
            call_order_log = temp / "call-order.txt"
            docker = mock_bin / "docker"
            docker.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$DOCKER_CALL_LOG\"\n"
                "printf 'docker\\n' >> \"$CALL_ORDER_LOG\"\n"
            )
            docker.chmod(0o700)
            if deployment_mode == "self-hosted-small":
                python = mock_bin / "python3"
                python.write_text(
                    "#!/bin/sh\n"
                    "if [ \"$1\" = \"-\" ]; then\n"
                    "  sed -n 's/^CONTROL_PLANE_IMAGE=//p' \"$2\" | tail -1\n"
                    "else\n"
                    "  printf 'builder\\n' >> \"$CALL_ORDER_LOG\"\n"
                    "fi\n"
                )
                python.chmod(0o700)

            environment = os.environ.copy()
            environment["PATH"] = f"{mock_bin}:{environment['PATH']}"
            environment["DOCKER_CALL_LOG"] = str(call_log)
            environment["CALL_ORDER_LOG"] = str(call_order_log)
            if project_name is not None:
                environment["COMPOSE_PROJECT_NAME"] = project_name
            result = subprocess.run(
                [str(STARTER), str(env_file)],
                capture_output=True,
                text=True,
                env=environment,
                check=False,
            )
            if expected_error is not None:
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(expected_error, result.stderr)
                return []
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = call_log.read_text().splitlines()
            self.assertEqual(len(calls), 1, calls)
            if deployment_mode == "self-hosted-small":
                self.assertEqual(call_order_log.read_text().splitlines(), ["builder", "docker"])
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

    def test_can_target_an_isolated_restored_compose_project(self) -> None:
        args = self.invoke_startup(0, project_name="agentteams-restore")
        self.assertEqual(args[1:3], ["-p", "agentteams-restore"])

    def test_builds_hardened_control_plane_before_self_hosted_startup(self) -> None:
        args = self.invoke_startup(0, deployment_mode="self-hosted-small")
        self.assertEqual(
            self.services_after_up(args),
            ["postgres", "nats", "nats-bootstrap", "control-plane", "gateway"],
        )

    def test_starts_exactly_the_configured_worker_slots(self) -> None:
        args = self.invoke_startup(2)
        services = self.services_after_up(args)
        self.assertEqual(
            services,
            ["postgres", "nats", "nats-bootstrap", "control-plane", "gateway", "worker-1", "worker-2", "worker-enrollment", "credential-proxy"],
        )
        self.assertNotIn("--scale", args)

    def test_rejects_model_proxy_token_shared_between_worker_slots(self) -> None:
        self.invoke_startup(
            2,
            shared_proxy_tokens=True,
            expected_error="Each active Worker must use a distinct model proxy token",
        )

    def test_rejects_provider_credential_file_readable_by_other_users(self) -> None:
        self.invoke_startup(
            1,
            provider_secret_mode=0o644,
            expected_error="must resolve to an owner-only file under the matching tenant/project path",
        )

    def test_workers_only_receive_model_proxy_tokens(self) -> None:
        compose = (ROOT / "deploy/compose/compose.yaml").read_text()
        for slot in range(1, 9):
            secret_name = f"model_proxy_token_worker_{slot}"
            self.assertIn(f"AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE: /run/secrets/{secret_name}", compose)
            self.assertIn(f"secrets: [{secret_name}]", compose)
            self.assertRegex(
                compose,
                rf"{secret_name}:\n\s+file: \$\{{WORKER_{slot}_MODEL_PROXY_TOKEN_FILE:\?set WORKER_{slot}_MODEL_PROXY_TOKEN_FILE",
            )
        self.assertIn("AGENTTEAMS_CHATGPT_RESPONSES_URL: http://credential-proxy:8085/v1/responses", compose)
        self.assertIn("PROVIDER_CREDENTIALS_DIR:?set PROVIDER_CREDENTIALS_DIR", compose)
        self.assertNotIn("model_token_worker_", compose)
        self.assertRegex(compose, r"worker-egress:\n\s+internal: true")
        self.assertRegex(compose, r"provider-egress:\n\s+driver: bridge")

    def test_credential_proxy_build_mounts_maven_settings_as_a_build_secret(self) -> None:
        compose = (ROOT / "deploy/compose/compose.yaml").read_text()
        credential_proxy = compose.split("\n  credential-proxy:\n", 1)[1].split("\n  worker-enrollment:\n", 1)[0]
        self.assertIn("      secrets: [maven_settings]", credential_proxy)

    def test_worker_enrollment_is_host_reachable_and_builds_with_maven_settings(self) -> None:
        compose = (ROOT / "deploy/compose/compose.yaml").read_text()
        enrollment = compose.split("\n  worker-enrollment:\n", 1)[1].split("\n  gateway:\n", 1)[0]
        dockerfile = (ROOT / "worker-enrollment/Dockerfile").read_text()
        self.assertIn('"127.0.0.1:${ENROLLMENT_PORT:-8090}:8090"', enrollment)
        self.assertIn("    networks: [data, host-ingress]", enrollment)
        self.assertIn("      secrets: [maven_settings]", enrollment)
        self.assertIn("COPY credential-proxy/pom.xml ./credential-proxy/pom.xml", dockerfile)
        self.assertIn("--mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml", dockerfile)
        self.assertRegex(compose, r"maven_settings:\n\s+file: \$\{MAVEN_SETTINGS_FILE:-/dev/null\}")


if __name__ == "__main__":
    unittest.main()
