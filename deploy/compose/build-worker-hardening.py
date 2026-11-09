#!/usr/bin/env python3
"""Build pinned v0.1.5 Worker with Attempt-isolated workspace and state."""
from __future__ import annotations

import copy
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

SOURCE_COMMIT = "0156f2c1fecdc868cb4a0f4f13fb068bb4162834"
SOURCE_TAG = "v0.1.5"
IMAGE_TAG = "agentteams-new/agent-worker:v0.1.5-attempt-capability-0156f2c"
BASE_IMAGE = "dockerproxy.net/library/eclipse-temurin@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a"
REQUIRED_TESTS = {
    "io.agentteams.worker.agentscope.ConfiguredAgentScopeHarnessFactoryTest": 19,
    "io.agentteams.worker.chatgpt.AttemptCredentialTokenSourceTest": 2,
    "io.agentteams.worker.chatgpt.AttemptCapabilityTokenSourceTest": 3,
    "io.agentteams.worker.WorkerRuntimeFactoryTest": 12,
    "io.agentteams.runtime.GatewayRuntimeAdapterTest": 10,
    "io.agentteams.runtime.GatewayRuntimeAdapterArtifactUploadTest": 5,
    "io.agentteams.runtime.GrpcRuntimeArtifactUploadPortTest": 1,
}


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
    for test_class, expected_tests in REQUIRED_TESTS.items():
        module = "runtime" if test_class.startswith("io.agentteams.runtime.") else "agent-worker"
        report_dir = source / module / "target/surefire-reports"
        report = report_dir / f"TEST-{test_class}.xml"
        suite = ET.parse(report).getroot()
        if (suite.attrib["tests"] != str(expected_tests) or suite.attrib["skipped"] != "0"
                or suite.attrib["failures"] != "0" or suite.attrib["errors"] != "0"):
            raise RuntimeError(f"required Worker Attempt credential tests failed for {test_class}: {suite.attrib}")


def require_pinned_source_tag() -> None:
    reference = json.loads(subprocess.run(
        ["gh", "api", f"repos/ly416123/agentteams-java/git/ref/tags/{SOURCE_TAG}"],
        capture_output=True, text=True, check=True, timeout=30,
    ).stdout)
    target = reference["object"]
    if target["type"] == "tag":
        target = json.loads(subprocess.run(
            ["gh", "api", f"repos/ly416123/agentteams-java/git/tags/{target['sha']}"],
            capture_output=True, text=True, check=True, timeout=30,
        ).stdout)["object"]
    if target["type"] != "commit" or target["sha"] != SOURCE_COMMIT:
        raise RuntimeError(f"upstream {SOURCE_TAG} must resolve to the pinned Worker source commit")


def main() -> int:
    image_tag = sys.argv[1] if len(sys.argv) == 2 else IMAGE_TAG
    if image_tag != IMAGE_TAG:
        raise SystemExit(f"image tag must be {IMAGE_TAG}")
    script_dir = Path(__file__).resolve().parent
    patch_file = script_dir / "patches/agent-worker-v0.1.5-attempt-workspace.patch"
    patch_digest = hashlib.sha256(patch_file.read_bytes()).hexdigest()
    current = subprocess.run(
        ["docker", "image", "inspect", "--format",
         '{{index .Config.Labels "org.opencontainers.image.revision"}}|'
         '{{index .Config.Labels "io.agentteams.attempt-capability-patch.sha256"}}', image_tag],
        capture_output=True, text=True, check=False,
    )
    if current.returncode == 0 and current.stdout.strip() == f"{SOURCE_COMMIT}|{patch_digest}":
        print(f"Reusing locally built Attempt-isolated image {image_tag}")
        return 0
    with tempfile.TemporaryDirectory(prefix="agentteams-new-worker-") as temp:
        work = Path(temp)
        archive = work / "source.tar.gz"
        source = work / "source"
        source.mkdir()
        require_pinned_source_tag()
        with archive.open("wb") as output:
            subprocess.run(["gh", "api", f"repos/ly416123/agentteams-java/tarball/{SOURCE_TAG}"],
                           stdout=output, check=True, timeout=120)
        extract_source(archive, source)
        run(["patch", "--batch", "--forward", "-p1", "-i", str(patch_file)], cwd=source)
        selected_tests = ",".join(test_class.rsplit(".", 1)[-1] for test_class in REQUIRED_TESTS)
        run(["mvn", "--batch-mode", "--no-transfer-progress", "-pl", "agent-worker", "-am",
             f"-Dtest={selected_tests}", "-Dsurefire.failIfNoSpecifiedTests=false", "test"], cwd=source)
        require_tests(source)
        # Resolve Maven dependencies on the host, where the normal Maven
        # settings/proxy are available. The runtime image only needs the
        # platform-independent Boot JAR; do not make Docker's build network
        # responsible for downloading packages a second time.
        run(["mvn", "--batch-mode", "--no-transfer-progress", "-pl", "agent-worker", "-am",
             "-DskipTests", "install"], cwd=source)
        run(["mvn", "--batch-mode", "--no-transfer-progress", "-pl", "agent-worker",
             "-DskipTests", "package", "spring-boot:repackage"], cwd=source)
        boot_jar = source / "agent-worker/target/agentteams-agent-worker-0.1.0-SNAPSHOT-boot.jar"
        if not boot_jar.is_file():
            raise RuntimeError("Attempt-hardened Worker Boot JAR was not produced")
        image_context = work / "runtime-image"
        image_context.mkdir()
        shutil.copy2(boot_jar, image_context / "app.jar")
        (image_context / "Dockerfile").write_text(f"""FROM {BASE_IMAGE}
WORKDIR /app
COPY --chown=10001:0 app.jar /app/app.jar
USER 10001
EXPOSE 9090
ENTRYPOINT [\"java\", \"-XX:MaxRAMPercentage=75\", \"-jar\", \"/app/app.jar\"]
""", encoding="utf-8")
        run(["docker", "build", "--platform", "linux/amd64", "--label",
             "org.opencontainers.image.source=https://github.com/ly416123/agentteams-new",
             "--label", f"org.opencontainers.image.revision={SOURCE_COMMIT}",
             "--label", f"io.agentteams.attempt-capability-patch.sha256={patch_digest}",
             "--tag", image_tag, "--file", "Dockerfile", "."], cwd=image_context)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, tarfile.TarError, ET.ParseError, RuntimeError) as error:
        print(f"Worker hardening build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
