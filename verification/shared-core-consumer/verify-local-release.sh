#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPOSITORY_ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)
DEFAULT_SOURCE=$(cd "${REPOSITORY_ROOT}/.." && pwd)/agentteams-java
SOURCE=${AGENTTEAMS_JAVA_SOURCE:-${DEFAULT_SOURCE}}
SOURCE_REF=${AGENTTEAMS_JAVA_REF:-HEAD}
VERSION=${AGENTTEAMS_CORE_VERSION:-0.1.1}

usage() {
  cat <<'EOF'
用法：
  AGENTTEAMS_JAVA_SOURCE=/path/to/agentteams-java \
  AGENTTEAMS_CORE_VERSION=0.1.1 \
    verification/shared-core-consumer/verify-local-release.sh

可选环境变量：
  AGENTTEAMS_JAVA_SOURCE  旧仓库的 Git 工作区；默认取并列目录 ../agentteams-java
  AGENTTEAMS_JAVA_REF     要验证的已提交 Git ref；默认 HEAD
  AGENTTEAMS_CORE_VERSION 要验证的稳定版本号；默认 0.1.1

脚本从已提交 ref 创建干净源码快照，在临时 Maven 仓库部署 parent 和 6 个核心模块，
再用空 Maven 本地缓存运行独立 consumer 的 dependency:tree。不会读取或修改旧仓库工作区文件。
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if [[ $# -ne 0 ]]; then
  usage >&2
  exit 2
fi
if [[ ! -d "${SOURCE}/.git" && ! -f "${SOURCE}/.git" ]]; then
  echo "AGENTTEAMS_JAVA_SOURCE must point to a Git checkout: ${SOURCE}" >&2
  exit 2
fi
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "AGENTTEAMS_CORE_VERSION must be a stable X.Y.Z version: ${VERSION}" >&2
  exit 2
fi

SOURCE_COMMIT=$(git -C "${SOURCE}" rev-parse "${SOURCE_REF}^{commit}")
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/agentteams-core-consumer.XXXXXX")
trap 'rm -rf "${TEMP_ROOT}"' EXIT
SOURCE_SNAPSHOT="${TEMP_ROOT}/source"
FILE_REPOSITORY="${TEMP_ROOT}/maven-repository"
MAVEN_CACHE="${TEMP_ROOT}/maven-cache"
MAVEN_SETTINGS="${TEMP_ROOT}/settings.xml"
MAVEN_GLOBAL_SETTINGS="${TEMP_ROOT}/global-settings.xml"
mkdir -p "${SOURCE_SNAPSHOT}" "${FILE_REPOSITORY}" "${MAVEN_CACHE}"
printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" />' >"${MAVEN_SETTINGS}"
cp "${MAVEN_SETTINGS}" "${MAVEN_GLOBAL_SETTINGS}"

git -C "${SOURCE}" archive "${SOURCE_COMMIT}" | tar -x -C "${SOURCE_SNAPSHOT}"

# Redirect only the disposable source snapshot's release destination. The
# checked-in release workflow and source checkout remain untouched.
python3 - "${SOURCE_SNAPSHOT}/pom.xml" "file://${FILE_REPOSITORY}" <<'PY'
from pathlib import Path
import sys

pom = Path(sys.argv[1])
repository_url = sys.argv[2]
content = pom.read_text(encoding="utf-8")
old_url = "https://maven.pkg.github.com/ly416123/agentteams-java"
if content.count(old_url) != 1:
    raise SystemExit("expected exactly one GitHub Packages distribution URL in source POM")
pom.write_text(content.replace(old_url, repository_url), encoding="utf-8")
PY

echo "Source commit: ${SOURCE_COMMIT}"
echo "Core version: ${VERSION}"
echo "Deploying committed source snapshot to a temporary file repository"
mvn --batch-mode -s "${MAVEN_SETTINGS}" -gs "${MAVEN_GLOBAL_SETTINGS}" \
  -Dmaven.repo.local="${MAVEN_CACHE}" \
  -f "${SOURCE_SNAPSHOT}/pom.xml" \
  org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
  -DnewVersion="${VERSION}" \
  -DprocessAllModules=true \
  -DgenerateBackupPoms=false \
  -DupdateMatchingVersions=true

mvn --batch-mode -s "${MAVEN_SETTINGS}" -gs "${MAVEN_GLOBAL_SETTINGS}" \
  -Dmaven.repo.local="${MAVEN_CACHE}" \
  -f "${SOURCE_SNAPSHOT}/pom.xml" \
  -pl .,contracts,domain,application-contracts,observability,storage,runtime \
  -am -DskipTests \
  -DaltDeploymentRepository="verification::default::file://${FILE_REPOSITORY}" deploy

python3 - "${FILE_REPOSITORY}" "${VERSION}" <<'PY'
from pathlib import Path
import sys

repository = Path(sys.argv[1])
version = sys.argv[2]
artifacts = {
    "agentteams-java": {"pom"},
    "agentteams-contracts": {"pom", "jar"},
    "agentteams-domain": {"pom", "jar"},
    "agentteams-application-contracts": {"pom", "jar"},
    "agentteams-observability": {"pom", "jar"},
    "agentteams-storage": {"pom", "jar"},
    "agentteams-runtime": {"pom", "jar"},
}
missing = [
    f"io/agentteams/{artifact}/{version}/{artifact}-{version}.{extension}"
    for artifact, extensions in artifacts.items()
    for extension in extensions
    if not (repository / "io/agentteams" / artifact / version
            / f"{artifact}-{version}.{extension}").is_file()
]
if missing:
    raise SystemExit("local Maven publication is incomplete:\n" + "\n".join(missing))
print(f"Published file repository contains {sum(map(len, artifacts.values()))} expected POM/JAR artifacts")
PY

echo "Resolving independent consumer from the temporary file repository"
mvn --batch-mode -s "${MAVEN_SETTINGS}" -gs "${MAVEN_GLOBAL_SETTINGS}" \
  -Dmaven.repo.local="${MAVEN_CACHE}" \
  -f "${SCRIPT_DIR}/pom.xml" \
  -Dagentteams.core.version="${VERSION}" \
  -Dagentteams.maven.repository="file://${FILE_REPOSITORY}" \
  dependency:tree -Dverbose

echo "SHARED_CORE_LOCAL_CONSUMER_OK version=${VERSION} commit=${SOURCE_COMMIT}"
