FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY credential-proxy/pom.xml ./credential-proxy/pom.xml
COPY credential-proxy/src ./credential-proxy/src
RUN mvn --batch-mode --no-transfer-progress -f pom.xml -pl credential-proxy -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 agentteams \
    && useradd --system --uid 10001 --gid agentteams --home-dir /app --shell /usr/sbin/nologin agentteams
COPY --from=build --chown=10001:10001 /workspace/credential-proxy/target/agentteams-credential-proxy-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
