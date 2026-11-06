import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "deploy/compose/validate-config.sh"


class ComposeOidcUrlContractTest(unittest.TestCase):
    def validate(
        self,
        issuer: str = "https://id.example/issuer",
        jwks: str = "https://id.example/jwks",
        *,
        deployment_mode: str = "local-dev",
        api_auth_enabled: bool = True,
        worker_replicas: int = 0,
        control_plane_source_build: bool = False,
        control_plane_image: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        values = {}
        for line in (ROOT / "deploy/compose/.env.example").read_text().splitlines():
            if line and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        values.update({
            "DEPLOYMENT_MODE": deployment_mode,
            "API_AUTH_ENABLED": str(api_auth_enabled).lower(),
            "OIDC_ISSUER_URI": issuer,
            "OIDC_JWK_SET_URI": jwks,
            "OIDC_AUDIENCE": "agentteams-test",
            "POSTGRES_PASSWORD": "test-postgres-password-with-32-chars",
            "CONTROL_PLANE_DB_PASSWORD": "test-control-plane-password-with-32-chars",
            "WORKER_ENROLLMENT_DB_PASSWORD": "test-enrollment-password-with-32-chars",
            "CREDENTIAL_PROXY_DB_PASSWORD": "test-credential-proxy-password-with-32-chars",
            "WORKER_REPLICAS": str(worker_replicas),
            "CONTROL_PLANE_BUILD_FROM_SOURCE": str(control_plane_source_build).lower(),
        })
        if control_plane_image is not None:
            values["CONTROL_PLANE_IMAGE"] = control_plane_image
        with tempfile.TemporaryDirectory() as temp_dir:
            env_file = Path(temp_dir) / ".env"
            env_file.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
            return subprocess.run(
                [str(VALIDATOR), str(env_file)],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_rejects_http_oidc_issuer(self) -> None:
        result = self.validate("http://id.example/issuer", "https://id.example/jwks")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OIDC_ISSUER_URI must use HTTPS", result.stderr)

    def test_rejects_http_jwks_endpoint(self) -> None:
        result = self.validate("https://id.example/issuer", "http://id.example/jwks")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OIDC_JWK_SET_URI must use HTTPS", result.stderr)

    def test_accepts_https_issuer_and_jwks_endpoint(self) -> None:
        result = self.validate("https://id.example/issuer", "https://id.example/jwks")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_anonymous_api_outside_local_dev(self) -> None:
        result = self.validate(deployment_mode="self-hosted-small", api_auth_enabled=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("API_AUTH_ENABLED=true is required outside local-dev", result.stderr)

    def test_allows_anonymous_zero_worker_local_dev(self) -> None:
        result = self.validate(api_auth_enabled=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_workers_in_local_dev_mode(self) -> None:
        result = self.validate(api_auth_enabled=False, worker_replicas=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("API_AUTH_ENABLED=true is required before starting Workers", result.stderr)

    def test_rejects_unknown_deployment_mode(self) -> None:
        result = self.validate(deployment_mode="enterprise-k8s")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("DEPLOYMENT_MODE must be local-dev or self-hosted-small", result.stderr)

    def test_rejects_self_hosted_without_hardened_control_plane_build(self) -> None:
        result = self.validate(deployment_mode="self-hosted-small")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TASK_EVENT-hardened Control Plane source build", result.stderr)

    def test_accepts_self_hosted_with_pinned_hardened_control_plane_build(self) -> None:
        result = self.validate(
            deployment_mode="self-hosted-small",
            control_plane_source_build=True,
            control_plane_image="agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c",
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_other_control_plane_images_in_self_hosted_mode(self) -> None:
        result = self.validate(
            deployment_mode="self-hosted-small",
            control_plane_source_build=True,
            control_plane_image="ghcr.io/ly416123/agentteams-control-plane@sha256:403c3da94d436c19ce09e2d347f9e4182fb972a41adc5995c57694922fb94173",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TASK_EVENT-hardened Control Plane source build", result.stderr)


if __name__ == "__main__":
    unittest.main()
