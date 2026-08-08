# Build context is apps/graphiti-ingester, not the repo root — this is a Java service inside
# a Rust/Tauri monorepo, and a root context would ship the whole workspace to the daemon.

# JDK 21, not 17 and not "latest". Lombok cannot parse a newer JDK's AST: under JDK 26 the
# build dies with `ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`,
# which names a javac internal and nothing about the real cause. Pin it.
#
# Maven comes from the base image rather than from ./mvnw. The wrapper is kept in the repo so
# local builds pin the same version, but inside the image it only adds a download of Maven
# itself plus an unzip dependency the JRE image doesn't ship — for no reproducibility gain
# over a pinned base image tag.
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Dependencies resolve in their own layer so a source-only change doesn't re-download the
# world on every build.
COPY pom.xml ./
RUN mvn dependency:go-offline -B -q

COPY src/ src/
# Tests run in CI against a real JDK before the image is built; re-running them here would
# double the build time for the same signal.
RUN mvn clean package -DskipTests=true -B -q


FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is not in the temurin JRE image. Without it the HEALTHCHECK below fails every probe
# and the container reports permanently unhealthy while the app is fine.
#
# No ssh client: the workstation pushes over HTTP, so nothing here reaches back out to it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 appuser \
    && useradd --system --uid 1001 --gid appuser --no-create-home appuser

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

# Where the workstation's pushed export files land, and where the ingest scheduler reads
# them from. Owned by appuser so the service can write there without running as root.
RUN mkdir -p /data/redacted-jsonl \
    && chown -R appuser:appuser /app /data

USER appuser

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container's memory limit is set in Coolify,
# and a hardcoded heap would either waste it or get the process OOM-killed when it changes.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

# Reports unhealthy while the ingest can't reach its database. Deliberately does not check
# graphiti-mcp: the workstation behind it is expected to be asleep outside working hours, and
# flapping the container for that would be worse than the outage it reports.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
