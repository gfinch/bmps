# Multi-stage build for BMPS Core Application

# Stage 1: Build the application with sbt
# Using Java 11 LTS to match local testing environment (11.0.26)
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-11.0.21_9_1.9.9_2.13.12 AS builder

WORKDIR /app

# Copy project files
COPY project project
COPY build.sbt .
COPY core core

# Build the application
# Use sbt-assembly to create a fat JAR
RUN sbt "core/assembly"

# Stage 2: Runtime image
# Using Java 11 JRE to match local testing environment
FROM eclipse-temurin:11-jre-jammy

WORKDIR /app

# Install curl for health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the assembled JAR from builder stage
COPY --from=builder /app/core/target/scala-2.13/bmps-core-assembly-*.jar /app/bmps-core.jar

# Copy application configuration
COPY core/src/main/resources/application.conf /app/application.conf

# Create a non-root user to run the application
RUN groupadd -r bmps && useradd -r -g bmps bmps && \
    chown -R bmps:bmps /app

USER bmps

# Expose the REST API port
# Default is 8081, but can be overridden with BMPS_PORT env var
EXPOSE 8081

# Set default environment variables
ENV BMPS_PORT=8081 \
    BMPS_READ_ONLY_MODE=false \
    DATABENTO_KEY="" \
    TRADOVATE_PASS="" \
    TRADOVATE_KEY="" \
    TRADOVATE_DEVICE=""

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:${BMPS_PORT}/health || exit 1

# Run the application
CMD ["sh", "-c", "java -Dconfig.file=/app/application.conf -jar /app/bmps-core.jar"]
