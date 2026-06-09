FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/account-service-1.0.0.jar app.jar
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
