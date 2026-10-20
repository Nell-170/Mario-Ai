#!/usr/bin/env bash
# apply-framework-patch.sh — applies tools/framework.patch to the cloned framework.
# Usage: bash tools/apply-framework-patch.sh <framework-dir>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Si se pasa un argumento, resolver su ruta absoluta o relativa respecto al CWD
if [ -n "${1:-}" ]; then
    FRAMEWORK="$(cd "$1" 2>/dev/null && pwd || echo "$1")"
else
    FRAMEWORK="$ROOT_DIR/mario_ai_framework"
fi

PATCH="$SCRIPT_DIR/framework.patch"
AUDIO_PATCH="$SCRIPT_DIR/framework-audio.patch"
SRC="$FRAMEWORK/src"

if [ ! -d "$FRAMEWORK" ]; then
    echo "ERROR: Directorio del framework no existe: $FRAMEWORK" >&2
    exit 1
fi

echo "[*] Aplicando parches git al motor Mario AI en: $FRAMEWORK"
cd "$FRAMEWORK"
if grep -q "ProceduralMusic" src/engine/core/MarioGame.java 2>/dev/null; then
    echo "  ✓ Parches del framework ya estaban aplicados."
else
    if git apply --unidiff-zero --ignore-whitespace "$PATCH"; then
        echo "  ✓ Parche del framework aplicado exitosamente."
    else
        echo "ERROR: No se pudo aplicar tools/framework.patch a $FRAMEWORK" >&2
        exit 1
    fi
fi

echo "[*] Sincronizando herramientas Java desde $SCRIPT_DIR hacia $SRC..."
mkdir -p "$SRC/engine/audio"
cp -f "$SCRIPT_DIR/ValidateLevels.java" "$SRC/ValidateLevels.java"
cp -f "$SCRIPT_DIR/PlayHuman.java"      "$SRC/PlayHuman.java"
cp -f "$SCRIPT_DIR/LevelSelector.java"  "$SRC/LevelSelector.java"
cp -f "$SCRIPT_DIR/ExperimentRunner.java" "$SRC/ExperimentRunner.java"
cp -f "$SCRIPT_DIR/engine/audio/ProceduralMusic.java" "$SRC/engine/audio/ProceduralMusic.java"
cp -f "$SCRIPT_DIR/engine/audio/ProceduralEffects.java" "$SRC/engine/audio/ProceduralEffects.java"

echo "  ✓ Herramientas Java sincronizadas con éxito."
