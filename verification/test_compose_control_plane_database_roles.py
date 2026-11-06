import unittest
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "deploy/compose/compose.yaml"
EXAMPLE_ENV = ROOT / "deploy/compose/.env.example"
ENROLLMENT_RLS = ROOT / "worker-enrollment/src/main/resources/db/worker-enrollment-rls.sql"


def compose_service(name: str) -> str:
    content = COMPOSE.read_text()
    services = list(re.finditer(r"(?m)^  ([a-z0-9-]+):$", content))
    for index, match in enumerate(services):
        if match.group(1) == name:
            end = services[index + 1].start() if index + 1 < len(services) else len(content)
            return content[match.start():end]
    raise AssertionError(f"Compose service not found: {name}")


class ComposeControlPlaneDatabaseRolesTest(unittest.TestCase):
    def test_control_plane_uses_non_superuser_runtime_role_and_separate_flyway_identity(self) -> None:
        control_plane = compose_service("control-plane")

        self.assertIn("SPRING_DATASOURCE_USERNAME: agentteams_runtime", control_plane)
        self.assertIn("SPRING_DATASOURCE_PASSWORD: ${CONTROL_PLANE_DB_PASSWORD", control_plane)
        self.assertIn("SPRING_FLYWAY_USER: agentteams", control_plane)
        self.assertIn("SPRING_FLYWAY_PASSWORD: ${POSTGRES_PASSWORD", control_plane)
        self.assertIn("database-role-setup:", control_plane)

    def test_database_role_setup_precedes_control_plane_and_grants_default_migration_objects(self) -> None:
        setup = compose_service("database-role-setup")

        self.assertIn("CONTROL_PLANE_DB_PASSWORD", setup)
        self.assertIn("NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS", setup)
        self.assertRegex(setup, r"ALTER DEFAULT PRIVILEGES[^\n]+GRANT ALL ON TABLES TO agentteams_runtime")
        self.assertRegex(setup, r"ALTER DEFAULT PRIVILEGES[^\n]+GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO agentteams_runtime")
        self.assertIn("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO agentteams_runtime", setup)
        self.assertIn("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO agentteams_runtime", setup)

    def test_example_config_has_a_distinct_control_plane_runtime_password(self) -> None:
        values = {}
        for line in EXAMPLE_ENV.read_text().splitlines():
            if line and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key] = value

        self.assertIn("CONTROL_PLANE_DB_PASSWORD", values)
        self.assertNotEqual(values["CONTROL_PLANE_DB_PASSWORD"], values["POSTGRES_PASSWORD"])
        self.assertGreaterEqual(len(values["CONTROL_PLANE_DB_PASSWORD"]), 24)
        self.assertRegex(values["CONTROL_PLANE_DB_PASSWORD"], r"replace-with-a-unique-control-plane-db-password")

    def test_database_role_setup_does_not_require_a_running_control_plane(self) -> None:
        setup = compose_service("database-role-setup")
        self.assertNotRegex(setup, r"(?m)^\s+control-plane:")

    def test_runtime_role_keeps_existing_control_plane_access_when_enrollment_enables_rls(self) -> None:
        rls = ENROLLMENT_RLS.read_text()
        self.assertRegex(rls, r"CREATE POLICY control_plane_runtime_agents[\s\S]+?ON agents[\s\S]+?TO agentteams_runtime[\s\S]+?USING \(true\)")
        self.assertRegex(rls, r"CREATE POLICY control_plane_runtime_sessions[\s\S]+?ON agent_sessions[\s\S]+?TO agentteams_runtime[\s\S]+?USING \(true\)[\s\S]+?WITH CHECK \(true\)")


if __name__ == "__main__":
    unittest.main()
