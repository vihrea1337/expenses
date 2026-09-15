# Деплой на VPS — как реально устроено (факт на 2026-09-15)

Документ фиксирует, **как бэкенд Expenses развёрнут на VPS**, чтобы будущие правки не сломали соседей (VPN). Историю переезда (старый сервер, старый домен) см. в конце файла.

## Что на сервере

- **ОС:** Ubuntu 24.04, вход под `root`. Публичный IP `130.49.176.80`.
- Это слабый сервер (диск ~8.7 ГБ) — на нём **только** VPN (Xray VLESS+WS, контейнер `vless-ws-piter-ip`, TLS для него терминирует внешний Nginx Proxy Manager в Питере, `films.litra.su` — не трогать) и Expenses. Больше ничего — сервер держится «чистым» специально.
- Expenses НЕ докеризован (кроме БД): бэкенд — обычный systemd-сервис на хосте, слушает `127.0.0.1:8080`; перед ним — **свой** nginx (не общий стек, как было раньше), сам терминирует TLS для домена Expenses.
- **Postgres** — в docker, контейнер `expenses-pg` (`postgres:16-alpine`), слушает `127.0.0.1:5432`, том `expenses_pgdata`.

## Наш бэкенд

- **Домен:** `vihreaexpenses.duckdns.org` (DuckDNS → `130.49.176.80`).
- **jar:** `/opt/expenses/expenses-backend-all.jar` (заливается через `scp` с ПК; владелец — служебный пользователь `expenses`).
- **Секреты:** `/etc/expenses/expenses.env` (chmod 600) — `DB_URL`, `DB_USER`, `DB_PASSWORD`, `BOT_TOKEN`, `API_TOKEN`. `GROQ_API_KEY` пока не задан — ИИ-категоризация не работает, всё остальное работает (это ожидаемо, см. `architecture.md`).
- **Служба:** `systemd`-юнит `/etc/systemd/system/expenses-backend.service` — `User=expenses`, `ExecStart=/usr/bin/java -jar /opt/expenses/expenses-backend-all.jar`, `Restart=on-failure`. Слушает `127.0.0.1:8080`.
- **nginx:** свой конфиг `/etc/nginx/sites-available/expenses` (симлинк в `sites-enabled`) — `server_name vihreaexpenses.duckdns.org`, `proxy_pass http://127.0.0.1:8080`. Отдельный от VPN — тот наружу не через локальный nginx (см. выше).
- **TLS:** Let's Encrypt через `certbot --nginx` (сам правит конфиг nginx, добавляет `listen 443 ssl` и редирект с 80). Автопродление — `certbot.timer` (systemd, уже включён).
- **Бот:** long-polling (не webhook) — доменом/сертификатом для бота не пользуется, только для REST API приложения/веб-страницы.

## Порядок обновления бэкенда (когда jar изменится)

1. На ПК: `buildFatJar` → `expenses-backend-all.jar`.
2. `scp` его в `/opt/expenses/` (владелец должен остаться `expenses:expenses`, `chown` после копирования).
3. На сервере: `systemctl restart expenses-backend` → проверить `curl -s http://127.0.0.1:8080/health`.

## Как безопасно менять nginx.conf

1. Бэкап: `cp /etc/nginx/sites-available/expenses /etc/nginx/sites-available/expenses.bak`.
2. Править прямо на сервере (конфиг маленький, свой, без общего стека — в отличие от старой схемы, тут не нужно готовить файл на ПК и слать `scp`).
3. Проверить: `nginx -t`.
4. Применить: `systemctl reload nginx` (мягкий, соединения не рвёт; при ошибке `nginx -t` не даст применить битый конфиг).
5. Проверить: `curl -s https://vihreaexpenses.duckdns.org/health`.

## Доступ Claude к серверу (изменение политики, 2026-09-15)

**До 2026-09-15** в `CLAUDE.md` было написано «у Claude доступа к VPS нет» — серверные шаги пользователь выполнял сам по инструкциям. **Пользователь явно решил это изменить** для переезда на новый сервер: сгенерирован отдельный ed25519 SSH-ключ, публичная часть добавлена в `/root/.ssh/authorized_keys` на `130.49.176.80`. С этого момента Claude может сам заходить по SSH на **этот** сервер и выполнять серверные операции напрямую (что и сделано: диагностика, бэкап, чистка, установка Java/nginx/certbot/Postgres, деплой jar, nginx+сертификат — всё выполнено Claude по SSH в этой сессии, без передачи команд пользователю). Ключ лежит локально на ПК пользователя (не в git); приватная часть Claude не публикуется.

---

## История переезда (что было раньше)

### Старый сервер Expenses (утрачен, факт до 2026-09-15)
Раньше бэкенд жил на `89.125.30.242` (домен `sashlevhealth.duckdns.org`), в общем docker-compose стеке `vpn-stack` с VPN (3x-ui/Xray Reality) и сайтом-визиткой — один nginx на 443 разводил трафик по SNI. **Доступ к этому серверу утрачен** (просрочен) до того, как был сделан бэкап БД — реальные траты и аккаунты, накопленные там, **не восстановлены**, на новом сервере база создана с нуля. Сайт-визитку решили не переносить вообще.

### Перенос на новый сервер (с нуля) — актуальная инструкция
Готовые артефакты — в папке [`deploy/`](../deploy):

| Файл | Назначение |
|---|---|
| `deploy/expenses.env.example` | шаблон секретов → `/etc/expenses/expenses.env` |
| `deploy/expenses-backend.service` | systemd-юнит (с `EnvironmentFile`) → `/etc/systemd/system/` |
| `deploy/nginx-expenses.conf` | справочный server-блок под **старую** схему (общий стек с SNI-роутером) — на факте 2026-09-15 использован свой отдельный конфиг, см. выше |

Шаги на чистом сервере (то, что реально было сделано 2026-09-15):
```bash
# 1) Postgres в docker
docker run -d --name expenses-pg --restart unless-stopped \
  -e POSTGRES_PASSWORD=<пароль> -e POSTGRES_DB=expenses \
  -p 127.0.0.1:5432:5432 -v expenses_pgdata:/var/lib/postgresql/data postgres:16-alpine

# 2) Секреты, jar, служба
mkdir -p /etc/expenses /opt/expenses
cp expenses.env /etc/expenses/ && chmod 600 /etc/expenses/expenses.env
cp expenses-backend-all.jar /opt/expenses/      # собрать: ./gradlew buildFatJar
apt-get install -y openjdk-21-jre-headless
id expenses &>/dev/null || useradd -r -s /usr/sbin/nologin expenses
chown -R expenses:expenses /opt/expenses
cp deploy/expenses-backend.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now expenses-backend
curl -s http://127.0.0.1:8080/health            # {"status":"ok"}

# 3) nginx + certbot (свой, не общий стек)
apt-get install -y nginx certbot python3-certbot-nginx
# создать /etc/nginx/sites-available/expenses (server_name + proxy_pass http://127.0.0.1:8080),
# включить симлинком в sites-enabled, nginx -t && systemctl reload nginx
certbot --nginx -d vihreaexpenses.duckdns.org --agree-tos -m <email> --redirect
```
