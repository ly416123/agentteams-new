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

发布 workflow 使用同一套 POM 和 artifact coordinates。提交 tag 前，可先将源码完整 checkout 到临时目录，用 `versions-maven-plugin` 将全 reactor 改为待发布版本，并以 `altDeploymentRepository` 发布到临时 file repository；再将 `agentteams.maven.repository` 覆盖为该 file repository，以空 Maven local cache 运行上述 consumer 命令。该验证由批次 0 的执行记录给出实际命令与输出摘要。
