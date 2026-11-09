#!/usr/bin/env bash
# compile-framework.sh — cross-platform alternative to compile-framework.ps1
# Usage: bash tools/compile-framework.sh <framework-dir>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -n "${1:-}" ]; then
    FRAMEWORK="$(cd "$1" 2>/dev/null && pwd || echo "$1")"
else
    FRAMEWORK="$ROOT_DIR/mario_ai_framework"
fi
# Ensure javac is in PATH
if ! command -v javac &>/dev/null; then
    for cand in "/home/marioc/.local/jdk-25/bin" "/home/marioc/.local/bin" "$HOME/.local/jdk-25/bin" "$HOME/.local/bin"; do
        if [ -x "$cand/javac" ]; then
            export PATH="$cand:$PATH"
            break
        fi
    done
fi

BIN="$FRAMEWORK/bin"
SRC="$FRAMEWORK/src"

echo -e "\033[1;36m[ 25%]\033[0m \033[1;32m[█████░░░░░░░░░░░░░░░]\033[0m Aplicando parches y sincronizando herramientas..."
bash "$SCRIPT_DIR/apply-framework-patch.sh" "$FRAMEWORK"
mkdir -p "$BIN"

echo -e "\033[1;36m[ 75%]\033[0m \033[1;32m[███████████████░░░░░]\033[0m Compilando archivos Java con javac..."
mapfile -t java_files < <(find "$SRC" -name "*.java")

if [ "${#java_files[@]}" -eq 0 ]; then
    echo "ERROR: No Java source files found in $SRC" >&2
    exit 1
fi

# Determine release target: Java 17 is the LTS baseline supported by JDK 17, 21, and 25
# (required for Java records and switch expressions used in custom tools)
RELEASE_FLAG="--release 17"
if ! javac $RELEASE_FLAG -version &>/dev/null; then
    RELEASE_FLAG=""
fi

javac $RELEASE_FLAG -d "$BIN" -encoding UTF-8 "${java_files[@]}"

# Driver JDBC de PostgreSQL (base de datos del experimento): se extrae dentro de bin/
# para que todos los "java -cp bin ..." lo encuentren sin cambiar el classpath.
for lib_jar in "$SCRIPT_DIR"/lib/*.jar; do
    [ -f "$lib_jar" ] || continue
    if command -v jar &>/dev/null; then
        (cd "$BIN" && jar xf "$lib_jar")
    elif command -v unzip &>/dev/null; then
        unzip -oq "$lib_jar" -d "$BIN"
    else
        python3 -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$lib_jar" "$BIN"
    fi
done
echo -e "\033[1;36m[100%]\033[0m \033[1;32m[████████████████████]\033[0m ¡Compilación completada exitosamente!"
