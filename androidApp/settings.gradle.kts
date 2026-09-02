// Настройки Gradle-проекта Android-приложения.
// pluginManagement — откуда качать плагины сборки (Android Gradle Plugin, Kotlin).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// dependencyResolutionManagement — откуда качать библиотеки (androidx, compose, retrofit и т.д.).
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ExpensesAndroid"
include(":app")

// includeBuild подключает соседний Gradle-проект (общие DTO с бэкендом) как обычную зависимость.
includeBuild("../shared")
