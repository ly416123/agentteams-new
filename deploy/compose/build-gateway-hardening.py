#!/usr/bin/env python3
"""Build the pinned Gateway with slot-session and Attempt-safe Artifact boundaries."""

from __future__ import annotations

import copy
import hashlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


SOURCE_COMMIT = "0156f2c1fecdc868cb4a0f4f13fb068bb4162834"
IMAGE_TAG = "agentteams-new/gateway:v0.1.5-session-bound-artifacts-0156f2c"


def run(command: list[str], *, cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def extract_source(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as bundle:
        root = destination.resolve()
        members = []
        for member in bundle.getmembers():
            parts = Path(member.name).parts
            if len(parts) == 1 and member.isdir():
                continue
            if len(parts) < 2 or Path(*parts[1:]).is_absolute() or ".." in parts[1:]:
                raise RuntimeError("upstream source archive contains an unsafe path")
            target = (root / Path(*parts[1:])).resolve()
            if not target.is_relative_to(root):
                raise RuntimeError("upstream source archive escapes the extraction directory")
            stripped = copy.copy(member)
            stripped.name = "/".join(parts[1:])
            members.append(stripped)
        bundle.extractall(destination, members=members, filter="data")


def require_tests(source: Path) -> None:
    reports = source / "agent-gateway/target/surefire-reports"
    expected = {
        "io.agentteams.gateway.ArtifactUploadHandlerTest": 12,
        "io.agentteams.gateway.ArtifactUploadWiringTest": 2,
        "io.agentteams.gateway.SessionTokenAuthenticatorTest": 3,
        "io.agentteams.gateway.JdbcAgentSessionStoreTest": 3,
        "io.agentteams.gateway.ConnectionRegistryTest": 4,
        "io.agentteams.gateway.JdbcAgentStateStoreTest": 8,
        "io.agentteams.gateway.InboundEventHandlerTest": 14,
        "io.agentteams.gateway.TaskAssignedCommandHandlerTest": 7,
        "io.agentteams.gateway.CommandDeliveryServiceTest": 5,
        "io.agentteams.gateway.JdbcCommandEventStoreTest": 7,
    }
    for class_name, expected_count in expected.items():
        suite = ET.parse(reports / f"TEST-{class_name}.xml").getroot()
        if (int(suite.attrib["tests"]) != expected_count or suite.attrib["skipped"] != "0"
                or suite.attrib["failures"] != "0" or suite.attrib["errors"] != "0"):
            raise RuntimeError(f"required Gateway test gate failed: {class_name}: {suite.attrib}")


def main() -> int:
    image_tag = sys.argv[1] if len(sys.argv) == 2 else IMAGE_TAG
    if image_tag != IMAGE_TAG:
        raise SystemExit(f"image tag must be {IMAGE_TAG}")

    script_dir = Path(__file__).resolve().parent
    patch_file = script_dir / "patches/gateway-v0.1.5-session-bound-artifacts.patch"
    patch_digest = hashlib.sha256(patch_file.read_bytes()).hexdigest()
    current = subprocess.run(
        ["docker", "image", "inspect", "--format",
         '{{index .Config.Labels "org.opencontainers.image.revision"}}|'
         '{{index .Config.Labels "io.agentteams.session-bound-artifacts-patch.sha256"}}', image_tag],
        capture_output=True, text=True, check=False,
    )
    if current.returncode == 0 and current.stdout.strip() == f"{SOURCE_COMMIT}|{patch_digest}":
        print(f"Reusing locally built hardened image {image_tag}")
        return 0

    with tempfile.TemporaryDirectory(prefix="agentteams-new-gateway-") as temp:
        work = Path(temp)
        archive = work / "source.tar.gz"
        source = work / "source"
        source.mkdir()
        for attempt in range(3):
            try:
                with archive.open("wb") as output:
                    subprocess.run(["gh", "api", f"repos/ly416123/agentteams-java/tarball/{SOURCE_COMMIT}"],
                                   stdout=output, check=True, timeout=180)
                break
            except subprocess.SubprocessError:
                if attempt == 2:
                    raise
                archive.unlink(missing_ok=True)
                subprocess.run(["sleep", str(2 ** attempt)], check=True)
        extract_source(archive, source)
        run(["patch", "--batch", "--forward", "-p1", "-i", str(patch_file)], cwd=source)
        run([
            "mvn", "--batch-mode", "--no-transfer-progress", "-P", "!colima-testcontainers",
            "-pl", "agent-gateway", "-am",
            "-Dtest=ArtifactUploadHandlerTest,ArtifactUploadWiringTest,SessionTokenAuthenticatorTest,"
            "JdbcAgentSessionStoreTest,ConnectionRegistryTest,JdbcAgentStateStoreTest,InboundEventHandlerTest,"
            "TaskAssignedCommandHandlerTest,CommandDeliveryServiceTest,JdbcCommandEventStoreTest",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        ], cwd=source)
        require_tests(source)
        run([
            "mvn", "--batch-mode", "--no-transfer-progress", "-P", "!colima-testcontainers",
            "-pl", "agent-gateway", "-am", "-DskipTests", "package",
        ], cwd=source)
        image_context = work / "image-context"
        jar_relative = Path("agent-gateway/target/agentteams-agent-gateway-0.1.0-SNAPSHOT.jar")
        (image_context / jar_relative.parent).mkdir(parents=True)
        shutil.copy2(source / jar_relative, image_context / jar_relative)
        run([
            "docker", "build", "--platform", "linux/amd64", "--label",
            "org.opencontainers.image.source=https://github.com/ly416123/agentteams-new",
            "--label", f"org.opencontainers.image.revision={SOURCE_COMMIT}",
            "--label", f"io.agentteams.session-bound-artifacts-patch.sha256={patch_digest}",
            "--tag", image_tag, "--file", str(source / "deploy/docker/gateway.runtime-overlay.Dockerfile"),
            str(image_context),
        ], cwd=source)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, tarfile.TarError, ET.ParseError, RuntimeError) as error:
        print(f"Gateway hardening build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
