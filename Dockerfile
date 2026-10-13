# Multi-stage production Dockerfile for Payment Service
# Stage 1: Build the JAR using Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime image using Eclipse Temurin JRE 21
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl && \
    addgroup -S -g 1000 appgroup && \
    adduser -S -u 1000 -G appgroup appuser

COPY --chown=appuser:appgroup --from=builder /app/target/payment_service-0.0.1-SNAPSHOT.jar app.jar

USER appuser

ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=10s --start-period=50s --retries=3 \
    CMD curl -fsS http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
