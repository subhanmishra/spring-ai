# One image per application, built from a jar that was already packaged on the host:
#
#     ./mvnw -pl ragr-app -am package -DskipTests
#     docker compose --profile apps build ragr-app
#
# ragr.ps1 does both, and compose.yaml passes MODULE for each service. Maven does not run in here:
# the host already has the JDK and a warm ~/.m2, and building inside Docker would download every
# dependency again into the WSL2 VM, whose memory this host can least spare.
#
# Temurin's JRE on Ubuntu 24.04 rather than a GraalVM native image, which has no Java 26 release.

ARG JAVA_IMAGE=eclipse-temurin:26-jre-noble

# Splits the fat jar into Spring Boot's four layers, ordered from least to most often changed. A
# code change then rebuilds only the application layer, while the ~90 MB dependencies layer is
# reused from cache. ragr-shared goes into the application layer too: Boot puts modules from the same
# build there, so snapshot-dependencies is empty here.
FROM ${JAVA_IMAGE} AS extract
ARG MODULE
WORKDIR /build
# Fails with "no source files were specified" when the jar has not been packaged - never silently
# builds from a stale one, because there is nothing else in the build context to fall back to.
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
# Exec form, so the JVM is PID 1 and receives docker stop's SIGTERM for a graceful shutdown. Heap
# cap and collector come from JDK_JAVA_OPTIONS in compose.yaml, the same values as the poms and
# the IntelliJ run configurations.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
