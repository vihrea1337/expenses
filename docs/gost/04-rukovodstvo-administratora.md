# Руководство администратора

## автоматизированной системы учёта личных расходов «Expenses»

Эксплуатационный документ: разворачивание, обновление, резервное копирование и
поддержание системы в рабочем состоянии.

---

## 1. Общие сведения об эксплуатационной среде

| Параметр | Значение |
|---|---|
| Операционная система сервера | Ubuntu Linux 24.04 |
| Среда выполнения бэкенда | Java 21 (JRE headless), процесс под управлением `systemd` |
| СУБД | PostgreSQL 16 (образ `postgres:16-alpine` в Docker), слушает `127.0.0.1:5432` |
| Веб-сервер (reverse proxy) | nginx, терминирует HTTPS |
| Сертификат TLS | Let's Encrypt, выпуск и продление — `certbot` |
| Домен | DuckDNS (бесплатный DNS-провайдер) |

Бэкенд — единственный процесс, отвечающий одновременно за REST API, Telegram-бота
(long-polling) и отдачу веб-страницы. Он слушает исключительно `127.0.0.1:8080` —
не имеет прямого доступа из интернета; единственная публичная точка входа — nginx
на портах 80/443.

## 2. Первичное развёртывание на новом сервере

```bash
# 1. Postgres в Docker
docker run -d --name expenses-pg --restart unless-stopped \
  -e POSTGRES_PASSWORD=<пароль> -e POSTGRES_DB=expenses \
  -p 127.0.0.1:5432:5432 -v expenses_pgdata:/var/lib/postgresql/data postgres:16-alpine

# 2. Java, служебный пользователь, каталоги
apt-get install -y openjdk-21-jre-headless
useradd -r -s /usr/sbin/nologin expenses
mkdir -p /etc/expenses /opt/expenses
chown -R expenses:expenses /opt/expenses

# 3. Секреты — файл /etc/expenses/expenses.env (chmod 600):
#    DB_URL, DB_USER, DB_PASSWORD, BOT_TOKEN, API_TOKEN, (необязательно) GROQ_API_KEY

# 4. Jar-файл бэкенда (собирается на машине разработчика: ./gradlew buildFatJar)
#    заливается в /opt/expenses/expenses-backend-all.jar

# 5. systemd-юнит /etc/systemd/system/expenses-backend.service, затем:
systemctl daemon-reload && systemctl enable --now expenses-backend
curl -s http://127.0.0.1:8080/health   # ожидается {"status":"ok"}

# 6. nginx + сертификат
apt-get install -y nginx certbot python3-certbot-nginx
# создать server-блок (server_name = домен, proxy_pass http://127.0.0.1:8080)
certbot --nginx -d <домен> --agree-tos -m <email> --redirect
```

Полная и всегда актуальная версия этой инструкции с реальными путями и последним
фактическим состоянием — `docs/deploy.md` в репозитории проекта.

## 3. Обновление версии бэкенда

1. На машине разработчика собрать новый jar: `./gradlew buildFatJar`.
2. Скопировать файл на сервер (`scp`) в `/opt/expenses/expenses-backend-all.jar`,
   восстановить владельца (`chown expenses:expenses`).
3. Перезапустить службу: `systemctl restart expenses-backend`.
4. Проверить: `curl -s http://127.0.0.1:8080/health` → `{"status":"ok"}`.

Схема данных обновляется автоматически при старте (идемпотентные миграции —
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`), отдельного шага не требуется.

## 4. Обновление Android-приложения

Собрать APK (`./gradlew assembleDebug` для тестовой сборки) и переустановить на
устройстве. Адрес сервера задан константой в коде клиента — при смене домена сервера
требуется пересборка приложения.

## 5. Резервное копирование данных

```bash
# разовый дамп
docker exec expenses-pg pg_dump -U postgres expenses | gzip > expenses-$(date +%F).sql.gz

# восстановление (перезапишет текущие данные!)
systemctl stop expenses-backend
gunzip -c expenses-ДАТА.sql.gz | docker exec -i expenses-pg psql -U postgres expenses
systemctl start expenses-backend
```

Рекомендуется ежедневный автоматический дамп через `cron` с хранением ограниченного
числа последних копий — готовый скрипт и инструкция по включению — `docs/backup.md`.
Дампы содержат токены аккаунтов (то есть являются секретом) и должны храниться как
секрет, желательно — с копией за пределами того же сервера.

## 6. Мониторинг работоспособности

- `systemctl status expenses-backend` — состояние процесса.
- `journalctl -u expenses-backend -f` — журнал работы (запуск, ошибки, сообщения бота
  и классификатора).
- `curl -s https://<домен>/health` — внешняя проверка живости через nginx и TLS.
- `df -h` — контроль свободного места на диске (особенно важно на серверах с
  ограниченным объёмом хранилища).

## 7. Безопасность эксплуатации

- Секреты (`expenses.env`) — права доступа `600`, владелец — служебный пользователь;
  не хранятся в системе контроля версий.
- Бэкенд принципиально не открыт наружу напрямую — только через nginx (HTTPS).
- Регулярное продление сертификата — автоматическое (`certbot.timer`), не требует
  вмешательства администратора при штатной работе.
- Резервные копии базы данных содержат токены доступа пользователей — обращаться с
  ними как с секретом.

## 8. Типовые неисправности и их устранение

| Симптом | Вероятная причина | Действие |
|---|---|---|
| `/health` не отвечает | Служба бэкенда не запущена или упала | `systemctl status expenses-backend`, смотреть журнал |
| Ошибка подключения к БД в журнале | Контейнер `expenses-pg` не запущен | `docker ps`, `docker start expenses-pg` |
| Сертификат просрочен | Таймер продления не сработал | `certbot renew`, проверить `systemctl status certbot.timer` |
| Категория трат не проставляется автоматически | Не задан или недействителен `GROQ_API_KEY` | Проверить журнал на строку «Классификатор ИИ выключен/включён»; обновить ключ в `expenses.env`, перезапустить службу |
| Диск заполнен | Логи, кэш пакетов, старые Docker-образы | `journalctl --vacuum-time=7d`, `apt-get clean`, `docker image prune` |
