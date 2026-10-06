# ---- build: Maven + full JDK (never shipped) -----------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package

# ---- jre: custom minimal Java runtime (jdeps + jlink) --------------------------------------------------------------
# Same Alpine/musl JDK as the build stage, so the jlinked runtime runs on plain alpine below.
FROM eclipse-temurin:17-jdk-alpine AS jre
COPY --from=build /build/target/orbit-backend.jar /tmp/app.jar
COPY docker/make-jre.sh /tmp/make-jre.sh
RUN sh /tmp/make-jre.sh /tmp/app.jar /opt/jre

# ---- runtime: bare Alpine + the trimmed JRE + the jar --------------------------------------------------------------
FROM alpine:3.21
# /app is created and chowned here (tiny layer). Do NOT run `chown -R /app` after copying the jar: that re-writes the
# whole ~65 MB jar into a second layer and doubles its size in the image. COPY --chown sets the owner for free.
RUN addgroup -S orbit && adduser -S orbit -G orbit \
 && mkdir /app && chown orbit:orbit /app
WORKDIR /app
COPY --from=jre /opt/jre /opt/jre
COPY --from=build --chown=orbit:orbit /build/target/orbit-backend.jar app.jar
USER orbit

# Per-machine settings file (JWT_SECRET etc.); docker-compose bind-mounts the project's .env here.
ENV ORBIT_ENV_FILE=/app/.env \
    PATH="/opt/jre/bin:${PATH}" \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.awt.headless=true"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8080/api/setup/status >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
