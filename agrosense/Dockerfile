# The image needs the workspace's database/ folder, so build it from the workspace root:
#   docker build -f backend/agrosense/Dockerfile -t agrosense-backend .

# ── Stage 1: Build ────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY backend/agrosense/.mvn/ .mvn/
COPY backend/agrosense/mvnw backend/agrosense/pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY backend/agrosense/src/ src/
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
