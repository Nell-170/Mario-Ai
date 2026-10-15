#!/usr/bin/env bash
# install.sh — Mario AI Unified Installer Launcher
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -x "$SCRIPT_DIR/.venv/bin/python" ]; then
    PYTHON="$SCRIPT_DIR/.venv/bin/python"
else
    PYTHON="${PYTHON:-python3}"
    if ! command -v "$PYTHON" &>/dev/null; then
        PYTHON="python"
    fi
fi

exec "$PYTHON" "$SCRIPT_DIR/install.py" "$@"
