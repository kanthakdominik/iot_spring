# Backend (Spring Boot 4, Java 25) image - works on ARM (Oracle Ampere A1) and x86.

# --- build stage ---
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
# Download dependencies first so they are cached between builds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# tests run in CI (they need Docker for Testcontainers)
RUN mvn -B -q package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN useradd --system --create-home --home-dir /app app
COPY --from=build /src/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
# JVM memory flags are passed through JAVA_TOOL_OPTIONS (see deploy/docker-compose.yml)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
