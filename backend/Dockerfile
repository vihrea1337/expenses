# Образ бэкенда Expenses: кладём готовый fat-jar в лёгкий JRE-образ.
# Джарник собираем локально заранее:  ./gradlew buildFatJar
# Затем из папки backend/:  docker build -t expenses-backend .
# (в контексте сборки нужен build/libs/expenses-backend-all.jar)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/expenses-backend-all.jar /app/app.jar
# В контейнере слушаем все интерфейсы, иначе проброс порта не достучится.
ENV HOST=0.0.0.0 \
    PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
