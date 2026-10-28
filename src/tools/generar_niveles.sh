#!/usr/bin/env bash
# Genera niveles de MarioGPT y los guarda en una carpeta.
#
# Uso:
#   ./generar_niveles.sh [-n cantidad] [-o carpeta] [-p "prompt"]
#
# Ejemplos:
#   ./generar_niveles.sh
#   ./generar_niveles.sh -n 5 -o mis_niveles
#   ./generar_niveles.sh -n 3 -p "few pipes, no enemies, low elevation"

# Eliminamos -e temporalmente para manejar los errores nosotros mismos sin cierres mudos
set -uo pipefail

SERVIDOR="http://jeisson.ecci.ucr.ac.cr:8080"
CANTIDAD=2
# Definimos la ruta por defecto directamente a tu carpeta de destino en Git Bash
CARPETA="/c/Users/emago/OneDrive/Documentos/GitHub/Mario-Ai/src/levels/generics"
PROMPT=""

while getopts "n:o:p:h" opt; do
  case "$opt" in
    n) CANTIDAD="$OPTARG" ;;
    o) CARPETA="$OPTARG" ;;
    p) PROMPT="$OPTARG" ;;
    h) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Opción inválida. Use -h para ayuda." >&2; exit 1 ;;
  esac
done

# 1. Verificar que el servidor esté disponible
echo "Verificando servidor en $SERVIDOR/health ..."

# Ejecutamos curl capturando explícitamente su estado
respuesta=$(curl -sf --max-time 10 "$SERVIDOR/health" 2>&1)
estado_curl=$?

if [ $estado_curl -ne 0 ]; then
  echo "Error: no se pudo conectar con el servidor (Código de salida curl: $estado_curl)." >&2
  echo "Detalle: $respuesta" >&2
  exit 1
fi
echo "Servidor OK: $respuesta"

# 2. Crear la carpeta de salida
mkdir -p "$CARPETA"

# 3. Generar los niveles
echo "Generando $CANTIDAD nivel(es) con el prompt: \"$PROMPT\""
for ((i = 1; i <= CANTIDAD; i++)); do
  archivo="$CARPETA/nivel_$(date +%Y%m%d_%H%M%S)_$i.txt"
  echo "[$i/$CANTIDAD] Generando -> $archivo"

  # Nota en Windows: date +%s.%N puede no dar nanosegundos completos, usamos una alternativa segura
  inicio=$(date +%s)
  
  if curl -sfG "$SERVIDOR/level" \
       --data-urlencode "prompt=$PROMPT" \
       -o "$archivo"; then
    fin=$(date +%s)
    duracion=$((fin - inicio))
    printf "    Listo en %s s\n" "$duracion"
  else
    echo "    Error al generar el nivel $i" >&2
    rm -f "$archivo"
  fi
done

echo "Niveles guardados en: $(realpath "$CARPETA")"
