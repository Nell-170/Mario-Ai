#!/usr/bin/env bash
# apply-framework-patch.sh — applies tools/framework.patch to the cloned framework.
# Usage: bash tools/apply-framework-patch.sh <framework-dir>

set -euo pipefail

FRAMEWORK="${1:-../mario_ai_framework}"
PATCH="$(cd "$(dirname "$0")" && pwd)/framework.patch"

cd "$FRAMEWORK"
if git apply --ignore-whitespace --reverse --check "$PATCH" 2>/dev/null; then
    echo "Framework patch already applied."
elif git apply --ignore-whitespace "$PATCH"; then
    echo "Framework patch applied."
else
    echo "ERROR: could not apply tools/framework.patch to $FRAMEWORK" >&2
    exit 1
fi
