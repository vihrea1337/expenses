package io.github.vihrea1337.expenses

/**
 * Разбор строки быстрого ввода в трату.
 *
 * Формат: "<категория> <сумма>". Последнее слово — сумма (число), всё перед ним — категория.
 * Примеры:
 *   "кофе 200"          -> NewExpense(amount = 200.0, category = "кофе", note = null)
 *   "такси до дома 350" -> NewExpense(amount = 350.0, category = "такси до дома", note = null)
 *
 * Возвращает NewExpense, если разобрать удалось. Если ввод мусорный ("привет",
 * "кофе дорого") — возвращает null. Тип NewExpense? (с "?") как раз и означает
 * "либо трата, либо null". Проверяя результат на null, вызывающий код понимает,
 * получилось разобрать или нет.
 */
fun parseExpense(text: String): NewExpense? {
    // Убираем пробелы по краям и режем на слова по любым пробелам (\\s+ = один или больше пробелов).
    val words = text.trim().split(Regex("\\s+"))

    // Меньше двух слов — нет ни категории, ни суммы. Разбирать нечего.
    if (words.size < 2) return null

    // Последнее слово пытаемся прочитать как число. Запятую принимаем как разделитель
    // дробной части (150,5 -> 150.5). toDoubleOrNull безопаснее toDouble: на нечисле
    // ("дорого") он не роняет программу, а спокойно возвращает null.
    val amount = words.last().replace(',', '.').toDoubleOrNull() ?: return null
    // Отрицательная или нулевая сумма для траты бессмысленна.
    if (amount <= 0) return null

    // Всё, кроме последнего слова, — категория (склеиваем обратно через пробел).
    val category = words.dropLast(1).joinToString(" ")

    return NewExpense(amount = amount, category = category, note = null)
}
