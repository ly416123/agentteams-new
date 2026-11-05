import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "deploy/compose/validate-config.sh"


class ComposeOidcUrlContractTest(unittest.TestCase):
    def validate(self, issuer: str, jwks: str) -> subprocess.CompletedProcess[str]:
        values = {}
        for line in (ROOT / "deploy/compose/.env.example").read_text().splitlines():
            if line and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        values.update({
            "API_AUTH_ENABLED": "true",
            "OIDC_ISSUER_URI": issuer,
            "OIDC_JWK_SET_URI": jwks,
            "OIDC_AUDIENCE": "agentteams-test",
            "POSTGRES_PASSWORD": "test-postgres-password-with-32-chars",
            "WORKER_ENROLLMENT_DB_PASSWORD": "test-enrollment-password-with-32-chars",
            "CREDENTIAL_PROXY_DB_PASSWORD": "test-credential-proxy-password-with-32-chars",
            "WORKER_REPLICAS": "0",
        })
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


if __name__ == "__main__":
    unittest.main()
