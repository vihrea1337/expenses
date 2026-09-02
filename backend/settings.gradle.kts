// Имя Gradle-проекта бэкенда. Отдельный, самостоятельный проект внутри репозитория Expenses.
rootProject.name = "expenses-backend"

// includeBuild подключает соседний Gradle-проект как обычную зависимость.
includeBuild("../shared")
