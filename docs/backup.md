# Бэкапы базы данных

Данные (все траты, аккаунты, бюджеты) живут в PostgreSQL — в docker-контейнере
`expenses-pg` на VPS. Если контейнер/диск умрёт, данные пропадут. Ниже — простой
и надёжный бэкап средствами `pg_dump` (логический дамп: текстовый SQL, из которого
базу можно полностью восстановить).

Все команды выполняются на сервере (`ssh root@130.49.176.80` — сервер сменился 2026-09-15,
см. `deploy.md`; Claude теперь может заходить сам по SSH-ключу).

## Разовый бэкап (вручную)

```bash
mkdir -p /root/backups
docker exec expenses-pg pg_dump -U postgres expenses | gzip > /root/backups/expenses-$(date +%F).sql.gz
```

- `docker exec expenses-pg …` — запускаем `pg_dump` внутри контейнера с базой.
- `-U postgres expenses` — пользователь `postgres`, база `expenses`.
- `| gzip` — сжимаем (дамп хорошо жмётся), `$(date +%F)` — дата в имени файла (`2026-07-19`).

Проверить, что файл не пустой:

```bash
ls -lh /root/backups/
```

## Восстановление из бэкапа

⚠️ Перезапишет текущие данные. Сначала желательно остановить бэкенд, чтобы никто не писал:

```bash
systemctl stop expenses-backend
gunzip -c /root/backups/expenses-2026-07-19.sql.gz | docker exec -i expenses-pg psql -U postgres expenses
systemctl start expenses-backend
```

## Автоматический бэкап по расписанию (cron)

Ежедневно в 3:30 ночи, с хранением последних 14 дней (старые удаляются).

Создать скрипт `/root/backup-expenses.sh`:

```bash
cat > /root/backup-expenses.sh <<'SH'
#!/bin/bash
set -e
DIR=/root/backups
mkdir -p "$DIR"
docker exec expenses-pg pg_dump -U postgres expenses | gzip > "$DIR/expenses-$(date +%F).sql.gz"
# Удалить дампы старше 14 дней.
find "$DIR" -name 'expenses-*.sql.gz' -mtime +14 -delete
SH
chmod +x /root/backup-expenses.sh
```

Добавить в crontab (`crontab -e`) строку:

```
30 3 * * * /root/backup-expenses.sh >> /root/backups/backup.log 2>&1
```

Проверить, что скрипт работает, запустив его вручную один раз:

```bash
/root/backup-expenses.sh && ls -lh /root/backups/
```

## Что стоит помнить

- Бэкапы лежат на **том же сервере** — от потери всего VPS это не спасёт. Для настоящей
  надёжности стоит иногда копировать дампы на другую машину (`scp` к себе на ПК) или
  во внешнее хранилище.
- Дамп содержит **токены аккаунтов** (это доступ к данным) — храни файлы бэкапов как секрет.
