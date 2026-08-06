# Tests are skipped here on purpose: they need a Docker daemon (Testcontainers),
# which the Render build environment does not provide. CI runs them instead.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
USER app

# Render Free gives 512 MB. A percentage-based heap plus the serial collector
# keeps the JVM inside that budget on a 0.1 CPU instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
