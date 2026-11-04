FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY worker-enrollment/pom.xml ./worker-enrollment/pom.xml
COPY worker-enrollment/src ./worker-enrollment/src
RUN mvn -B -ntp -f pom.xml -pl worker-enrollment -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system --gid 10001 agentteams \
    && useradd --system --uid 10001 --gid agentteams --home-dir /app --shell /usr/sbin/nologin agentteams
COPY --from=build --chown=10001:10001 /workspace/worker-enrollment/target/agentteams-worker-enrollment-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
