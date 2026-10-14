FROM maven:3.9.16-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

# Resolve dependencies before copying source so ordinary source edits can reuse this layer.
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime

ARG APP_VERSION=dev
LABEL org.opencontainers.image.title="Xinghe Trade Center Backend" \
      org.opencontainers.image.version="${APP_VERSION}"

RUN addgroup -S xinghe && adduser -S -G xinghe xinghe
WORKDIR /app
COPY --from=build /workspace/target/xinghe-trade-center-*.jar /app/app.jar

USER xinghe
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=8 \
  CMD wget -q -O - http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
