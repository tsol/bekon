#!/usr/bin/env bash
# WLYA-Server — Универсальный скрипт деплоя
# Скопировать, заполнить переменные в начале, запустить.
# Не содержит секретов — все значения задаются через переменные.

set -euo pipefail
IFS=$'\n\t'

###############################################################################
# 1. ПЕРЕМЕННЫЕ — заполнить перед запуском
###############################################################################

# --- DNS / Cloudflare ---
CF_API_TOKEN=""           # Cloudflare API Token (Zone:DNS:Edit)
CF_ZONE_NAME=""           # например: potoki.pro
CF_SUBDOMAIN=""           # например: wlya
CF_ZONE_ID=""             # опционально: если пусто — ищется через API

# --- Сервер ---
SERVER_IP=""              # публичный IP сервера
SERVER_USER="root"        # SSH пользователь
SSH_PASS=""               # пароль (если password auth) или оставить пустым для key auth

# --- Домен и пути ---
DOMAIN=""                 # полный домен: ${CF_SUBDOMAIN}.${CF_ZONE_NAME}
SERVER_APP_DIR=""         # например: /opt/wlya-server/app
SERVER_NGINX_AVAIL=""     # /etc/nginx/sites-available/${DOMAIN}

# --- Docker ---
COMPOSE_PORT=""           # порт на хосте для Docker bind, например: 18081

# --- Уведомления / email для certbot ---
ADMIN_EMAIL=""            # admin@${CF_ZONE_NAME}

###############################################################################
# 2. ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
###############################################################################

log() { echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# SSH-выполнение команды на сервере
run_ssh() {
    if [ -n "${SSH_PASS:-}" ]; then
        sshpass -e ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
            "${SERVER_USER}@${SERVER_IP}" "$@"
    else
        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
            "${SERVER_USER}@${SERVER_IP}" "$@"
    fi
}

# Rsync с SSH
run_rsync() {
    local src="$1" dst="$2"
    if [ -n "${SSH_PASS:-}" ]; then
        export SSHPASS="${SSH_PASS}"
        rsync -avz --delete -e 'sshpass -e ssh -o StrictHostKeyChecking=no' "$src" "$dst"
    else
        rsync -avz --delete -e 'ssh -o StrictHostKeyChecking=no' "$src" "$dst"
    fi
}

###############################################################################
# D1. СОЗДАНИЕ DNS ЗОНЫ (Cloudflare)
###############################################################################

step_dns() {
    log "=== D1: DNS A-запись ==="

    [ -n "$CF_API_TOKEN" ] || die "CF_API_TOKEN не задан"
    [ -n "$CF_ZONE_NAME" ] || die "CF_ZONE_NAME не задан"
    [ -n "$CF_SUBDOMAIN" ] || die "CF_SUBDOMAIN не задан"
    [ -n "$SERVER_IP" ] || die "SERVER_IP не задан"

    local zone_id="$CF_ZONE_ID"

    # Если ZONE_ID не задан — ищем через API
    if [ -z "$zone_id" ]; then
        zone_id=$(curl -s "https://api.cloudflare.com/client/v4/zones" \
            -H "Authorization: Bearer ${CF_API_TOKEN}" \
            | python3 -c "import sys, json; zones = json.load(sys.stdin)['result']; print(next((z['id'] for z in zones if z['name'] == '$CF_ZONE_NAME'), ''))")
        [ -n "$zone_id" ] || die "Zone ID для $CF_ZONE_NAME не найден"
        log "Zone ID найден: $zone_id"
    fi

    # Создаём A-запись
    local resp_code
    resp_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        "https://api.cloudflare.com/client/v4/zones/${zone_id}/dns_records" \
        -H "Authorization: Bearer ${CF_API_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"type\":\"A\",\"name\":\"${CF_SUBDOMAIN}\",\"content\":\"${SERVER_IP}\",\"ttl\":120,\"proxied\":true}")

    if [ "$resp_code" = "200" ] || [ "$resp_code" = "400" ]; then
        log "DNS A-запись создана (или уже существует)"
    else
        die "Ошибка создания DNS записи: HTTP $resp_code"
    fi

    # Ждём propagation (опционально)
    log "Ожидание DNS propagation (10 сек)..."
    sleep 10
}

