# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS=""
COPY --from=build /src/target/winrun-bot.jar /app/winrun-bot.jar
COPY .env.example /app/.env
VOLUME ["/app/data"]
EXPOSE 8080
CMD ["sh", "-lc", "java $JAVA_OPTS -jar /app/winrun-bot.jar"]