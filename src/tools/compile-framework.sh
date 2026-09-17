#!/usr/bin/env bash
# compile-framework.sh — cross-platform alternative to compile-framework.ps1
# Usage: bash tools/compile-framework.sh <framework-dir>

set -euo pipefail

FRAMEWORK="${1:-../mario_ai_framework}"
BIN="$FRAMEWORK/bin"
SRC="$FRAMEWORK/src"

echo "Installing custom tools..."
mkdir -p "$BIN"
cp -f "tools/ValidateLevels.java" "$SRC/ValidateLevels.java"
cp -f "tools/PlayHuman.java"      "$SRC/PlayHuman.java"
cp -f "tools/LevelSelector.java"  "$SRC/LevelSelector.java"

echo "Compiling framework and tools in $SRC..."
mapfile -t java_files < <(find "$SRC" -name "*.java")

if [ "${#java_files[@]}" -eq 0 ]; then
    echo "ERROR: No Java source files found in $SRC" >&2
    exit 1
fi

javac -d "$BIN" -encoding UTF-8 "${java_files[@]}"
echo "Compilation complete."
