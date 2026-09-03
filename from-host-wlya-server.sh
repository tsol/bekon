#!/bin/bash
exec "$(cd "$(dirname "$0")" && pwd)/packages/wlya-server/scripts/relay" "$@"
