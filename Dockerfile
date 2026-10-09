# One image per application, built from a jar that was already packaged on the host:
#
#     ./mvnw -pl ragr-app -am package -DskipTests
#     docker compose --profile apps build ragr-app
#
# ragr.ps1 does both, and compose.yaml passes MODULE for each service. Maven does not run in here: the
# host has the JDK and a warm ~/.m2, and building inside Docker would download every dependency again,
# into a VM whose memory this machine can least spare.
#
# Temurin's JRE on Ubuntu 24.04; GraalVM native image has no Java 26 release.

ARG JAVA_IMAGE=eclipse-temurin:26-jre-noble

# Splits the jar into Spring Boot's four layers, least often changed first, so a code change rebuilds
# only the small application layer and the ~90 MB of dependencies come from cache. ragr-shared lands in
# the application layer too, as a module of the same build.
FROM ${JAVA_IMAGE} AS extract
ARG MODULE
WORKDIR /build
# Fails ("no source files were specified") when the jar was not packaged, rather than using a stale one.
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${JAVA_IMAGE}
# curl for the compose healthcheck only; the base image ships neither curl nor wget. Kept above the
# application layers so it is cached across every rebuild.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
# The base image's own unprivileged user.
USER ubuntu
# Exec form, so the JVM is PID 1 and gets docker stop's SIGTERM for a graceful shutdown. Heap cap and
# collector come from JDK_JAVA_OPTIONS in compose.yaml - the same values as the poms and IntelliJ.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
