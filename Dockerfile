FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY contracts contracts
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
RUN groupadd -g 10004 ouf && useradd -r -u 10004 -g ouf ouf
WORKDIR /app
COPY --from=build /build/target/udp-object-resolution-*.jar app.jar
USER 10004:10004
ENTRYPOINT ["java","-jar","/app/app.jar"]
