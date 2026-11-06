#!/usr/bin/env python3
"""Build the pinned v0.1.5 Control Plane with the new-repository TASK_EVENT scope patch."""

from __future__ import annotations

import copy
import hashlib
import os
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


SOURCE_COMMIT = "0156f2c1fecdc868cb4a0f4f13fb068bb4162834"
IMAGE_TAG = "agentteams-new/control-plane:v0.1.5-task-event-scope-guard-0156f2c"


def run(command: list[str], *, cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def configure_testcontainers_docker_host() -> None:
    if os.environ.get("DOCKER_HOST"):
        return
    context = os.environ.get("DOCKER_CONTEXT")
    if not context:
        context = subprocess.run(["docker", "context", "show"], capture_output=True,
                                 text=True, check=True).stdout.strip()
    endpoint = subprocess.run(
        ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}", context],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    if endpoint.startswith("unix://"):
        os.environ["DOCKER_HOST"] = endpoint


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
    reports = source / "control-plane/target/surefire-reports"
    expected = {
        "io.agentteams.controlplane.application.ControlPlaneExecutionEventAdapterTest": 3,
        "io.agentteams.controlplane.application.JdbcTaskEventScopeGuardPostgresTest": 4,
        "io.agentteams.controlplane.config.ConfigDeploymentServiceTest": 7,
        "io.agentteams.controlplane.team.TeamDeploymentRepositoryTest": 4,
        "io.agentteams.controlplane.skill.SkillControllerTest": 6,
        "io.agentteams.controlplane.quality.JdbcArtifactQualityEventRepositoryTest": 6,
        "io.agentteams.controlplane.worker.WorkerDrainLifecycleTest": 9,
        "io.agentteams.controlplane.persistence.FoundationRepositoryIT": 2,
        "io.agentteams.controlplane.scheduling.TenantFairQueueTest": 4,
    }
    for class_name, expected_count in expected.items():
        report = reports / f"TEST-{class_name}.xml"
        suite = ET.parse(report).getroot()
        actual = int(suite.attrib["tests"])
        if (actual != expected_count or suite.attrib["skipped"] != "0"
                or suite.attrib["failures"] != "0" or suite.attrib["errors"] != "0"):
            raise RuntimeError(f"required Control Plane test gate failed: {class_name}: {suite.attrib}")

    report = source / "integration-tests/target/failsafe-reports/TEST-io.agentteams.it.TaskEventScopeEndToEndIT.xml"
    suite = ET.parse(report).getroot()
    if (suite.attrib["tests"] != "2" or suite.attrib["skipped"] != "0"
            or suite.attrib["failures"] != "0" or suite.attrib["errors"] != "0"):
        raise RuntimeError(f"required Gateway/NATS-to-Control-Plane tenant-scope tests failed: {suite.attrib}")


def main() -> int:
    image_tag = sys.argv[1] if len(sys.argv) == 2 else IMAGE_TAG
    if image_tag != IMAGE_TAG:
        raise SystemExit(f"image tag must be {IMAGE_TAG}")

    configure_testcontainers_docker_host()
    script_dir = Path(__file__).resolve().parent
    patch_file = script_dir / "patches/control-plane-v0.1.5-task-event-scope.patch"
    patch_digest = hashlib.sha256(patch_file.read_bytes()).hexdigest()
    current = subprocess.run(
        ["docker", "image", "inspect", "--format",
         '{{index .Config.Labels "org.opencontainers.image.revision"}}|'
         '{{index .Config.Labels "io.agentteams.task-event-scope-patch.sha256"}}', image_tag],
        capture_output=True, text=True, check=False,
    )
    if current.returncode == 0 and current.stdout.strip() == f"{SOURCE_COMMIT}|{patch_digest}":
        print(f"Reusing locally built hardened image {image_tag}")
        return 0
    with tempfile.TemporaryDirectory(prefix="agentteams-new-control-plane-") as temp:
        work = Path(temp)
        archive = work / "source.tar.gz"
        source = work / "source"
        source.mkdir()
        with archive.open("wb") as output:
            subprocess.run(["gh", "api", f"repos/ly416123/agentteams-java/tarball/{SOURCE_COMMIT}"],
                           stdout=output, check=True, timeout=120)
        extract_source(archive, source)
        run(["patch", "--batch", "--forward", "-p1", "-i", str(patch_file)], cwd=source)
        run([
            "mvn", "--batch-mode", "--no-transfer-progress", "-P", "!colima-testcontainers",
            "-pl", "control-plane", "-am",
            "-Dtest=ControlPlaneExecutionEventAdapterTest,JdbcTaskEventScopeGuardPostgresTest,ConfigDeploymentServiceTest,TeamDeploymentRepositoryTest,SkillControllerTest,JdbcArtifactQualityEventRepositoryTest,WorkerDrainLifecycleTest,FoundationRepositoryIT#doesNotSelectProjectWorkerWithAnActiveLease+concurrentProjectAssignmentsCannotCreateTwoActiveLeasesForOneWorker,TenantFairQueueTest",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        ], cwd=source)
        run([
            "mvn", "--batch-mode", "--no-transfer-progress",
            "-P", "integration-tests,!colima-testcontainers",
            "-pl", "integration-tests", "-am", "-DskipTests", "install",
        ], cwd=source)
        run([
            "mvn", "--batch-mode", "--no-transfer-progress",
            "-P", "integration-tests,!colima-testcontainers",
            "-pl", "integration-tests", "-Dit.test=TaskEventScopeEndToEndIT",
            "-Dfailsafe.failIfNoSpecifiedTests=false", "failsafe:integration-test", "failsafe:verify",
        ], cwd=source)
        require_tests(source)
        run([
            "docker", "build", "--platform", "linux/amd64", "--label",
            "org.opencontainers.image.source=https://github.com/ly416123/agentteams-new",
            "--label", f"org.opencontainers.image.revision={SOURCE_COMMIT}",
            "--label", f"io.agentteams.task-event-scope-patch.sha256={patch_digest}",
            "--tag", image_tag, "--file", "deploy/docker/control-plane.Dockerfile", ".",
        ], cwd=source)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, tarfile.TarError, ET.ParseError, RuntimeError) as error:
        print(f"Control Plane hardening build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
