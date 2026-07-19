# Деплой Expenses на сервер (Docker: Postgres + Ktor + Caddy)

Стек: **Postgres** (данные) + **Ktor** (наш бэкенд) + **Caddy** (HTTPS через DuckDNS).
Всё живёт в одной папке и своей сети; наружу торчит только Caddy на порту **34443**.
Данные восстанавливаются из дампа, секреты — из зашифрованного архива.

Адрес после деплоя: `https://sashlevhealth.duckdns.org:34443` (сайт-страница и REST API).
Telegram-бот работает через long-polling — входящие порты ему не нужны.

## 1. Подготовить на ПК

```powershell
# собрать jar
cd backend; .\gradlew.bat buildFatJar   # -> build/libs/expenses-backend-all.jar
# расшифровать секреты (получишь expenses.env и expenses-db.sql)
gpg -d expenses-backup.tgz.gpg > expenses-backup.tgz && tar xzf expenses-backup.tgz
```

## 2. Залить на сервер

```powershell
scp -r deploy/server brother:/root/app/expenses
scp backend/build/libs/expenses-backend-all.jar brother:/root/app/expenses/backend/
scp expenses-backup/expenses.env expenses-backup/expenses-db.sql brother:/root/app/expenses/
```

## 3. На сервере

```bash
cd /root/app/expenses
cp .env.example .env && nano .env         # DB_PASSWORD (любой) + DUCKDNS_TOKEN

docker compose up -d expenses-pg          # 1) поднять только БД
# 2) восстановить данные из дампа
cat expenses-db.sql | docker compose exec -T expenses-pg psql -U postgres expenses

docker compose up -d --build              # 3) собрать и поднять backend + caddy
docker compose logs -f caddy              # дождаться строки о выпуске сертификата (Ctrl+C выйти)
```

## 4. DuckDNS + проверка

- На https://www.duckdns.org переключить домен `sashlevhealth` на IP этого сервера.
- Проверить:
```bash
curl -s https://sashlevhealth.duckdns.org:34443/health   # {"status":"ok"}
```
- Приложение Android собрано с этим адресом (см. `androidApp` → `ApiClient`), нужно переустановить.
- Бот: убедиться, что старый бэкенд (на прежнем сервере) погашен — иначе два бота на один токен = ошибка Telegram 409.

## Обновление бэкенда потом

```powershell
cd backend; .\gradlew.bat buildFatJar
scp backend/build/libs/expenses-backend-all.jar brother:/root/app/expenses/backend/
```
```bash
cd /root/app/expenses && docker compose up -d --build expenses-backend
```
