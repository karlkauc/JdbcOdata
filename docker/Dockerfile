# syntax=docker/dockerfile:1
# Base images are pinned by digest (reproducible, no silent changes). Update: docker buildx imagetools inspect <tag>
# The build stage runs natively for every target platform: the JAR is the same, and Maven under emulation takes long.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Dassembly.skipAssembly=true package

FROM eclipse-temurin:21-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
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
