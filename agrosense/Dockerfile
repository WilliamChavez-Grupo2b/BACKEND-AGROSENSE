# ── Stage 1: Build ────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# ── Stage 2: Run ──────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S agrosense && adduser -S agrosense -G agrosense
COPY --from=builder /app/target/*.jar app.jar
USER agrosense
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
