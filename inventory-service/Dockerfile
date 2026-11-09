# Copies a jar built on the host rather than running Maven in the image.
#
# Three in-image builds of the same reactor would resolve the whole Spring
# dependency tree three times over, turning one compose build into several
# minutes of downloads for no difference in the artifact. Build first with:
#     ./mvnw -DskipTests package
FROM eclipse-temurin:21-jre

RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*  && useradd --system --create-home --uid 10001 saga

USER saga
WORKDIR /app
COPY --chown=saga:saga target/inventory-service-*.jar /app/app.jar

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
