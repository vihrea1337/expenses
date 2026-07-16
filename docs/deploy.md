# Деплой на VPS — как реально устроено (факт на 2026-07-16)

Документ фиксирует, **как бэкенд Expenses развёрнут на VPS**, чтобы будущие правки не сломали соседей (VPN, сайт). Все серверные шаги делает пользователь по SSH; у Claude доступа к серверу нет.

## Что на сервере

- **ОС:** Ubuntu 24.04, вход под `root`. Публичный IP `89.125.30.242`.
- **Это docker-compose стек** в `/root/vpn/vpn-stack` (репозиторий VPN-стенда, отдельный от Expenses). Контейнеры:
  | Контейнер | Образ | Роль |
  |---|---|---|
  | `nginx-proxy` | `nginx:stable` | reverse-proxy, **`network_mode: host`**, держит порты 80/443 |
  | `portfolio-web-1` | `portfolio-web` | сайт пользователя, слушает `127.0.0.1:3000` |
  | `3x-ui` + `xray` | `mhsanaei/3x-ui` | VPN (Xray Reality), панель управления |

- **`nginx-proxy` работает в host-сети** — поэтому для него `127.0.0.1` = loopback самого сервера. Отсюда он проксирует на `127.0.0.1:3000` (сайт), `127.0.0.1:8443` (Xray) и `127.0.0.1:8080` (наш Ktor). Наш бэкенд поэтому НЕ нужно докеризовать: он крутится на хосте, и nginx его видит напрямую.

## Как nginx на 443 разводит трафик

Порт 443 обслуживается в два слоя (файл `/root/vpn/vpn-stack/reverse-proxy/nginx.conf`, **правится руками** — bootstrap.sh его не генерирует):

1. `stream {}` + `ssl_preread` — читает имя домена (SNI) **не расшифровывая TLS** и маршрутизирует:
   - SNI `yandex.ru` → Xray (`127.0.0.1:8443`) — маскировка VPN;
   - всё остальное → `site_tls` (`127.0.0.1:8081`).
2. `http {}` на `127.0.0.1:8081 ssl` — терминирует TLS и по `server_name` выбирает бэкенд:
   - `sashlev.duckdns.org` → сайт (`127.0.0.1:3000`);
   - `sashlevhealth.duckdns.org` → **наш Ktor** (`127.0.0.1:8080`).

Наш поддомен попадает в ветку «всё остальное», поэтому VPN-блок трогать не нужно.

## Наш бэкенд

- **Домен:** `sashlevhealth.duckdns.org` (DuckDNS → `89.125.30.242`).
- **jar:** `/opt/expenses/expenses-backend-all.jar` (заливается через `scp` с ПК; владелец — служебный пользователь `expenses`).
- **Служба:** `systemd` юнит `/etc/systemd/system/expenses-backend.service` — `User=expenses`, `ExecStart=/usr/bin/java -jar /opt/expenses/expenses-backend-all.jar`, `Restart=on-failure`. Слушает `127.0.0.1:8080`.
- **TLS:** Let's Encrypt, выпущен `certbot certonly --webroot -w /root/vpn/vpn-stack/reverse-proxy/certbot/www -d sashlevhealth.duckdns.org --deploy-hook "docker exec nginx-proxy nginx -s reload"`. Автопродление — `certbot.timer`, сертификаты в `/etc/letsencrypt/live/sashlevhealth.duckdns.org/`.
- **nginx-блок:** один `server { listen 127.0.0.1:8081 ssl; server_name sashlevhealth.duckdns.org; ... proxy_pass http://127.0.0.1:8080; }` в `reverse-proxy/nginx.conf`.

## Порядок обновления бэкенда (когда jar изменится)

1. На ПК: `buildFatJar` → `expenses-backend-all.jar`.
2. `scp` его в `/opt/expenses/` (можно во временное имя, потом `mv`).
3. На сервере: `systemctl restart expenses-backend` → проверить `curl -s http://127.0.0.1:8080/health`.

## Как безопасно менять nginx.conf

1. Бэкап: `cp reverse-proxy/nginx.conf reverse-proxy/nginx.conf.bak`.
2. **Не пастить большой конфиг в SSH-терминал** — вставка искажается. Готовить файл на ПК и слать `scp` во временное имя, затем `cat tmp > nginx.conf` (перезапись **на месте** сохраняет inode, иначе host-bind-mount контейнера не увидит новый файл).
3. Проверять и **синтаксис, и структуру**: `docker exec nginx-proxy nginx -t` (синтаксис) + `grep -nE 'stream \{|http \{|server_name' nginx.conf` (что http-блок и все server_name на месте — `nginx -t` проходит даже без http-блока!).
4. Применить: `docker exec nginx-proxy nginx -s reload` (мягкий, соединения не рвёт; при ошибке не применяется).
5. Проверить: `curl -s https://sashlevhealth.duckdns.org/health` и что сайт отдаёт 200.
