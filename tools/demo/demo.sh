#!/usr/bin/env bash
# Interactive first-run helper. Thin wrapper around npm scripts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DEMO_DIR="$ROOT/.demo"
SESSION="$DEMO_DIR/session.json"
PUBLIC_APK_URL="${PUBLIC_APK_URL:-https://wlya.potoki.pro/u}"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { echo ""; bold "→ $*"; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

pick_goal() {
  echo "What do you want to try?"
  echo "  1) Quick tunnel demo (Gateway + desktop-ui)"
  echo "  2) Remote phone control (Control tab + screenshot)"
  echo "  3) Agent MCP (phone-control-mcp on :18083)"
  echo "  4) Voice / walkie (Voice tab — no GSM)"
  echo "  5) Lab only — install stack, skip phone wizard"
  read -r -p "Choice [1]: " choice
  case "${choice:-1}" in
    1) GOAL=tunnel ;;
    2) GOAL=control ;;
    3) GOAL=mcp ;;
    4) GOAL=voice ;;
    5) GOAL=stack ;;
    *) GOAL=tunnel ;;
  esac
}

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 8
  else
    date +%s | sha256sum | cut -c1-16
  fi
}

write_session() {
  mkdir -p "$DEMO_DIR"
  local channel secret
  channel="bekon-demo-$(random_hex)"
  secret="$(random_hex)"
  cat >"$SESSION" <<EOF
{
  "channel": "$channel",
  "secret": "$secret",
  "relayUrl": "${RELAY_URL:-http://127.0.0.1:18081}",
  "desktopUi": "http://127.0.0.1:5174",
  "desktopApi": "http://127.0.0.1:18080",
  "phoneManager": "http://127.0.0.1:18082",
  "goal": "$GOAL"
}
EOF
  step "Demo credentials written to .demo/session.json"
  echo "  Channel: $channel"
  echo "  Secret:  $secret"
}

start_stack() {
  step "Starting relay (docker compose)…"
  npm run relay:compose
  step "Starting desktop-ui + wlya-tunnel + phone-control-api…"
  npm run stack:start -- --bg
  sleep 2
  npm run stack:status || true
}

phone_hints() {
  step "Phone setup"
  echo "  1. Enable Developer options + USB debugging"
  echo "  2. Connect USB — run: npm run gateway:fix-adb  (Linux, once)"
  echo "  3. Install Gateway APK:"
  echo "     USB:  npm run gateway:deploy"
  echo "     URL:  $PUBLIC_APK_URL"
  echo "  4. On the phone: enter Channel + Secret from above"
  echo "     Enable «Accept advertised adapters» → Start All"
  echo "  5. On desktop-ui: create tunnel with same Channel/Secret,"
  echo "     add WLYA Server adapter, start tunnel, advertise adapters"
}

mcp_hints() {
  step "Phone MCP"
  echo "  npm run phone-control-mcp"
  echo "  Then: curl -s http://127.0.0.1:18083/mcp | head"
  echo "  See apps/phone-control-mcp/README.md"
}

voice_hints() {
  step "Voice / walkie"
  echo "  Open desktop-ui → Voice tab"
  echo "  Room + Secret must match on both ends"
  echo "  Walkie mode — no root required for a first test"
}

main() {
  need_cmd npm
  bold "Bekon demo wizard"
  pick_goal
  write_session
  start_stack
  phone_hints
  case "$GOAL" in
    mcp) mcp_hints ;;
    voice) voice_hints ;;
    control) echo "  Open desktop-ui → Control tab after tunnel is up." ;;
  esac
  step "Done"
  echo "  desktop-ui: http://127.0.0.1:5174 (port may differ — npm run stack:status)"
  echo "  Full command list: docs/COMMANDS.md"
}

main "$@"
