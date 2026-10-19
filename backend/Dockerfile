FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S adpilot && adduser -S adpilot -G adpilot
COPY --from=build /workspace/target/adpilot-api-0.1.0-SNAPSHOT.jar /app/app.jar
USER adpilot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
