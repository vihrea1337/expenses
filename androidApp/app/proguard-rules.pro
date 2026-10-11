# Правила R8 для release-сборки.
# Retrofit, OkHttp, Room, Hilt и kotlinx.serialization приходят со своими правилами внутри
# библиотек — здесь только то, что нужно именно нашему коду.

# Классы с @Serializable из shared/ (DTO): R8 не должен выкидывать их сериализаторы.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.vihrea1337.expenses.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class io.github.vihrea1337.expenses.**$$serializer { *; }

# Retrofit: методы интерфейса API читаются через рефлексию (аннотации @GET/@POST и типы
# suspend-функций). allowobfuscation — имя интерфейса сжимать можно, выкидывать нельзя.
-keep,allowobfuscation interface io.github.vihrea1337.expenses.android.data.ExpensesApi
