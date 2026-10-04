FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY poker-domain/pom.xml poker-domain/pom.xml
COPY poker-server/pom.xml poker-server/pom.xml
RUN mvn -B -pl poker-server -am dependency:go-offline
COPY poker-domain/src poker-domain/src
COPY poker-server/src poker-server/src
RUN mvn -B -pl poker-server -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 poker
COPY --from=build /build/poker-server/target/poker-server-*.jar app.jar
RUN mkdir -p /app/data && chown -R poker:poker /app
USER poker
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]

