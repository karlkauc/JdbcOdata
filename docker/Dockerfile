# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Dassembly.skipAssembly=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/data /app/config /app/lib /app/certs \
 && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/jdbcodata.jar /app/jdbcodata.jar
USER app
EXPOSE 8080
VOLUME ["/app/data", "/app/config", "/app/lib", "/app/certs"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-Dloader.path=/app/lib", "-Dspring.config.additional-location=optional:file:/app/config/", "-jar", "/app/jdbcodata.jar"]
