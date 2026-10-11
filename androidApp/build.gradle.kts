// Корневой build-файл. Здесь только объявляем плагины и их версии (apply false — то есть
// сами плагины подключаются в модуле :app, а тут просто фиксируем версии для всего проекта).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    // KSP — генерирует код по аннотациям во время сборки (DAO для Room, граф Hilt).
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    // Hilt — внедрение зависимостей (DI): граф объектов строится на этапе компиляции.
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
}
