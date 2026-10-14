FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/user-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
