# Build stage
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./

# Ensure gradlew is executable
RUN chmod +x gradlew

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application
RUN ./gradlew build -x test --no-daemon

# Runtime stage
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre

LABEL org.opencontainers.image.title="Docker Manager"
LABEL org.opencontainers.image.description="A modern web interface for managing Docker containers"
LABEL org.opencontainers.image.source="https://github.com/WannaverseOfficial/docker-manager"

# Install required packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    tini \
    gosu \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -f dockermanager && \
    useradd -g dockermanager -s /bin/bash -m dockermanager || true

WORKDIR /app

# Create directories for data persistence
RUN mkdir -p /app/database /app/git-repos && \
    chown -R dockermanager:dockermanager /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership of the JAR
RUN chown dockermanager:dockermanager app.jar


# Environment variables with secure defaults
ENV JAVA_OPTS="-Xms256m -Xmx512m" \
    SERVER_PORT=8080 \
    SPRING_DATASOURCE_URL="jdbc:sqlite:/app/database/data.db" \
    GIT_CLONE_DIR="/app/git-repos"

# Expose the application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/auth/login || exit 1

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
