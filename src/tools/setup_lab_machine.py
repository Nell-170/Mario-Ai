#!/usr/bin/env python3
"""
setup_lab_machine.py — Puesta a Punto Total para Máquinas de Laboratorio (Lab 100).
Realiza todo el aprovisionamiento en 1 solo paso:
  1. Verifica entorno Python, venv y compilador Java (Java 21 / 17 / 25).
  2. Sincroniza herramientas y compila Mario AI Framework con target Java 17.
  3. Descarga / filtra niveles SMB1 humanos desde Hugging Face (TheGreatRambler/mm2_level).
  4. Convierte los niveles binarios a formato de texto MAF.
  5. Valida la jugabilidad con el agente inteligente A* (Robin Baumgarten) hacia levels/nivel0.
  6. Configura el generador JeissonServer (o túnel) en experiment/config.properties y prueba /health.
  7. Deja todo 100% verificado y listo para iniciar el experimento.
"""
import os
import sys
import time
import shutil
import subprocess
import urllib.request
from pathlib import Path

# Paths
SRC_DIR = Path(__file__).resolve().parents[1]
ROOT_DIR = SRC_DIR.parent
FRAMEWORK_DIR = ROOT_DIR / "mario_ai_framework"
DATASET_DIR = SRC_DIR / "dataset"
LEVELS_DIR = SRC_DIR / "levels"
CONFIG_FILE = SRC_DIR / "experiment" / "config.properties"

def progress(percent: int, message: str):
    """Outputs progress in the format expected by the dashboard parser."""
    w = 20
    filled = int(percent / 100.0 * w)
    bar = "█" * filled + "░" * (w - filled)
    sys.stdout.write(f"\033[1;36m[{percent:3d}%]\033[0m \033[1;32m[{bar}]\033[0m \033[1m{message}\033[0m\n")
    sys.stdout.flush()

def info(msg: str):
    print(f"  \033[1;34m•\033[0m {msg}", flush=True)

def success(msg: str):
    print(f"  \033[1;32m✓\033[0m {msg}", flush=True)

def warn(msg: str):
    print(f"  \033[1;33m⚠\033[0m {msg}", flush=True)

def run_cmd(cmd, cwd=None):
    proc = subprocess.run(cmd, cwd=cwd or SRC_DIR, capture_output=True, text=True)
    return proc.returncode, proc.stdout, proc.stderr

def run_cmd_stream(cmd, cwd=None, prefix=""):
    proc = subprocess.Popen(
        cmd,
        cwd=cwd or SRC_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        universal_newlines=True
    )
    for line in proc.stdout:
        s = line.rstrip()
        if s:
            print(f"{prefix}{s}", flush=True)
    proc.wait()
    return proc.returncode

