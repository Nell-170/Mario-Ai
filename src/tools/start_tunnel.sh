#!/usr/bin/env bash
# ==============================================================
# Script para iniciar el túnel SSH hacia el servidor MarioGPT
# en el nodo GPU del clúster HPC con auto-detección Slurm
# ==============================================================

set -e

MARIO_PORT="${MARIO_PORT:-8888}"
HPC_HOST="172.16.24.2"

echo "=========================================================="
echo " Conexión túnel MarioGPT (HPC Cluster GPU: ${HPC_HOST})"
echo "=========================================================="

if [ -z "$HPC_USER" ]; then
    if [ -n "$1" ]; then
        HPC_USER="$1"
    else
        read -rp "Ingresa tu usuario del clúster (ej. mariogabriel.cordero): " INPUT_USER
        HPC_USER="$INPUT_USER"
    fi
fi

if [[ "$HPC_USER" != *"@"* ]]; then
    FULL_SSH_TARGET="${HPC_USER}@${HPC_HOST}"
else
    FULL_SSH_TARGET="${HPC_USER}"
fi

# Auto-detección del nodo esclavo asignado por Slurm
if [ -z "$HPC_NODE" ] || [ "$HPC_NODE" = "auto" ]; then
    echo "[*] Detectando nodo activo en Slurm (squeue -n mariogpt-srv)..."
    DETECTED_NODE=$(ssh -o BatchMode=yes "${FULL_SSH_TARGET}" "squeue -n mariogpt-srv -ho '%N'" 2>/dev/null | grep -E 'cngpu[0-9]+' | head -n 1 | tr -d '\r\n ' || true)
    if [ -n "$DETECTED_NODE" ]; then
        HPC_NODE="$DETECTED_NODE"
        echo "[+] Nodo GPU activo detectado: ${HPC_NODE}"
    else
        HPC_NODE="cngpu002"
        echo "[!] No se pudo auto-detectar nodo vía Slurm. Usando fallback: ${HPC_NODE}"
    fi
fi

echo ""
echo "[*] Abriendo túnel hacia: ${FULL_SSH_TARGET}..."
echo "[*] Redirección: localhost:${MARIO_PORT} -> ${HPC_NODE}:${MARIO_PORT}"
echo "[*] Mantén este script corriendo en esta terminal mientras juegues."
echo "[*] Para cerrar el túnel, presiona Ctrl + C."
echo ""

ssh -N -L "${MARIO_PORT}:${HPC_NODE}:${MARIO_PORT}" "${FULL_SSH_TARGET}"
