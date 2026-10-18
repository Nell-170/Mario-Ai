#!/usr/bin/env python3
"""Safely remove local experiment participant results and response files."""
import shutil
from pathlib import Path


RESULTS_DIR = Path(__file__).resolve().parents[1] / "experiment" / "results"


def main() -> int:
    if not RESULTS_DIR.exists():
        print(f"No existe el directorio de resultados: {RESULTS_DIR}")
        return 0

    entries = list(RESULTS_DIR.iterdir())
    if not entries:
        print("El directorio de resultados ya está vacío.")
        return 0

    print(f"Se eliminará todo el contenido de:\n  {RESULTS_DIR}")
    print("Esto incluye las sesiones de participantes y los archivos TSV de respuestas.")
    if input("Para confirmar, escribe BORRAR: ").strip() != "BORRAR":
        print("Limpieza cancelada; no se eliminó ningún archivo.")
        return 0

    shutil.rmtree(RESULTS_DIR)
    RESULTS_DIR.mkdir(parents=True)
    print("Resultados del experimento eliminados.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
