FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG BUILD_PROFILE=prod
WORKDIR /workspace

COPY pom.xml ./
COPY app-kernel/pom.xml app-kernel/pom.xml
COPY app-contracts/pom.xml app-contracts/pom.xml
COPY app-edge-ktor/pom.xml app-edge-ktor/pom.xml
COPY app-ui-vaadin/pom.xml app-ui-vaadin/pom.xml

RUN mvn -B -q -pl app-ui-vaadin -am dependency:go-offline

COPY . .
RUN mvn -B -pl app-ui-vaadin -am -P${BUILD_PROFILE} -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app-ui-vaadin/target/app-ui-vaadin-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
