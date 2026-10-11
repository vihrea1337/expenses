import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Плагин компилятора Compose (с Kotlin 2.x подключается отдельно, версия = версии Kotlin).
    id("org.jetbrains.kotlin.plugin.compose")
    // Плагин kotlinx.serialization — чтобы @Serializable-классы умели превращаться в JSON
    // (и чтобы маршруты навигации были обычными @Serializable-классами).
    id("org.jetbrains.kotlin.plugin.serialization")
    // KSP — генерация кода по аннотациям на этапе сборки: DAO для Room и граф зависимостей Hilt.
    id("com.google.devtools.ksp")
    // Hilt — внедрение зависимостей (DI).
    id("com.google.dagger.hilt.android")
}

// Адрес сервера — настройка сборки, а не строка в коде. Боевой — по умолчанию; отладочную
// сборку можно направить на свой компьютер без правки кода:
//   ./gradlew :app:assembleDebug -Pexpenses.baseUrl=http://10.0.2.2:8080/
// (10.0.2.2 — так эмулятор видит компьютер, на котором запущен.)
val prodBaseUrl = "https://vihreaexpenses.duckdns.org:34443/"
val debugBaseUrl = (findProperty("expenses.baseUrl") as String?) ?: prodBaseUrl

android {
    // namespace — базовый пакет для сгенерированного класса R и т.п.
    namespace = "io.github.vihrea1337.expenses.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vihrea1337.expenses"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"
        buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
        // Запуск UI-тестов (androidTest) на эмуляторе или телефоне.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: выкидывает неиспользуемый код и сжимает имена — APK в разы меньше.
            // Правила для Retrofit/kotlinx.serialization — в proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем отладочным ключом: APK раздаётся со своего сайта, не через Google Play,
            // и ключ магазина не нужен. Иначе release-сборка вышла бы неустанавливаемой.
            signingConfig = signingConfigs.getByName("debug")
            // Release всегда смотрит на боевой сервер, что бы ни передали в -Pexpenses.baseUrl.
            buildConfigField("String", "BASE_URL", "\"$prodBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Включаем Jetpack Compose (декларативный UI).
        compose = true
        // Класс BuildConfig с полями сборки (BASE_URL выше).
        buildConfig = true
    }

    // Разрешаем держать исходники в папке src/main/kotlin (а не только java).
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")

    testOptions {
        // Robolectric (тесты Room на JVM) читает ресурсы и манифест приложения.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Общий модуль: модели (DTO) — тот же контракт, что и на бэкенде, и парсер «кофе 200»,
    // общий с Telegram-ботом.
    implementation("io.github.vihrea1337.expenses:shared:1.0")

    // Compose BOM — "спецификация версий": он сам подбирает согласованные версии всех compose-библиотек,
    // поэтому у отдельных compose-зависимостей версию не указываем.
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Расширенный набор значков Material (вместо эмодзи на кнопках).
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Базовые androidx + интеграция Compose с Activity и жизненным циклом.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    // Заставка при запуске (Android 12+ API, с поддержкой старых версий).
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Навигация между экранами; маршруты — типобезопасные @Serializable-классы.
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Hilt: DI-контейнер + связка с ViewModel (hiltViewModel()) и с WorkManager.
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager: синхронизация в фоне, которая переживает закрытие приложения и
    // перезагрузку телефона и ждёт условия «есть сеть».
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // DataStore — хранение настроек (токен, курсор синхронизации, бюджет) вместо
    // SharedPreferences: асинхронно, через Flow.
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Glance — виджет на рабочий стол, написанный в стиле Compose
    // (под капотом превращается в RemoteViews, которые умеет рисовать лаунчер).
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Сеть: Retrofit (HTTP-клиент) + конвертер kotlinx.serialization (JSON <-> классы) + сам JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room — локальный кэш трат (офлайн-first): экран читает список из базы на телефоне,
    // а не напрямую из сети, и работает даже без связи. room-ktx даёт корутины/Flow.
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Юнит-тесты на JVM (без эмулятора): ViewModel с фейковыми репозиториями, расчёты
    // аналитики, Room-запросы и синхронизация — Robolectric подсовывает настоящую
    // SQLite-реализацию Android. Turbine — удобная проверка Flow.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")

    // UI-тесты Compose (androidTest): идут на эмуляторе/телефоне —
    // ./gradlew :app:connectedDebugAndroidTest. Версии compose-тестов — из того же BOM.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose-тесты внутри используют Espresso; старый (3.5/3.6) падает на Android 16+
    // (NoSuchMethodException: InputManager.getInstance) — поднимаем явно.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // Пустая Activity, в которой тест рисует отдельный composable.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
