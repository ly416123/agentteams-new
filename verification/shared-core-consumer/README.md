# 独立 Maven 消费验证

此 fixture 不加入默认 reactor。它模拟新仓库独立消费发布版本的核心模块，并依赖各模块 parent POM 提供的 dependencyManagement。

## 发布前提

GitHub Packages 的 Maven Registry 需要认证，即使包关联的仓库公开也需凭证。Apache Maven 包使用仓库级权限，包设置页不提供独立包授权。已验证的本机消费方式是使用对来源仓库有读取权限、带 `read:packages` scope 的 classic PAT。跨仓库 GitHub Actions 的 `GITHUB_TOKEN` 读取路径尚未验证；CI 可先使用仓库 Secret 中的 classic PAT。`~/.m2/settings.xml` 使用与 POM 中 `github` ID 对应的 server，并通过 `GITHUB_ACTOR`、`GITHUB_TOKEN` 环境变量注入凭据，不要把 Token 写进文件或命令历史。

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

## 验证已发布版本

在仓库根目录运行：

```bash
consumer_cache="$(mktemp -d)"
mvn -f verification/shared-core-consumer/pom.xml \
  -Dmaven.repo.local="$consumer_cache" \
  dependency:tree -Dverbose
```

成功标准是 Maven 从 GitHub Packages 解析 `agentteams-java:0.1.5` parent 和 6 个核心 JAR，并从 Maven Central 解析它们的第三方传递依赖。使用临时 local cache 可避免旧的本机 SNAPSHOT 或手工安装包掩盖远端缺失。若发布版本号改变，通过 `-Dagentteams.core.version=<release-version>` 指定同一版本。

## 验证未上传的候选发布

使用仓库内脚本可完整重放发布和消费流程。脚本会从 `agentteams-java` 的已提交 ref 导出干净快照，不读取未提交文件；在临时目录中归一化版本、使用隔离 Maven settings 和本地缓存发布到 file repository，校验 13 个 POM/JAR 文件，再运行本 consumer 的 `dependency:tree`：

```bash
AGENTTEAMS_JAVA_SOURCE=/path/to/agentteams-java \
AGENTTEAMS_JAVA_REF=<commit-or-tag> \
AGENTTEAMS_CORE_VERSION=0.1.5 \
  verification/shared-core-consumer/verify-local-release.sh
```

2026-11-04 已使用本机 `gh` 凭证（`read:packages`）和本机代理 `127.0.0.1:7892`，在临时 Maven settings 与独立本地缓存下执行远端 `dependency:tree`。Maven 从 GitHub Packages 成功解析根 parent、6 个核心模块 `0.1.5` 及第三方传递依赖，输出 `BUILD SUCCESS`。凭证通过环境变量注入，未写入仓库或持久 settings。该验证证明本机凭证路径可用；新仓库 GitHub Actions 的 `GITHUB_TOKEN` 跨仓库访问仍待单独验证。
