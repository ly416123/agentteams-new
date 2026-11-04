# 独立 Maven 消费验证

此 fixture 不加入默认 reactor。它模拟新仓库独立消费发布版本的核心模块，并依赖各模块 parent POM 提供的 dependencyManagement。

## 发布前提

GitHub Packages 的 Maven Registry 需要认证。`agentteams-new` 仓库必须获得 `agentteams-java` 对应 Maven package 的读取权限；本地消费使用具备 `read:packages` scope 的 classic PAT。CI 可使用有 package 读取权限的 `GITHUB_TOKEN`。`~/.m2/settings.xml` 使用与 POM 中 `github` ID 对应的 server，并通过 `GITHUB_ACTOR`、`GITHUB_TOKEN` 环境变量注入凭据，不要把 Token 写进文件或命令历史。

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

成功标准是 Maven 从 GitHub Packages 解析 `agentteams-java:0.1.0` parent 和 6 个核心 JAR，并从 Maven Central 解析它们的第三方传递依赖。使用临时 local cache 可避免旧的本机 SNAPSHOT 或手工安装包掩盖远端缺失。若发布版本号改变，通过 `-Dagentteams.core.version=<release-version>` 指定同一版本。

## 验证未上传的候选发布

使用仓库内脚本可完整重放发布和消费流程。脚本会从 `agentteams-java` 的已提交 ref 导出干净快照，不读取未提交文件；在临时目录中归一化版本、使用隔离 Maven settings 和本地缓存发布到 file repository，校验 13 个 POM/JAR 文件，再运行本 consumer 的 `dependency:tree`：

```bash
AGENTTEAMS_JAVA_SOURCE=/path/to/agentteams-java \
AGENTTEAMS_JAVA_REF=<commit-or-tag> \
AGENTTEAMS_CORE_VERSION=0.1.1 \
  verification/shared-core-consumer/verify-local-release.sh
```

截至当前批次，`0.1.1` 仅是本地验证候选版本，不能据此推断 GitHub Packages 已发布。实际发布后，再使用上节命令和独立 Maven 缓存访问 GitHub Packages；本地 file repository 验证不能替代远端权限和下载验收。
