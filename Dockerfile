# Multi-stage Dockerfile for Village Storefront
# Builds Quarkus native executable + Vue.js admin SPA, packages with FFmpeg for media processing

# Stage 1: Build native executable and frontend assets
FROM maven:3.9-eclipse-temurin-21-alpine AS build

# Install Node.js 20 for Quinoa frontend build
RUN apk add --no-cache nodejs npm

WORKDIR /build

# Copy Maven parent POM and module POMs for dependency resolution
COPY pom.xml .
COPY modules/core-platform/pom.xml modules/core-platform/

# Resolve dependencies (cached layer - only rebuilds when POM changes)
RUN mvn dependency:go-offline -B || true

# Copy source code and configuration
COPY modules/ modules/
COPY api/ api/
COPY eclipse-formatter.xml .

# Build native executable (includes Quinoa frontend build)
# - Quarkus native compilation via GraalVM
# - Quinoa builds Vue.js admin SPA during Maven package phase
# - Skip tests to speed up build (tests run in CI)
RUN mvn clean package -Pnative \
    -DskipTests \
    -Dquarkus.native.container-build=false \
    -Dmaven.compiler.fork=true \
    -Dmaven.compiler.maxmem=2g

# Verify native executable was created
RUN ls -lh modules/core-platform/target/*-runner

# Stage 2: Runtime image with FFmpeg
FROM alpine:3.19

# Install FFmpeg and runtime dependencies
# - ffmpeg: Video transcoding (Task I4.T4 media pipeline)
# - libstdc++: C++ standard library (required by native image)
# - ca-certificates: TLS certificate verification
# - tini: Init system for proper signal handling
RUN apk add --no-cache \
    ffmpeg \
    libstdc++ \
    ca-certificates \
    tini \
    wget

# Verify FFmpeg installation
RUN ffmpeg -version

# Create non-root user for security
RUN addgroup -g 1000 quarkus && \
    adduser -u 1000 -G quarkus -s /bin/sh -D quarkus

WORKDIR /app

# Copy native executable from build stage
# Quinoa bundles Vue.js admin SPA assets into the native executable at build time
# Assets are embedded in META-INF/resources and served by Quarkus HTTP server
COPY --from=build --chown=quarkus:quarkus \
    /build/modules/core-platform/target/*-runner \
    /app/application

# Create temp directory for media processing
# Media workers need temp storage for FFmpeg transcoding
RUN mkdir -p /tmp/media_processing && \
    chown quarkus:quarkus /tmp/media_processing

# Set environment variables
# MEDIA_PROCESSING_FFMPEG_PATH: FFmpeg executable path for video transcoding
ENV MEDIA_PROCESSING_FFMPEG_PATH=/usr/bin/ffmpeg \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Switch to non-root user
USER quarkus:quarkus

# Expose application port
EXPOSE 8080

# Health check for container orchestration
# Quarkus provides /q/health/live endpoint via smallrye-health extension
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/q/health/live || exit 1

# Use tini as init to handle signals properly (graceful shutdown)
ENTRYPOINT ["/sbin/tini", "--"]

# Run the native executable
# -Dquarkus.http.host=0.0.0.0: Bind to all interfaces (required for container networking)
CMD ["/app/application", "-Dquarkus.http.host=0.0.0.0"]
