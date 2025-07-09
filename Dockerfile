FROM gradle:8.5-jdk17 AS builder
COPY . /app
WORKDIR /app
RUN gradle shadowJar --no-daemon

FROM openjdk:17-jdk-slim
COPY --from=builder /app/build/libs/*-all.jar /app/app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]