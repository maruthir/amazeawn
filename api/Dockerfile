# Build context is the repository root (see docker-compose.yml).
FROM gradle:8.11.1-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY common/build.gradle.kts common/
COPY api/build.gradle.kts api/
COPY order-processor/build.gradle.kts order-processor/
# Resolve dependencies first so source changes do not invalidate this layer.
RUN gradle :api:dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY common common
COPY api api
RUN gradle :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 app && apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/api/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
