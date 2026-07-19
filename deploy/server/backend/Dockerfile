# Образ бэкенда для сервера: кладём готовый fat-jar рядом с этим файлом.
# Перед сборкой положи сюда expenses-backend-all.jar (собранный ./gradlew buildFatJar).
# Хост/порт задаются переменными окружения из docker-compose (HOST=0.0.0.0, PORT=8080).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY expenses-backend-all.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