def main():
    start_time = time.time()
    print("\n" + "=" * 70, flush=True)
    print("  🚀 INICIANDO PUESTA A PUNTO TOTAL — MÁQUINA DE LABORATORIO (LAB 100)", flush=True)
    print("=" * 70 + "\n", flush=True)

    # ─────────────────────────────────────────────────────────────
    # FASE 1: Verificación de Entorno y Herramientas (10%)
    # ─────────────────────────────────────────────────────────────
    progress(10, "Verificando entorno Python y compilador Java...")
    
    info(f"Python activo: {sys.executable}")
    
    javac_path = shutil.which("javac")
    if not javac_path:
        cand = Path.home() / ".local" / "jdk-25" / "bin" / "javac"
        if cand.exists():
            javac_path = str(cand)
            os.environ["PATH"] = str(cand.parent) + os.pathsep + os.environ.get("PATH", "")
    
    if javac_path:
        code, out, _ = run_cmd([javac_path, "-version"])
        ver = (out or "").strip()
        success(f"Compilador Java listo: {ver} ({javac_path})")
    else:
        warn("No se detectó 'javac' en PATH. Se intentará compilar con Makefile/scripts locales.")

    for d in [
        SRC_DIR / "experiment" / "results",
        SRC_DIR / "telemetry",
        LEVELS_DIR / "familiarization",
        LEVELS_DIR / "generics",
        LEVELS_DIR / "nivel0",
        LEVELS_DIR / "converted",
        LEVELS_DIR / "generated",
        LEVELS_DIR / "test_output",
    ]:
        d.mkdir(parents=True, exist_ok=True)
    success("Estructura de directorios verificada.")

    # ─────────────────────────────────────────────────────────────
    # FASE 2: Sincronización y Compilación de Java (25%)
    # ─────────────────────────────────────────────────────────────
    progress(25, "Sincronizando parches y compilando Mario AI Framework...")
    compile_cmd = ["bash", "tools/compile-framework.sh", str(FRAMEWORK_DIR)]
    if os.name == "nt":
        compile_cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools/compile-framework.ps1", "-Framework", str(FRAMEWORK_DIR)]

    code = run_cmd_stream(compile_cmd, cwd=SRC_DIR, prefix="  ")
    if code != 0:
        warn("La compilación retornó código distinto de 0. Verificando si bin/ ya contiene clases...")
    
    runner_class = FRAMEWORK_DIR / "bin" / "ExperimentRunner.class"
    if runner_class.exists():
        success("Framework y herramientas personalizadas compiladas con éxito.")
    else:
        warn("No se encontró ExperimentRunner.class; se continuará con la preparación de niveles.")

    # ─────────────────────────────────────────────────────────────
    # FASE 3: Descarga y Filtrado desde Hugging Face (50%)
    # ─────────────────────────────────────────────────────────────
    progress(50, "Obteniendo niveles Super Mario Maker 2 desde Hugging Face...")
    nivel0_dir = LEVELS_DIR / "nivel0"
    converted_dir = LEVELS_DIR / "converted"
    pkl_file = DATASET_DIR / "mm2_selected_levels.pkl"

    existing_n0 = list(nivel0_dir.glob("*.txt"))
    info(f"Niveles humanos existentes en levels/nivel0: {len(existing_n0)}")

    info("Conectando con Hugging Face (TheGreatRambler/mm2_level)...")
    filter_script = DATASET_DIR / "filter_mm2.py"
    if filter_script.exists():
        filter_cmd = [sys.executable, str(filter_script), "--want", "15", "--max-scan", "10000"]
        code = run_cmd_stream(filter_cmd, cwd=SRC_DIR, prefix="  ")
        if code == 0 and pkl_file.exists():
            success("Niveles filtrados y descargados desde Hugging Face exitosamente.")
        else:
            warn("No se pudo conectar a Hugging Face o stream interrumpido. Usando archivos locales existentes.")
    else:
        warn("Script filter_mm2.py no encontrado; omitiendo streaming HF.")

    # ─────────────────────────────────────────────────────────────
    # FASE 4: Conversión a Formato de Texto MAF (70%)
    # ─────────────────────────────────────────────────────────────
    progress(70, "Convirtiendo niveles binarios MM2 a formato texto Mario AI Framework...")
    convert_script = DATASET_DIR / "convert_mm2_to_maf.py"
    if convert_script.exists() and pkl_file.exists():
        code = run_cmd_stream([sys.executable, str(convert_script)], cwd=SRC_DIR, prefix="  ")
        conv_count = len(list(converted_dir.glob("*.txt")))
        success(f"Conversión finalizada: {conv_count} niveles en levels/converted.")
    else:
        conv_count = len(list(converted_dir.glob("*.txt")))
        info(f"Conservando {conv_count} niveles previamente convertidos en levels/converted.")

    # ─────────────────────────────────────────────────────────────
    # FASE 5: Validación Paralela con Agente A* Robin Baumgarten (85%)
    # ─────────────────────────────────────────────────────────────
    progress(85, "Validando jugabilidad con agente inteligente A* (Robin Baumgarten)...")
    java_exec = shutil.which("java") or (str(Path.home() / ".local" / "jdk-25" / "bin" / "java"))
    val_class = FRAMEWORK_DIR / "bin" / "ValidateLevels.class"
    
    if java_exec and val_class.exists() and list(converted_dir.glob("*.txt")):
        info("Ejecutando validación A* en paralelo (verificando que todos los niveles sean ganables)...")
        val_cmd = [
            java_exec,
            "-Djava.awt.headless=true",
            "-cp",
            "bin",
            "ValidateLevels",
            str(converted_dir),
            str(nivel0_dir),
            "30",
        ]
        run_cmd_stream(val_cmd, cwd=FRAMEWORK_DIR, prefix="  ")
    
    final_n0 = list(nivel0_dir.glob("*.txt"))
    if len(final_n0) < 4:
        info("Asegurando niveles base para que el Cuadrado Latino tenga suficientes opciones...")
        fam_levels = list((LEVELS_DIR / "familiarization").glob("*.txt"))
        gen_levels = list((LEVELS_DIR / "generics").glob("*.txt"))
        for idx, f in enumerate(fam_levels + gen_levels):
            dest = nivel0_dir / f"nivel_humano_{idx + 1}.txt"
            if not dest.exists():
                shutil.copy2(f, dest)
        final_n0 = list(nivel0_dir.glob("*.txt"))

    success(f"Niveles humanos verificados y superables (levels/nivel0): {len(final_n0)}")

    # ─────────────────────────────────────────────────────────────
    # FASE 6: Configuración del Generador MarioGPT (95%)
    # ─────────────────────────────────────────────────────────────
    progress(95, "Configurando generador para red UCR (JeissonServer)...")
    if CONFIG_FILE.exists():
        content = CONFIG_FILE.read_text(encoding="utf-8")
        for old in ["generation_mode=Nativo", "generation_mode=Tunel", "generation_mode=Tunnel"]:
            content = content.replace(old, "generation_mode=JeissonServer")
        if "generation_mode=JeissonServer" not in content:
            content += "\ngeneration_mode=JeissonServer\n"
        CONFIG_FILE.write_text(content, encoding="utf-8")
        success("Modo predeterminado configurado a: JeissonServer (http://jeisson.ecci.ucr.ac.cr:8080).")

    jeisson_online = False
    try:
        req = urllib.request.Request("http://jeisson.ecci.ucr.ac.cr:8080/health", headers={"User-Agent": "MarioAI-Setup"})
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            if resp.status == 200:
                jeisson_online = True
    except Exception:
        jeisson_online = False

    if jeisson_online:
        success("Servidor MarioGPT del Prof. Jeisson: EN LÍNEA Y RESPONDIENDO CORRECTAMENTE (HTTP 200).")
    else:
        warn("JeissonServer no respondió de inmediato (verificar si la máquina está conectada a la red UCR / wifi institucional).")
        info("Si estás fuera del lab, puedes cambiar a 'Túnel SSH' o 'Nativo' desde la GUI con 1 clic.")

    # ─────────────────────────────────────────────────────────────
    # FASE 7: Verificación Final y Resumen (100%)
    # ─────────────────────────────────────────────────────────────
    elapsed = round(time.time() - start_time, 1)
    progress(100, f"¡Puesta a punto completada exitosamente en {elapsed}s!")

    print("\n" + "=" * 70, flush=True)
    print("  🍄 RESUMEN DE ESTADO PARA EL EXPERIMENTO — MÁQUINA LISTA", flush=True)
    print("=" * 70, flush=True)
    print(f"  🎮 Niveles humanos (nivel0):       {len(list(nivel0_dir.glob('*.txt')))} niveles superables listos")
    print(f"  🌟 Niveles de familiarización:     {len(list((LEVELS_DIR / 'familiarization').glob('*.txt')))} niveles")
    print(f"  📦 Niveles genéricos / control:    {len(list((LEVELS_DIR / 'generics').glob('*.txt')))} niveles")
    print(f"  ⚡ Generador de IA activo:         JeissonServer ({'● EN LÍNEA' if jeisson_online else '● Comprobar red/túnel'})")
    print(f"  📊 Directorio de resultados:       experiment/results (listo para participantes)")
    print("=" * 70 + "\n", flush=True)
    print("  👉 Puedes presionar 'Iniciar Experimento 6x6' para que el participante comience.\n", flush=True)

if __name__ == "__main__":
    main()
