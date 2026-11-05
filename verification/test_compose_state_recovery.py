import os
import secrets
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / "deploy/compose/backup-state.sh"
RESTORE = ROOT / "deploy/compose/restore-state.sh"
STREAMS = {
    "TASK_EVENTS": "task.events.recovery",
    "AGENT_EVENTS": "agent.events.recovery",
    "CONTROL_EVENTS": "control.events",
    "DEADLETTER_EVENTS": "deadletter.events",
}


@unittest.skipUnless(shutil.which("docker"), "Docker is required for Compose restore acceptance")
class ComposeStateRecoveryTest(unittest.TestCase):
    def test_postgres_and_jetstream_restore_into_a_fresh_project(self) -> None:
        suffix = secrets.token_hex(5)
        source = f"at-recovery-source-{suffix}"
        target = f"at-recovery-target-{suffix}"
        with tempfile.TemporaryDirectory(prefix="agentteams-recovery-") as temp_name:
            temp = Path(temp_name)
            env_file = temp / ".env"
            env_file.write_text("POSTGRES_PASSWORD=recovery-test-password\nAGENTTEAMS_STORAGE_ENABLED=true\n")
            compose_file = temp / "compose.yaml"
            compose_file.write_text(
                """services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: agentteams
      POSTGRES_USER: agentteams
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes: [postgres-data:/var/lib/postgresql/data]
  nats:
    image: nats:2.10-alpine
    command: [--jetstream, --store_dir=/data/jetstream, --http_port=8222]
    volumes: [nats-data:/data/jetstream]
    networks: [data]
  seaweedfs:
    image: alpine:3.21
    profiles: [storage]
    command: [sh, -c, "mkdir -p /data; if [ ! -f /data/object.bin ]; then printf default-marker > /data/object.bin; fi; sleep 3600"]
    volumes: [seaweedfs-data:/data]
    networks: [data]
volumes:
  postgres-data:
  nats-data:
  seaweedfs-data:
networks:
  data:
    internal: true
"""
            )
            bundle = temp / "backup"
            environment = os.environ | {"COMPOSE_FILE": str(compose_file)}

            def compose(project: str, *args: str) -> str:
                result = subprocess.run(
                    ["docker", "compose", "-p", project, "--env-file", str(env_file), "-f", str(compose_file), *args],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=180,
                )
                return result.stdout

            def nats(project: str, *args: str) -> str:
                return subprocess.run(
                    ["docker", "run", "--rm", "--network", f"{project}_data", "natsio/nats-box:0.14.0",
                     "nats", "--server", "nats://nats:4222", *args],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=60,
                ).stdout

            try:
                compose(source, "up", "-d", "postgres", "nats")
                compose(source, "--profile", "storage", "up", "-d", "seaweedfs")
                for _ in range(60):
                    ready = subprocess.run(
                        ["docker", "compose", "-p", source, "--env-file", str(env_file), "-f", str(compose_file),
                         "exec", "-T", "postgres", "pg_isready", "-U", "agentteams", "-d", "agentteams"],
                        capture_output=True,
                        text=True,
                    )
                    if ready.returncode == 0:
                        break
                    import time
                    time.sleep(1)
                else:
                    self.fail("isolated PostgreSQL did not become ready")

                compose(source, "exec", "-T", "postgres", "psql", "-U", "agentteams", "-d", "agentteams",
                        "-v", "ON_ERROR_STOP=1", "-c",
                        "CREATE TABLE recovery_marker (value text PRIMARY KEY); INSERT INTO recovery_marker VALUES ('pg-marker');")
                for stream, subject in STREAMS.items():
                    nats(source, "stream", "add", stream, "--subjects", subject, "--defaults")
                    nats(source, "pub", subject, f"marker-{stream}")
                subprocess.run(
                    ["docker", "compose", "-p", source, "--env-file", str(env_file), "-f", str(compose_file),
                     "--profile", "storage", "exec", "-T", "seaweedfs", "sh", "-ec",
                     "printf object-marker > /data/object.bin"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=30,
                )

                backup_result = subprocess.run(
                    [str(BACKUP), str(env_file), str(bundle), source], env=environment,
                    capture_output=True, text=True, timeout=300,
                )
                self.assertEqual(backup_result.returncode, 0, backup_result.stderr)
                nats_backups = list((bundle / "nats").glob("*"))
                for path in nats_backups:
                    self.assertEqual(path.stat().st_uid, os.geteuid(), f"backup owner mismatch: {path}")
                    self.assertEqual(path.stat().st_mode & 0o077, 0, f"backup permissions too broad: {path}")
                original_manifest = (bundle / "manifest.json").read_bytes()
                (bundle / "manifest.json").write_bytes(original_manifest + b"tamper")
                tampered_manifest = subprocess.run(
                    [str(RESTORE), str(env_file), str(bundle), target], env=environment,
                    capture_output=True, text=True, timeout=60,
                )
                self.assertNotEqual(tampered_manifest.returncode, 0)
                self.assertIn("FAILED", tampered_manifest.stdout + tampered_manifest.stderr)
                (bundle / "manifest.json").write_bytes(original_manifest)
                original_dump = (bundle / "postgres.dump").read_bytes()
                (bundle / "postgres.dump").write_bytes(original_dump + b"tamper")
                tampered_restore = subprocess.run(
                    [str(RESTORE), str(env_file), str(bundle), target], env=environment,
                    capture_output=True, text=True, timeout=60,
                )
                self.assertNotEqual(tampered_restore.returncode, 0)
                self.assertIn("FAILED", tampered_restore.stdout + tampered_restore.stderr)
                (bundle / "postgres.dump").write_bytes(original_dump)
                restore_result = subprocess.run(
                    [str(RESTORE), str(env_file), str(bundle), target], env=environment,
                    capture_output=True, text=True, timeout=300,
                )
                self.assertEqual(restore_result.returncode, 0, restore_result.stderr)

                restored = compose(target, "exec", "-T", "postgres", "psql", "-U", "agentteams", "-d", "agentteams",
                                   "-At", "-c", "SELECT value FROM recovery_marker")
                self.assertEqual(restored.strip(), "pg-marker")
                for stream in STREAMS:
                    details = nats(target, "stream", "get", stream, "1")
                    self.assertIn(f"marker-{stream}", details)
                compose(target, "--profile", "storage", "up", "-d", "seaweedfs")
                restored_object = compose(target, "--profile", "storage", "exec", "-T", "seaweedfs",
                                           "cat", "/data/object.bin")
                self.assertEqual(restored_object.strip(), "object-marker")
                repeated_restore = subprocess.run(
                    [str(RESTORE), str(env_file), str(bundle), target], env=environment,
                    capture_output=True, text=True, timeout=60,
                )
                self.assertNotEqual(repeated_restore.returncode, 0)
                self.assertIn("already has Compose containers", repeated_restore.stderr)
            finally:
                for project in (target, source):
                    subprocess.run(
                        ["docker", "compose", "-p", project, "--env-file", str(env_file), "-f", str(compose_file),
                         "--profile", "storage", "down", "-v", "--remove-orphans"],
                        capture_output=True,
                        text=True,
                        timeout=180,
                    )
                # NATS owns the stream backup files in the bind mount and
                # leaves them mode 0600/root-owned. Let Docker's root user
                # remove that test-only tree so TemporaryDirectory can clean
                # up the remaining host-owned files reliably.
                subprocess.run(
                    ["docker", "run", "--rm", "-v", f"{temp}:/cleanup", "alpine:3.21",
                     "sh", "-ec", "chmod -R u+rwX /cleanup/backup 2>/dev/null || true; rm -rf /cleanup/backup"],
                    capture_output=True,
                    text=True,
                    timeout=60,
                )


if __name__ == "__main__":
    unittest.main()
