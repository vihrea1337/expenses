plugins {
    // Плагин "application" — умеет запускать наш сервер и собирать его в исполняемый вид.
    application
    // Kotlin для JVM — компилирует Kotlin в байт-код, который выполняет Java.
    kotlin("jvm") version "2.1.0"
    // kotlinx.serialization — учит компилятор превращать классы в JSON и обратно.
    kotlin("plugin.serialization") version "2.1.0"
    // Плагин Ktor — даёт удобный запуск и сборку "толстого" JAR (один файл для деплоя на сервер).
    id("io.ktor.plugin") version "3.0.3"
}

group = "io.github.vihrea1337.expenses"
version = "1.1.0"

application {
    // Класс с функцией main(), с которого стартует сервер.
    // Файл Application.kt Kotlin превращает в класс с именем ApplicationKt.
    mainClass.set("io.github.vihrea1337.expenses.ApplicationKt")
}

kotlin {
    // Компилировать под Java 21 (её JDK у нас идёт с Android Studio).
    jvmToolchain(21)
}

repositories {
    // Откуда Gradle качает библиотеки.
    mavenCentral()
}

dependencies {
    // Общий модуль моделей (DTO) — тот же контракт, что и в Android-приложении.
    implementation("io.github.vihrea1337.expenses:shared:1.0")

    // Ядро Ktor + сам HTTP-сервер (движок Netty).
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    // Согласование форматов ответа + JSON через kotlinx.serialization.
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    // Авторизация: проверка токена (Bearer) на защищённых ручках /api/*.
    implementation("io.ktor:ktor-server-auth")
    // Логгер — чтобы видеть, что происходит на сервере.
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // --- Работа с базой данных ---
    // Exposed — Kotlin-обёртка над SQL: таблицы описываем Kotlin-объектами,
    // запросы пишем на Kotlin (типобезопасно), а не строками SQL.
    val exposedVersion = "0.57.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")     // ядро: типы колонок, DSL запросов
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")     // мост Exposed → JDBC (реальное выполнение запросов)
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion") // колонки дата/время на java.time
    // JDBC-драйвер PostgreSQL — как JVM физически общается именно с Postgres.
    implementation("org.postgresql:postgresql:42.7.4")
    // HikariCP — пул соединений: держит готовые подключения к БД и переиспользует их
    // (открывать новое подключение на каждый запрос дорого). Понадобится на шаге подключения.
    implementation("com.zaxxer:HikariCP:6.2.1")

    // --- HTTP-клиент для Telegram Bot API ---
    // До сих пор наш сервер только ОТВЕЧАЛ на запросы. Боту нужно наоборот — САМОМУ
    // ходить к Telegram (спрашивать новые сообщения и слать ответы). Для этого нужен клиент.
    implementation("io.ktor:ktor-client-core")                 // ядро HTTP-клиента
    implementation("io.ktor:ktor-client-cio")                  // движок: кто реально шлёт запросы по сети
    implementation("io.ktor:ktor-client-content-negotiation")  // разбирать JSON-ответы Telegram в наши классы

    // Для тестов сервера (поднимают Ktor без реального порта).
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
    // H2 — встроенная база «в памяти»: репозитории тестируем на настоящем SQL,
    // но без Postgres и Docker (база живёт только на время теста).
    testImplementation("com.h2database:h2:2.2.224")
}
