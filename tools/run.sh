#!/usr/bin/env bash
# Load optional .env.deploy and exec repo tooling.
# Usage: tools/run.sh relay <start|stop|status|deploy|publish-apk>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env.deploy" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env.deploy"
  set +a
fi

TARGET="${1:-}"
shift || true

case "$TARGET" in
  relay)
    exec bash "$ROOT/packages/wlya-server/scripts/relay" "$@"
    ;;
  "")
    cat <<EOF
Usage: $0 <target> <command> [args…]

Targets:
  relay   packages/wlya-server/scripts/relay (start|stop|status|deploy|publish-apk)

Optional env file: .env.deploy (see .env.deploy.example)
EOF
    exit 1
    ;;
  *)
    echo "Unknown target: $TARGET" >&2
    exit 1
    ;;
esac
