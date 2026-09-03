#!/bin/bash
exec "$(cd "$(dirname "$0")" && pwd)/tools/adb/gateway" "$@"
