# The image needs the workspace's database/ folder. Build from the repository root:
#   docker build -f agrosense/Dockerfile -t agrosense-backend .
# Or run `docker compose build backend` from the repository root.

# ── Stage 1: Build ────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY agrosense/.mvn/ .mvn/
COPY agrosense/mvnw agrosense/pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY agrosense/src/ src/
RUN ./mvnw package -DskipTests -B

# ── Stage 2: Run ──────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S agrosense && adduser -S agrosense -G agrosense
COPY --from=builder /app/target/*.jar app.jar
# Connection settings, schema and sample data; application.properties imports them from DATABASE_DIR.
COPY database/ /app/database/
ENV DATABASE_DIR=/app/database
USER agrosense
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
