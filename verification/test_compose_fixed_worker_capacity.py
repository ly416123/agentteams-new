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
    def test_worker_pool_uses_attempt_scoped_credentials_from_proxy(self) -> None:
        compose = (ROOT / "deploy/compose/compose.yaml").read_text()
        example_env = EXAMPLE_ENV.read_text()
        self.assertIn("AGENTTEAMS_ATTEMPT_CAPABILITY_ISSUE_URL: http://credential-proxy:8085/internal/v1/attempt-capabilities", compose)
        self.assertNotIn("model_proxy_token_worker_", compose)
        self.assertIn("AGENTTEAMS_TENANT_CREDENTIAL_REFS_JSON=", example_env)
        self.assertIn("jsonb_to_recordset", compose)

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
                "AGENTTEAMS_TENANT_CREDENTIAL_REFS_JSON": (
                    '[{"tenant_id":"tenant-a","project_id":"project-a","team_id":"team-a",'
                    '"model_credential_ref":"file:tenant-a/project-a/team-a/chatgpt"}]'
                ),
                "WORKER_REPLICAS": str(replicas),
            })
            if deployment_mode == "self-hosted-small":
                values["CONTROL_PLANE_BUILD_FROM_SOURCE"] = "true"
                values["CONTROL_PLANE_IMAGE"] = (
                    "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c"
                )
            if replicas > 0:
                values["CONTROL_PLANE_BUILD_FROM_SOURCE"] = "true"
                values["CONTROL_PLANE_IMAGE"] = (
                    "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c"
                )
                values["WORKER_BUILD_FROM_SOURCE"] = "true"

            for slot in range(1, replicas + 1):
                slot_proxy_token = temp / f"worker-{slot}.model-proxy-token"
                slot_proxy_token.write_text(f"atp_{chr(64 + slot) * 43}\n")
                slot_proxy_token.chmod(0o600)
                values[f"WORKER_{slot}_MODEL_PROXY_TOKEN_FILE"] = str(
                    temp / "worker-1.model-proxy-token" if shared_proxy_tokens else slot_proxy_token
                )
                values[f"WORKER_{slot}_MODEL_CREDENTIAL_REF"] = "file:tenant-a/project-a/team-a/chatgpt"
                credential_file = temp / f"worker-{slot}.runtime.env"
                credential_file.write_text(f"AGENTTEAMS_GATEWAY_AUTH_TOKEN=wslot_{'A' * 43}\n")
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
            if deployment_mode == "self-hosted-small" or replicas > 0:
                python = mock_bin / "python3"
                python.write_text(
                    "#!/bin/sh\n"
                    "case \"$1\" in\n"
                    "  -) if [ -f \"$2\" ] && grep -q '^CONTROL_PLANE_IMAGE=' \"$2\"; then sed -n 's/^CONTROL_PLANE_IMAGE=//p' \"$2\" | tail -1; else exec "
                    + shlex.quote(os.sys.executable) + " \"$@\"; fi;;\n"
                    "  */build-control-plane-hardening.py) echo control-plane-builder >> \"$CALL_ORDER_LOG\";;\n"
                    "  */build-gateway-hardening.py) echo gateway-builder >> \"$CALL_ORDER_LOG\";;\n"
                    "  */build-worker-hardening.py) echo worker-builder >> \"$CALL_ORDER_LOG\";;\n"
                    "  *) exec " + shlex.quote(os.sys.executable) + " \"$@\";;\n"
                    "esac\n"
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
            if deployment_mode == "self-hosted-small" or replicas > 0:
                order = call_order_log.read_text().splitlines()
                self.assertEqual(order[-1], "docker")
                expected = (["control-plane-builder", "gateway-builder", "worker-builder"]
                            if replicas > 0 else ["control-plane-builder", "gateway-builder"])
                self.assertEqual(order[:-1], expected)
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

    def test_builds_both_hardened_images_before_worker_startup(self) -> None:
        # The launcher must run both image gates before invoking Compose.
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            values = {}
            for line in EXAMPLE_ENV.read_text().splitlines():
                if line and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key] = value
            values.update({
                "WORKER_REPLICAS": "1", "API_AUTH_ENABLED": "true",
                "OIDC_ISSUER_URI": "https://id.example.test/issuer",
                "OIDC_JWK_SET_URI": "https://id.example.test/keys", "OIDC_AUDIENCE": "test",
                "POSTGRES_PASSWORD": "test-postgres-password-with-32-chars",
                "CONTROL_PLANE_DB_PASSWORD": "test-control-plane-password-with-32-chars",
                "WORKER_ENROLLMENT_DB_PASSWORD": "test-enrollment-password-with-32-chars",
                "CREDENTIAL_PROXY_DB_PASSWORD": "test-credential-proxy-password-with-32-chars",
                "AGENTTEAMS_TENANT_CREDENTIAL_REFS_JSON": (
                    '[{"tenant_id":"tenant-a","project_id":"project-a","team_id":"team-a",'
                    '"model_credential_ref":"file:tenant-a/project-a/team-a/chatgpt"}]'
                ),
                "CONTROL_PLANE_BUILD_FROM_SOURCE": "true",
                "CONTROL_PLANE_IMAGE": "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c",
                "WORKER_BUILD_FROM_SOURCE": "true",
                "WORKER_IMAGE": "ghcr.io/ly416123/agentteams-agent-worker@sha256:0aec65e942a9ecfa726032e560066d91266905dc878fd4a610d533db0118b86d",
            })
            for slot in range(1, 2):
                provider = temp / "provider-secrets/tenant-a/project-a/team-a/chatgpt"
                provider.parent.mkdir(parents=True, exist_ok=True)
                provider.write_text("test")
                provider.chmod(0o600)
                for folder in [provider.parent.parent.parent, provider.parent.parent, provider.parent]:
                    folder.chmod(0o700)
                token = temp / "proxy-token"
                token.write_text("atp_" + "A" * 43)
                token.chmod(0o600)
                values.update({
                    "PROVIDER_CREDENTIALS_DIR": str(provider.parents[2]),
                    "CREDENTIAL_PROXY_UID": str(os.getuid()),
                    f"WORKER_{slot}_MODEL_PROXY_TOKEN_FILE": str(token),
                    f"WORKER_{slot}_MODEL_CREDENTIAL_REF": "file:tenant-a/project-a/team-a/chatgpt",
                    f"WORKER_{slot}_AGENT_ID": "00000000-0000-0000-0000-000000000001",
                    f"WORKER_{slot}_CREDENTIALS_FILE": str(temp / "worker.runtime.env"),
                })
                runtime = temp / "worker.runtime.env"
                runtime.write_text("AGENTTEAMS_GATEWAY_AUTH_TOKEN=wslot_" + "A" * 43 + "\n")
                runtime.chmod(0o600)
            env = temp / ".env"
            env.write_text("\n".join(f"{k}={v}" for k, v in values.items()) + "\n")
            mock_bin = temp / "bin"
            mock_bin.mkdir()
            log = temp / "calls"
            (mock_bin / "docker").write_text("#!/bin/sh\necho docker >> \"$CALL_ORDER_LOG\"\n")
            (mock_bin / "docker").chmod(0o700)
            (mock_bin / "python3").write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$1\" >> \"$CALL_ORDER_LOG\"\n"
                "case \"$1\" in\n"
                "  -) if [ -f \"$2\" ]; then sed -n 's/^CONTROL_PLANE_IMAGE=//p' \"$2\" | tail -1; fi;;\n"
                "esac\n")
            (mock_bin / "python3").chmod(0o700)
            environment = os.environ.copy()
            environment.update(PATH=f"{mock_bin}:{environment['PATH']}", CALL_ORDER_LOG=str(log))
            result = subprocess.run([str(STARTER), str(env)], capture_output=True, text=True, env=environment)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = log.read_text().splitlines()
            self.assertEqual(calls[-1], "docker")
            builders = [call.rsplit("/", 1)[-1] for call in calls[:-1] if call != "-"]
            self.assertEqual(builders, ["build-control-plane-hardening.py", "build-gateway-hardening.py",
                                        "build-worker-hardening.py"])

    def test_starts_exactly_the_configured_worker_slots(self) -> None:
        args = self.invoke_startup(2)
        services = self.services_after_up(args)
        self.assertEqual(
            services,
            ["postgres", "nats", "nats-bootstrap", "control-plane", "gateway", "worker-1", "worker-2", "worker-enrollment", "credential-proxy"],
        )
        self.assertNotIn("--scale", args)

    def test_rejects_stock_images_for_worker_pool(self) -> None:
        # Remove the required derived images after the helper has set secure OIDC inputs.
        # Direct config validation reports the first missing hardening contract.
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            for line in EXAMPLE_ENV.read_text().splitlines():
                if line and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    if key == "WORKER_REPLICAS": value = "1"
                    if key == "API_AUTH_ENABLED": value = "true"
                    if key == "OIDC_ISSUER_URI": value = "https://id.example.test/issuer"
                    if key == "OIDC_JWK_SET_URI": value = "https://id.example.test/keys"
                    if key == "OIDC_AUDIENCE": value = "agentteams-test"
                    if key == "POSTGRES_PASSWORD": value = "test-postgres-password-with-32-chars"
                    if key == "CONTROL_PLANE_DB_PASSWORD": value = "test-control-plane-password-with-32-chars"
                    if key == "WORKER_ENROLLMENT_DB_PASSWORD": value = "test-enrollment-password-with-32-chars"
                    if key == "CREDENTIAL_PROXY_DB_PASSWORD": value = "test-credential-proxy-password-with-32-chars"
                    if key == "CREDENTIAL_PROXY_UID": value = str(os.getuid())
                    if key == "CREDENTIAL_PROXY_GID": value = str(os.getgid())
                    if key == "AGENTTEAMS_TENANT_CREDENTIAL_REFS_JSON":
                        value = ('[{"tenant_id":"tenant-a","project_id":"project-a","team_id":"team-a",'
                                 '"model_credential_ref":"file:tenant-a/project-a/team-a/chatgpt"}]')
                    if key == "PROVIDER_CREDENTIALS_DIR":
                        provider_root = temp / "provider"
                        provider = provider_root / "tenant-a/project-a/team-a/chatgpt"
                        provider.parent.mkdir(parents=True)
                        provider.write_text("test")
                        provider.chmod(0o600)
                        provider_root.chmod(0o700)
                        provider.parent.parent.parent.chmod(0o700)
                        provider.parent.parent.chmod(0o700)
                        provider.parent.chmod(0o700)
                        value = str(provider_root)
                    if key == "CONTROL_PLANE_BUILD_FROM_SOURCE": value = "true"
                    if key == "CONTROL_PLANE_IMAGE": value = "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c"
                    if key == "WORKER_BUILD_FROM_SOURCE": value = "false"
                    with temp.joinpath(".env").open("a") as env_output:
                        env_output.write(f"{key}={value}\n")
            result = subprocess.run([str(ROOT / "deploy/compose/validate-config.sh"), str(temp / ".env")],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2)
            self.assertIn("WORKER_BUILD_FROM_SOURCE=true", result.stderr)

    def test_workers_do_not_receive_static_model_proxy_tokens(self) -> None:
        compose = (ROOT / "deploy/compose/compose.yaml").read_text()
        self.assertNotIn("AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE", compose)
        self.assertNotIn("model_proxy_token_worker_", compose)
        self.assertIn("AGENTTEAMS_CHATGPT_RESPONSES_URL: http://credential-proxy:8085/v1/responses", compose)
        self.assertIn("AGENTTEAMS_ATTEMPT_CAPABILITY_ISSUE_URL: http://credential-proxy:8085/internal/v1/attempt-capabilities", compose)
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
