#!/usr/bin/env bash
# ==============================================================
# Script para iniciar el túnel SSH hacia el servidor MarioGPT
# en el nodo GPU del clúster HPC (cngpu001:8888)
# ==============================================================

set -e

MARIO_PORT="${MARIO_PORT:-8888}"
HPC_NODE="${HPC_NODE:-cngpu001}"
HPC_HOST="172.16.24.2"

echo "=========================================================="
echo " Conexión túnel MarioGPT (HPC Cluster GPU: ${HPC_NODE})"
echo "=========================================================="

if [ -z "$HPC_USER" ]; then
    if [ -n "$1" ]; then
        HPC_USER="$1"
    else
        read -rp "Ingresa tu usuario del clúster (ej. nombre.apellido): " INPUT_USER
        HPC_USER="$INPUT_USER"
    fi
fi

if [[ "$HPC_USER" != *"@"* ]]; then
    FULL_SSH_TARGET="${HPC_USER}@${HPC_HOST}"
else
    FULL_SSH_TARGET="${HPC_USER}"
fi

echo ""
echo "[*] Abriendo túnel hacia: ${FULL_SSH_TARGET}..."
echo "[*] Redirección: localhost:${MARIO_PORT} -> ${HPC_NODE}:${MARIO_PORT}"
echo "[*] Mantén este script corriendo en esta terminal mientras juegues."
echo "[*] Para cerrar el túnel, presiona Ctrl + C."
echo ""

ssh -N -L "${MARIO_PORT}:${HPC_NODE}:${MARIO_PORT}" "${FULL_SSH_TARGET}"
