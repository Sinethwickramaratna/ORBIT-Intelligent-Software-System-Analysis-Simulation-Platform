# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S orbit && adduser -S orbit -G orbit
WORKDIR /app
COPY --from=build /build/target/orbit-backend.jar app.jar
RUN chown -R orbit:orbit /app
USER orbit

# Per-machine settings file (JWT_SECRET etc.); docker-compose bind-mounts the project's .env here.
ENV ORBIT_ENV_FILE=/app/.env \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD wget -qO- http://localhost:8080/api/setup/status >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
