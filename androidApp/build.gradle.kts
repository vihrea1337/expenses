// Корневой build-файл. Здесь только объявляем плагины и их версии (apply false — то есть
// сами плагины подключаются в модуле :app, а тут просто фиксируем версии для всего проекта).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    // KSP — генерирует код Room (DAO-реализации) из аннотаций во время сборки.
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
