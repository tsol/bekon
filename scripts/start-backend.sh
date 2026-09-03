#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

export JAVA_HOME="/opt/data/.toolchain/amazon-corretto-17.0.20.8.1-linux-x64"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_DIR"
exec ./gradlew :wlya-desktop:run "$@"
