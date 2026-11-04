import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "verification/shared-core-consumer/verify-local-release.sh"


class LocalReleaseVerificationContractTest(unittest.TestCase):
    def test_verifier_documents_and_accepts_source_and_version_options(self):
        result = subprocess.run(["bash", str(SCRIPT), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("AGENTTEAMS_JAVA_SOURCE", result.stdout)
        self.assertIn("AGENTTEAMS_CORE_VERSION", result.stdout)

    def test_verifier_uses_clean_committed_source_and_isolated_maven_cache(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('archive "${SOURCE_COMMIT}"', script)
        self.assertIn("| tar -x", script)
        self.assertIn("maven.repo.local", script)
        self.assertIn('"file://${FILE_REPOSITORY}"', script)
        self.assertIn("old_url =", script)
        self.assertIn("altDeploymentRepository=", script)
        self.assertIn("-gs \"${MAVEN_GLOBAL_SETTINGS}\"", script)
        self.assertIn("dependency:tree", script)
        self.assertIn("local Maven publication is incomplete", script)
        self.assertIn('"agentteams-runtime"', script)
        self.assertNotIn("mvn install", script)


if __name__ == "__main__":
    unittest.main()
