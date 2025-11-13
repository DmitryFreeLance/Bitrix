# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---------- run stage ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# app
COPY --from=build /app/target/*.jar /app/app.jar

COPY 1.jpg 1.docx /app/
COPY images/ /app/images/

EXPOSE 8080
CMD ["java","-jar","/app/app.jar"]