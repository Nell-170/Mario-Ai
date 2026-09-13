#!/usr/bin/env sh
set -eu

if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk maven
elif command -v brew >/dev/null 2>&1; then
    brew install openjdk@17 maven
else
    echo "No se encontro apt-get ni Homebrew. Instala JDK 17 y Maven 3.9+ manualmente." >&2
    exit 1
fi
