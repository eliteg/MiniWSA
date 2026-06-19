# Multi-stage build: compile with the Gradle/JDK image, run on a slim JRE.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Copy the Gradle wrapper + build scripts first so dependency resolution is cached.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
# Now the sources, then build the boot jar (skip tests — CI/`./gradlew check` covers them).
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# curl is used by the compose healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
