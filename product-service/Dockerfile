FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/product-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
