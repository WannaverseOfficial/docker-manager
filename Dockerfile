# syntax=docker/dockerfile:1

# --- UI (static Next.js export) ------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# --- Server --------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
# Strip Windows line endings in case the checkout converted them.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon -q
COPY src/ src/
COPY --from=ui /ui/out/ src/main/resources/static/
RUN ./gradlew bootJar --no-daemon -x test

# --- Docker CLI and Compose plugin (static binaries, for the target platform) --------------
FROM docker:29-cli AS dockercli

# --- Runtime -------------------------------------------------------------------------------
# Built for the target platform. (This used to be pinned to the build platform, so the arm64
# image shipped an amd64 Java runtime.)
FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.title="Docker Manager"
LABEL org.opencontainers.image.description="A self-hosted web interface for managing Docker"
LABEL org.opencontainers.image.source="https://github.com/WannaverseOfficial/docker-manager"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tini gosu openssh-client ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Compose stacks and git deployments run `docker compose`.
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

RUN groupadd -f dockermanager \
    && (useradd -g dockermanager -s /usr/sbin/nologin -m dockermanager || true)

WORKDIR /app
RUN mkdir -p /app/database /app/git-repos /opt/docker-manager/stacks \
    && chown -R dockermanager:dockermanager /app /opt/docker-manager

COPY --from=builder /app/build/libs/*.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh

ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseSerialGC" \
    SERVER_PORT=8080 \
    SPRING_DATASOURCE_URL="jdbc:sqlite:/app/database/data.db?journal_mode=WAL&busy_timeout=30000&synchronous=NORMAL" \
    APP_DATA_DIR=/app/database \
    GIT_CLONE_DIR=/app/git-repos \
    STACKS_DIR=/opt/docker-manager/stacks

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/system/status >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
