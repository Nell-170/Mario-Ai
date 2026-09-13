#!/usr/bin/env sh
set -eu

PORT="${1:-8080}"
PROOF_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$PROOF_ROOT/../.." && pwd)
TARGET_ROOT="$PROOF_ROOT/target"
WEB_ROOT="$TARGET_ROOT/web"
LEVEL_DIRECTORY="$REPOSITORY_ROOT/src/levels/nivel0"

if command -v mvn >/dev/null 2>&1; then
    MAVEN=mvn
elif [ -x "$PROOF_ROOT/.tools/apache-maven-3.9.11/bin/mvn" ]; then
    MAVEN="$PROOF_ROOT/.tools/apache-maven-3.9.11/bin/mvn"
else
    echo "Maven no esta instalado. Ejecuta make install-web-tools." >&2
    exit 1
fi

cd "$PROOF_ROOT"
"$MAVEN" package
mkdir -p "$WEB_ROOT"
LEVEL_FILE=$(find "$LEVEL_DIRECTORY" -maxdepth 1 -type f -name '*.txt' | sort | head -n 1)
if [ -z "$LEVEL_FILE" ]; then
    echo "No hay niveles .txt en $LEVEL_DIRECTORY. Ejecuta primero el pipeline de niveles." >&2
    exit 1
fi
cp "$TARGET_ROOT/classes/index.html" "$WEB_ROOT/index.html"
cp "$LEVEL_FILE" "$WEB_ROOT/level.txt"
cp "$TARGET_ROOT/classes/wasm-gc-module-runtime.js" "$WEB_ROOT/wasm-gc-module-runtime.js"
echo "Abre http://localhost:$PORT/"
python3 -m http.server "$PORT" --directory "$WEB_ROOT"
