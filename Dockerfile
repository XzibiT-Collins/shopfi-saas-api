# ---- Build Stage ----
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw

# Cache dependencies separately from source
RUN ./mvnw dependency:go-offline -q

COPY src src
RUN ./mvnw package -DskipTests -q

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /app/target/*.jar app.jar

RUN chown appuser:appgroup app.jar

# THEME-07 AC3: multipart spool directory owned by the service user, NOT under the shared, predictable /tmp (any local
# user could pre-create /tmp/shopfi-multipart and make startup fail closed). See docs/api/theme-contract.md 5.1.
RUN mkdir /app/shopfi-multipart && chown appuser:appgroup /app/shopfi-multipart && chmod 700 /app/shopfi-multipart
ENV MULTIPART_TMP_DIR=/app/shopfi-multipart

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