###############################################################################
# D2. ПРОВЕРКА / УСТАНОВКА DOCKER
###############################################################################

step_docker_install() {
    log "=== D2: Docker Engine ==="

    run_ssh '
        if ! command -v docker &> /dev/null; then
            echo "Installing Docker..."
            apt-get update -qq
            apt-get install -y -qq ca-certificates curl gnupg lsb-release
            install -m 0755 -d /etc/apt/keyrings
            curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
            chmod a+r /etc/apt/keyrings/docker.gpg
            echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
                > /etc/apt/sources.list.d/docker.list
            apt-get update -qq
            apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            systemctl enable --now docker
        fi
        docker --version
        docker compose version
    '
}

###############################################################################
# D3. СОЗДАНИЕ ПАПОК НА СЕРВЕРЕ
###############################################################################

step_dirs() {
    log "=== D3: Создание директорий ==="
    [ -n "$SERVER_APP_DIR" ] || die "SERVER_APP_DIR не задан"
    run_ssh "mkdir -p ${SERVER_APP_DIR}"
}

###############################################################################
# D4. ЗАЛИВКА КОДА + СБОРКА DOCKER
###############################################################################

step_deploy_code() {
    log "=== D4: Заливка кода и сборка ==="

    local local_dir="${LOCAL_DIR:-$(pwd)}"
    run_rsync \
        "${local_dir}/" \
        "${SERVER_USER}@${SERVER_IP}:${SERVER_APP_DIR}/"

    run_ssh "cd ${SERVER_APP_DIR} && docker compose build"
    log "Docker образ собран"
}

###############################################################################
# D5. NGINX КОНФИГ
###############################################################################

step_nginx() {
    log "=== D5: nginx конфигурация ==="

    [ -n "$SERVER_NGINX_AVAIL" ] || die "SERVER_NGINX_AVAIL не задан"
    [ -n "$COMPOSE_PORT" ] || die "COMPOSE_PORT не задан"
    [ -n "$DOMAIN" ] || die "DOMAIN не задан"

    # Проверяем наличие map-директивы в nginx.conf для WS upgrades
    run_ssh '
        if ! grep -q "connection_upgrade" /etc/nginx/nginx.conf; then
            echo "Добавляем map \$http_upgrade \$connection_upgrade в nginx.conf..."
            sed -i "/^http {/a\\    map \$http_upgrade \$connection_upgrade { default upgrade; \"\" close; }" /etc/nginx/nginx.conf
            nginx -t && systemctl reload nginx
        else
            echo "map \$http_upgrade \$connection_upgrade уже есть"
        fi
    '

    # Создаём vhost
    local nginx_conf
    nginx_conf="server {
    server_name ${DOMAIN};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        proxy_pass http://127.0.0.1:${COMPOSE_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        client_max_body_size 8m;
        proxy_buffering off;
        proxy_cache off;
    }

    listen 80;
    listen [::]:80;
}"

    # Заливаем через base64 (безопасно для спецсимволов)
    local b64
    b64=$(echo -n "$nginx_conf" | base64 -w0)
    run_ssh "echo '${b64}' | base64 -d > ${SERVER_NGINX_AVAIL}"
    run_ssh "ln -sf ${SERVER_NGINX_AVAIL} /etc/nginx/sites-enabled/"
    run_ssh "nginx -t && systemctl reload nginx"
    log "nginx vhost создан и активирован"
}

###############################################################################
# D6. SSL (Let's Encrypt via certbot)
###############################################################################

step_ssl() {
    log "=== D6: SSL сертификат ==="
    [ -n "$ADMIN_EMAIL" ] || die "ADMIN_EMAIL не задан"
    [ -n "$DOMAIN" ] || die "DOMAIN не задан"

    run_ssh "certbot --nginx -d ${DOMAIN} --non-interactive --agree-tos -m ${ADMIN_EMAIL}" || {
        log "WARNING: certbot упал. Возможно, DNS ещё не распространился. Повторите через несколько минут."
        return 1
    }
    log "SSL активен для ${DOMAIN}"
}

