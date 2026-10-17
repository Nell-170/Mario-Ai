#!/usr/bin/env bash
# compile-framework.sh — cross-platform alternative to compile-framework.ps1
# Usage: bash tools/compile-framework.sh <framework-dir>

set -euo pipefail

FRAMEWORK="${1:-../mario_ai_framework}"
BIN="$FRAMEWORK/bin"
SRC="$FRAMEWORK/src"

echo -e "\033[1;36m[ 25%]\033[0m \033[1;32m[█████░░░░░░░░░░░░░░░]\033[0m Aplicando parches al framework..."
bash "$(dirname "$0")/apply-framework-patch.sh" "$FRAMEWORK"

echo -e "\033[1;36m[ 50%]\033[0m \033[1;32m[██████████░░░░░░░░░░]\033[0m Instalando herramientas Java personalizadas..."
mkdir -p "$BIN"
cp -f "tools/ValidateLevels.java" "$SRC/ValidateLevels.java"
cp -f "tools/PlayHuman.java"      "$SRC/PlayHuman.java"
cp -f "tools/LevelSelector.java"  "$SRC/LevelSelector.java"
cp -f "tools/ExperimentRunner.java" "$SRC/ExperimentRunner.java"
mkdir -p "$SRC/engine/audio"
cp -f "tools/engine/audio/ProceduralMusic.java" "$SRC/engine/audio/ProceduralMusic.java"
cp -f "tools/engine/audio/ProceduralEffects.java" "$SRC/engine/audio/ProceduralEffects.java"

echo -e "\033[1;36m[ 75%]\033[0m \033[1;32m[███████████████░░░░░]\033[0m Compilando archivos Java con javac..."
mapfile -t java_files < <(find "$SRC" -name "*.java")

if [ "${#java_files[@]}" -eq 0 ]; then
    echo "ERROR: No Java source files found in $SRC" >&2
    exit 1
fi

javac --release 25 -d "$BIN" -encoding UTF-8 "${java_files[@]}"
echo -e "\033[1;36m[100%]\033[0m \033[1;32m[████████████████████]\033[0m ¡Compilación completada exitosamente!"
