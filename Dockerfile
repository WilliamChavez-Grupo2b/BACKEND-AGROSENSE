# Build from the repository root:
#   docker build -t agrosense-backend .
# Or run `docker compose build backend`.

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
# The database settings come from the environment (SPRING_DATASOURCE_URL, _USERNAME, _PASSWORD).
USER agrosense
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