###############################################################################
# D7. ЗАПУСК DOCKER COMPOSE
###############################################################################

step_start() {
    log "=== D7: Запуск контейнеров ==="
    run_ssh "cd ${SERVER_APP_DIR} && docker compose up -d"

    # Проверка health endpoint
    log "Проверка health endpoint..."
    run_ssh "curl -sS http://127.0.0.1:${COMPOSE_PORT}/health" || die "Health check failed"
    log "Контейнеры запущены и healthy"
}

###############################################################################
# D8. SYSTEMD (автозапуск)
###############################################################################

step_systemd() {
    log "=== D8: systemd unit ==="
    [ -n "$SERVER_APP_DIR" ] || die "SERVER_APP_DIR не задан"

    run_ssh "cat > /etc/systemd/system/wlya-server.service << 'EOF'
[Unit]
Description=WLYA Server Message Relay
After=network.target docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${SERVER_APP_DIR}
ExecStart=/usr/bin/docker compose up -d
TimeoutStartSec=120

[Install]
WantedBy=multi-user.target
EOF
"
    run_ssh "systemctl daemon-reload && systemctl enable --now wlya-server"
    log "systemd unit: enabled + started"
}

###############################################################################
# D9. ПРОВЕРКИ
###############################################################################

step_verify() {
    log "=== D9: Финальная проверка ==="

    echo ""
    echo "--- HTTPS Health ---"
    curl -sL -H "User-Agent: Mozilla/5.0" "https://${DOMAIN}/health" || die "HTTPS health failed"

    echo ""
    echo "--- HTTP→HTTPS Redirect ---"
    curl -sL -o /dev/null -w "status=%{http_code}, final_url=%{url_effective}\n" \
        -H "User-Agent: Mozilla/5.0" "http://${DOMAIN}/health"

    echo ""
    echo "--- Docker Status ---"
    run_ssh "cd ${SERVER_APP_DIR} && docker compose ps"

    echo ""
    echo "--- Metrics ---"
    curl -sL -H "User-Agent: Mozilla/5.0" "https://${DOMAIN}/metrics" | head -4

    echo ""
    log "✅ Деплой завершён! ${DOMAIN}"
}

###############################################################################
# MAIN — раскомментировать нужные шаги
###############################################################################

# step_dns
# step_docker_install
# step_dirs
# step_deploy_code
# step_nginx
# step_ssl
# step_start
# step_systemd
# step_verify

# Или выполнить все:
# main() {
#     step_dns
#     step_docker_install
#     step_dirs
#     step_deploy_code
#     step_nginx
#     step_ssl
#     step_start
#     step_systemd
#     step_verify
# }
# main
###############################################################################
# ИСПОЛЬЗОВАНИЕ
###############################################################################
#
# 1. Заполнить все переменные в секции 1 выше.
# 2. Убедиться, что на локальной машине установлены: ssh, rsync, curl, python3, base64.
#    Для password-auth также нужен: sshpass (apt-get install sshpass).
# 3. Запустить нужные шаги по очереди (или раскомментировать main()).
#
# Пример последовательного запуска:
#
#   CF_API_TOKEN="xxx" \
#   CF_ZONE_NAME="potoki.pro" \
#   CF_SUBDOMAIN="wlya" \
#   SERVER_IP="37.60.235.35" \
#   SERVER_USER="root" \
#   SSH_PASS="xxx" \
#   DOMAIN="relay.example" \
#   SERVER_APP_DIR="/opt/wlya-server/app" \
#   SERVER_NGINX_AVAIL="/etc/nginx/sites-available/relay.example" \
#   COMPOSE_PORT="18081" \
#   ADMIN_EMAIL="admin@potoki.pro" \
#   ./deploy_example.sh
#
# Или запускать шаги по одному, комментируя остальные.
#
###############################################################################
