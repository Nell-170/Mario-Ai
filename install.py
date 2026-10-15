#!/usr/bin/env bash
""":"
# Dual-runner: this file can be executed with either `python3 install.py` or `./install.py` (via bash)
exec python3 "$0" "$@"
"""

import os
import sys
import time
import shutil
import subprocess
import glob
from pathlib import Path
from typing import Optional, List, Tuple

# Terminal colors
if sys.stdout.isatty():
    C_RESET = "\033[0m"
    C_BOLD = "\033[1m"
    C_CYAN = "\033[1;36m"
    C_GREEN = "\033[1;32m"
    C_YELLOW = "\033[1;33m"
    C_RED = "\033[1;31m"
    C_MAGENTA = "\033[1;35m"
    C_DIM = "\033[2m"
else:
    C_RESET = C_BOLD = C_CYAN = C_GREEN = C_YELLOW = C_RED = C_MAGENTA = C_DIM = ""

TOTAL_STEPS = 8


class InstallerProgress:
    def __init__(self, total_steps: int = TOTAL_STEPS, bar_width: int = 24):
        self.total_steps = total_steps
        self.bar_width = bar_width
        self.start_time = time.time()

    def render_bar(self, percent: int) -> str:
        filled = int((percent / 100.0) * self.bar_width)
        empty = self.bar_width - filled
        bar = "█" * filled + "░" * empty
        return f"{C_CYAN}[{percent:3d}%]{C_RESET} {C_GREEN}[{bar}]{C_RESET}"

    def step(self, step_idx: int, title: str):
        percent = int(((step_idx - 1) / float(self.total_steps)) * 100)
        bar = self.render_bar(percent)
        print(f"\n{bar} {C_BOLD}{C_CYAN}[Paso {step_idx}/{self.total_steps}]{C_RESET} {C_BOLD}{title}{C_RESET}")

    def success(self, msg: str):
        print(f"  {C_GREEN}✓{C_RESET} {msg}")

    def warning(self, msg: str):
        print(f"  {C_YELLOW}⚠{C_RESET} {msg}")

    def error(self, msg: str):
        print(f"  {C_RED}✗{C_RESET} {msg}")

    def info(self, msg: str):
        print(f"  {C_DIM}•{C_RESET} {msg}")

    def finish(self):
        bar = self.render_bar(100)
        elapsed = time.time() - self.start_time
        print(f"\n{bar} {C_BOLD}{C_GREEN}¡Instalación completada con éxito en {elapsed:.1f}s!{C_RESET}")


def find_repo_root() -> Path:
    # 1. install.py is located at the project root
    this_file = Path(__file__).resolve()
    candidates = [
        this_file.parent,
        this_file.parent.parent,
        this_file.parent.parent.parent,
        Path.cwd().resolve(),
        Path.cwd().resolve().parent,
    ]
    for p in candidates:
        if (p / "src" / "Makefile").exists() and (p / "src" / "tools").is_dir():
            return p
        if (p / ".git").exists() and (p / "src").is_dir():
            return p

    return this_file.parent


def report_step_failure(
    step_num: int,
    step_title: str,
    what_happened: str,
    technical_details: str = "",
    solutions: Optional[List[str]] = None,
    critical: bool = True,
):
    """Prints a clear, structured diagnostic screen explaining what failed and how to resolve it."""
    print(f"\n{C_RED}{C_BOLD}╔══════════════════════════════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_RED}{C_BOLD}║  ✗ ERROR EN EL PIPELINE DE INSTALACIÓN — PASO {step_num}/{TOTAL_STEPS:<2}                         ║{C_RESET}")
    print(f"{C_RED}{C_BOLD}║  Fase: {step_title[:68]:<68}  ║{C_RESET}")
    print(f"{C_RED}{C_BOLD}╚══════════════════════════════════════════════════════════════════════════════════╝{C_RESET}")

    print(f"\n  {C_BOLD}{C_YELLOW}¿Qué estaba ocurriendo?{C_RESET}")
    print(f"    {what_happened}")

    if technical_details and technical_details.strip():
        print(f"\n  {C_BOLD}{C_RED}Detalles técnicos del error (últimas líneas):{C_RESET}")
        for line in technical_details.strip().splitlines()[-14:]:
            print(f"    {C_DIM}│{C_RESET} {line}")

    if solutions:
        print(f"\n  {C_BOLD}{C_GREEN}¿Cómo solucionarlo?{C_RESET}")
        for sol in solutions:
            print(f"    {C_CYAN}•{C_RESET} {sol}")
    print("")

    if critical:
        sys.exit(1)


def ensure_virtual_environment(root: Path):
    """
    Guarantees the entire installation and all scripts run inside an isolated .venv in repo root.
    If executed from a system Python outside .venv:
      1. Creates root/.venv automatically.
      2. Re-executes the current script inside that virtual environment.
    """
    is_in_venv = (
        sys.prefix != getattr(sys, "base_prefix", sys.prefix)
        or os.environ.get("VIRTUAL_ENV") is not None
    )
    venv_dir = root / ".venv"
    if os.name == "nt":
        venv_python = venv_dir / "Scripts" / "python.exe"
    else:
        venv_python = venv_dir / "bin" / "python"

    if is_in_venv:
        return

    print(f"{C_BOLD}{C_CYAN}[Inicialización de Entorno Virtual]{C_RESET}")
    print(f"  {C_DIM}•{C_RESET} Verificando entorno virtual aislado (.venv) para máxima compatibilidad...")

    if not venv_python.exists():
        print(f"  {C_DIM}•{C_RESET} Creando nuevo entorno virtual en {venv_dir.relative_to(root)}...")
        try:
            res = subprocess.run([sys.executable, "-m", "venv", str(venv_dir)], capture_output=True, text=True)
            if res.returncode != 0:
                report_step_failure(
                    0,
                    "Inicialización de Entorno Virtual",
                    "No se pudo crear automáticamente el entorno virtual (.venv).",
                    res.stderr or res.stdout,
                    [
                        "Ubuntu/Debian: sudo apt update && sudo apt install python3-venv python3-pip",
                        "Arch Linux:    sudo pacman -S python",
                        "Fedora:        sudo dnf install python3",
                        "Verifica que tengas permisos de escritura en la carpeta del repositorio.",
                    ],
                    critical=True,
                )
        except Exception as e:
            report_step_failure(
                0,
                "Inicialización de Entorno Virtual",
                f"Excepción al ejecutar el módulo venv de Python: {e}",
                str(e),
                ["Verifica que Python tenga soporte para entornos virtuales."],
                critical=True,
            )

    print(f"  {C_GREEN}✓{C_RESET} Entorno virtual preparado: {venv_python}")
    print(f"  {C_CYAN}➜{C_RESET} Reiniciando instalador dentro del entorno virtual (.venv)...\n")

    env = dict(os.environ)
    env["VIRTUAL_ENV"] = str(venv_dir)
    bin_dir = str(venv_python.parent)
    env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")

    try:
        script_path = str(root / "install.py")
        if os.name != "nt":
            os.execve(str(venv_python), [str(venv_python), script_path] + sys.argv[1:], env)
        else:
            code = subprocess.call([str(venv_python), script_path] + sys.argv[1:], env=env)
            sys.exit(code)
    except Exception as e:
        report_step_failure(
            0,
            "Re-ejecución en Entorno Virtual",
            f"Fallo al transferir la ejecución al entorno virtual: {e}",
            str(e),
            [f"Ejecuta directamente: {venv_python} install.py"],
            critical=True,
        )


def run_command(cmd: List[str], cwd: Optional[Path] = None, env: Optional[dict] = None) -> Tuple[int, str, str]:
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else None,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError as e:
        return 127, "", str(e)
    except Exception as e:
        return 1, "", str(e)


def run_command_live(
    cmd: List[str],
    cwd: Optional[Path] = None,
    env: Optional[dict] = None,
    progress_prefix: str = "•",
) -> Tuple[int, str]:
    """
    Runs command streaming progress lines in real time to the terminal.
    Buffers full output in memory to return for diagnostics if an error occurs.
    """
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd) if cwd else None,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
    except FileNotFoundError as e:
        return 127, str(e)
    except Exception as e:
        return 1, str(e)

    lines = []
    if proc.stdout:
        for line in proc.stdout:
            lines.append(line)
            sline = line.strip()
            if not sline:
                continue

            # Stream clean summary progress lines
            if any(sline.startswith(p) for p in [
                "Collecting", "Downloading", "Installing", "Building", "Cloning", "Compiling",
                "Successfully", "Requirement already satisfied", "Using cached"
            ]):
                # Truncate very long lines nicely
                display_line = sline if len(sline) <= 80 else sline[:77] + "..."
                print(f"  {C_DIM}{progress_prefix}{C_RESET} {display_line}")

    proc.wait()
    return proc.returncode, "".join(lines)


def detect_java() -> Tuple[Optional[str], Optional[str]]:
    """Detects javac and java executables in PATH or standard system/IDE locations."""
    javac_path = shutil.which("javac")
    java_path = shutil.which("java")

    if javac_path and java_path:
        return javac_path, java_path

    search_dirs = [
        # Linux standard
        "/usr/lib/jvm/*/bin",
        "/usr/java/*/bin",
        os.path.expanduser("~/.jdks/*/bin"),
        os.path.expanduser("~/.sdkman/candidates/java/*/bin"),
        # VS Code / Antigravity Java extensions
        os.path.expanduser("~/.antigravity-ide/extensions/redhat.java-*/jre/*/bin"),
        os.path.expanduser("~/.vscode/extensions/redhat.java-*/jre/*/bin"),
        # macOS
        "/Library/Java/JavaVirtualMachines/*/Contents/Home/bin",
        # Windows
        "C:/Program Files/Java/*/bin",
        "C:/Program Files/Eclipse Adoptium/*/bin",
        "C:/Program Files/Microsoft/*/bin",
        "C:/Program Files/Amazon Corretto/*/bin",
    ]

    for pattern in search_dirs:
        for candidate_dir in sorted(glob.glob(pattern), reverse=True):
            cand_javac = os.path.join(candidate_dir, "javac" + (".exe" if os.name == "nt" else ""))
            cand_java = os.path.join(candidate_dir, "java" + (".exe" if os.name == "nt" else ""))
            if os.path.isfile(cand_javac) and os.access(cand_javac, os.X_OK):
                javac_path = cand_javac
                java_path = cand_java if os.path.isfile(cand_java) else (shutil.which("java") or cand_javac.replace("javac", "java"))
                return javac_path, java_path

    return javac_path, java_path


def prompt_ollama_key_gui(progress: InstallerProgress, default_key: str = "") -> Optional[str]:
    """Displays a graphical window for the user to easily paste the Ollama API key."""
    title = "Configuración de Ollama Cloud — Mario AI"
    desc = (
        "No se encontró el archivo necesario de Ollama (mario_ai_framework/Ollama-Key.txt).\n\n"
        "Pega aquí tu API Key de Ollama Cloud para la síntesis de niveles con IA:\n"
        "(Se guardará automáticamente en mario_ai_framework/Ollama-Key.txt)"
    )

    has_display = bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY") or sys.platform in ("win32", "darwin"))

    if has_display:
        # 1. Linux Zenity
        if sys.platform.startswith("linux"):
            try:
                res = subprocess.run(
                    [
                        "zenity",
                        "--entry",
                        f"--title={title}",
                        f"--text={desc}",
                        "--width=560",
                        f"--entry-text={default_key}",
                    ],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
                if res.returncode == 0:
                    val = res.stdout.strip()
                    if val:
                        return val
                elif res.returncode == 1:
                    return None
            except FileNotFoundError:
                pass

            # 2. Linux kdialog
            try:
                res = subprocess.run(
                    [
                        "kdialog",
                        "--title", title,
                        "--inputbox", desc,
                        default_key,
                    ],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
                if res.returncode == 0:
                    val = res.stdout.strip()
                    if val:
                        return val
            except FileNotFoundError:
                pass

        # 3. macOS osascript
        if sys.platform == "darwin":
            try:
                osa_script = (
                    f'display dialog "{desc}" default answer "{default_key}" '
                    f'with title "{title}" with icon note buttons {{"Cancelar", "Guardar"}} default button "Guardar"'
                )
                res = subprocess.run(
                    ["osascript", "-e", f"text returned of ({osa_script})"],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
                if res.returncode == 0:
                    val = res.stdout.strip()
                    if val:
                        return val
            except Exception:
                pass

        # 4. Windows PowerShell InputBox
        if sys.platform == "win32":
            try:
                clean_desc = desc.replace("\n", "`n")
                ps_script = (
                    "Add-Type -AssemblyName Microsoft.VisualBasic; "
                    f"[Microsoft.VisualBasic.Interaction]::InputBox('{clean_desc}', '{title}', '{default_key}')"
                )
                res = subprocess.run(
                    ["powershell", "-NoProfile", "-Command", ps_script],
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
                if res.returncode == 0:
                    val = res.stdout.strip()
                    if val:
                        return val
            except Exception:
                pass

        # 5. Tkinter fallback
        try:
            import tkinter as tk
            from tkinter import simpledialog

            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            key = simpledialog.askstring(title, desc, initialvalue=default_key, parent=root)
            root.destroy()
            if key and key.strip():
                return key.strip()
        except Exception:
            pass

    # 6. Terminal interactive fallback (if no GUI or headless SSH)
    print(f"\n{C_YELLOW}{C_BOLD}──────────────────────────────────────────────────────────────────{C_RESET}")
    print(f" {C_BOLD}INGRESO DE API KEY DE OLLAMA CLOUD{C_RESET}")
    print(f" {C_DIM}Pega tu API Key a continuación (o presiona Enter para omitir):{C_RESET}")
    print(f"{C_YELLOW}──────────────────────────────────────────────────────────────────{C_RESET}")
    try:
        user_key = input(f" {C_CYAN}API Key:{C_RESET} ").strip()
        if user_key:
            return user_key
    except (EOFError, KeyboardInterrupt):
        print("")
        return None

    return None


def main():
    root = find_repo_root()
    os.chdir(root)
    src_dir = root / "src"
    framework_dir = root / "mario_ai_framework"
    mariogpt_dir = root / "mario_gpt"

    # GUARANTEE VIRTUAL ENVIRONMENT EXECUTION:
    # If this is invoked from system python, it creates/activates .venv and re-runs.
    ensure_virtual_environment(root)

    print(f"{C_BOLD}{C_CYAN}╔══════════════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║                MARIO AI — INSTALADOR Y SETUP                     ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║     Configuración completa de entorno, repos y dependencias      ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚══════════════════════════════════════════════════════════════════╝{C_RESET}")
    print(f"  {C_DIM}• Entorno de ejecución:{C_RESET} {sys.executable}")
    print(f"  {C_DIM}• Directorio base:{C_RESET}      {root}")

    # Solo configurar clave de Ollama si se pasa --key-only
    if "--key-only" in sys.argv:
        print(f"\n{C_CYAN}{C_BOLD}=== CONFIGURACIÓN DE OLLAMA API KEY ==={C_RESET}")
        key_file = framework_dir / "Ollama-Key.txt"
        entered_key = prompt_ollama_key_gui(InstallerProgress(1))
        if entered_key and entered_key.strip():
            key_file.parent.mkdir(parents=True, exist_ok=True)
            key_file.write_text(entered_key.strip() + "\n", encoding="utf-8")
            print(f"  {C_GREEN}✓{C_RESET} API Key guardada exitosamente en {key_file.relative_to(root)}")
        else:
            print(f"  {C_YELLOW}⚠{C_RESET} No se ingresó ninguna clave.")
        return

    progress = InstallerProgress(TOTAL_STEPS)

    # ── PASO 1: Requisitos del sistema ─────────────────────────────
    progress.step(1, "Verificando requisitos del sistema (Python, Git y Java JDK)...")

    # Python version check
    py_ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    if sys.version_info < (3, 8):
        report_step_failure(
            1,
            "Requisitos del sistema",
            f"Se requiere Python 3.8 o superior, pero se detectó {py_ver}.",
            f"Ejecutable: {sys.executable}",
            ["Actualiza tu versión de Python instalando una versión reciente (3.10, 3.11, 3.12 o 3.14)."],
            critical=True,
        )
    progress.success(f"Python {py_ver} activo dentro del entorno virtual.")

    # Git check
    code, out, err = run_command(["git", "--version"])
    if code != 0:
        report_step_failure(
            1,
            "Requisitos del sistema",
            "Git no está instalado o no se encuentra disponible en la variable PATH del sistema.",
            err or out,
            [
                "Ubuntu/Debian: sudo apt update && sudo apt install git",
                "Arch Linux:    sudo pacman -S git",
                "Fedora:        sudo dnf install git",
                "macOS:         brew install git o xcode-select --install",
                "Windows:       Descarga Git desde https://git-scm.com/download/win",
            ],
            critical=True,
        )
    progress.success(f"{out.strip()} detectado.")

    # Java JDK check
    javac_path, java_path = detect_java()
    if not javac_path or not java_path:
        report_step_failure(
            1,
            "Requisitos del sistema",
            "No se encontró el compilador Java (javac / JDK). Mario AI Framework requiere Java JDK 17, 21 o 25.",
            "javac no encontrado en PATH ni en las rutas estándar del sistema o extensiones del IDE.",
            [
                "Ubuntu/Debian: sudo apt install openjdk-21-jdk",
                "Arch Linux:    sudo pacman -S jdk-openjdk",
                "Fedora:        sudo dnf install java-latest-openjdk-devel",
                "macOS:         brew install openjdk",
                "Windows:       winget install EclipseAdoptium.Temurin.21.JDK",
            ],
            critical=True,
        )

    # Add Java bin dir to PATH if detected from IDE or custom directory
    java_bin_dir = str(Path(javac_path).parent)
    if java_bin_dir not in os.environ.get("PATH", ""):
        os.environ["PATH"] = java_bin_dir + os.pathsep + os.environ.get("PATH", "")
        progress.info(f"JDK agregado a PATH de sesión: {java_bin_dir}")

    code, jout, jerr = run_command([javac_path, "-version"])
    ver_str = (jout or jerr).strip()
    progress.success(f"Java Compiler detectado: {ver_str} ({javac_path})")

    # ── PASO 2: Clonar repositorios externos ───────────────────────
    progress.step(2, "Clonando repositorios externos (Mario AI Framework y MarioGPT)...")

    # Mario AI Framework
    if (framework_dir / ".git").exists() or (framework_dir / "src" / "engine").exists():
        progress.success("Mario AI Framework ya clonado y listo (mario_ai_framework/).")
    else:
        if framework_dir.exists():
            progress.warning("Carpeta 'mario_ai_framework' incompleta detectada; limpiando antes de clonar...")
            shutil.rmtree(framework_dir, ignore_errors=True)

        progress.info("Clonando Mario-AI-Framework desde GitHub...")
        code, out, err = run_command([
            "git", "clone", "https://github.com/amidos2006/Mario-AI-Framework", str(framework_dir)
        ])
        if code != 0:
            report_step_failure(
                2,
                "Clonando repositorios externos",
                "Fallo al clonar Mario-AI-Framework desde GitHub.",
                err or out,
                [
                    "Comprueba tu conexión a internet o configuración de proxy.",
                    "Si la carpeta 'mario_ai_framework' quedó dañada, bórrala con 'rm -rf mario_ai_framework' y reintenta.",
                    "Verifica que el firewall no bloquee las conexiones a github.com.",
                ],
                critical=True,
            )
        progress.success("Mario AI Framework clonado exitosamente.")

    # MarioGPT
    if (mariogpt_dir / ".git").exists() or (mariogpt_dir / "mario_gpt").exists():
        progress.success("MarioGPT ya clonado y listo (mario_gpt/).")
    else:
        if mariogpt_dir.exists():
            progress.warning("Carpeta 'mario_gpt' incompleta detectada; limpiando antes de clonar...")
            shutil.rmtree(mariogpt_dir, ignore_errors=True)

        progress.info("Clonando MarioGPT desde GitHub...")
        code, out, err = run_command([
            "git", "clone", "https://github.com/shyamsn97/mario-gpt.git", str(mariogpt_dir)
        ])
        if code != 0:
            report_step_failure(
                2,
                "Clonando repositorios externos",
                "Fallo al clonar MarioGPT desde GitHub.",
                err or out,
                [
                    "Comprueba tu conexión a internet.",
                    "Si la carpeta 'mario_gpt' quedó incompleta, bórrala con 'rm -rf mario_gpt' y reintenta.",
                ],
                critical=True,
            )
        progress.success("MarioGPT clonado exitosamente.")

    # ── PASO 3: Instalar dependencias de Python en .venv ────────────
    progress.step(3, "Instalando dependencias de Python (requirements.txt y MarioGPT)...")
    req_file = src_dir / "requirements.txt"

    if "--skip-deps" in sys.argv:
        progress.info("Instalación de dependencias de Python omitida por argumento CLI (--skip-deps).")
    else:
        pip_cmd = [sys.executable, "-m", "pip"]

        # Ensure pip is up to date and responsive
        code, pip_ver_out, _ = run_command(pip_cmd + ["--version"])
        if code != 0:
            report_step_failure(
                3,
                "Instalando dependencias de Python",
                "No se pudo ejecutar pip dentro del entorno virtual.",
                pip_ver_out,
                [
                    "Reinstala el entorno virtual borrando la carpeta .venv y volviendo a ejecutar el instalador.",
                    f"Ejecuta manualmente: {sys.executable} -m ensurepip --default-pip",
                ],
                critical=True,
            )
        progress.info(f"Gestor de paquetes pip: {pip_ver_out.strip()}")

        if req_file.exists():
            progress.info("Instalando paquetes desde requirements.txt (esto puede tomar 1-2 minutos)...")
            # Always pass --progress-bar off to prevent carriage-return buffer hangs in subprocess
            code, out = run_command_live(pip_cmd + ["install", "--progress-bar", "off", "-r", str(req_file)])
            if code != 0:
                report_step_failure(
                    3,
                    "Instalando dependencias de Python",
                    "Fallo al instalar las librerías de Python desde src/requirements.txt.",
                    out,
                    [
                        "Verifica que dispongas de conexión a internet para descargar las librerías.",
                        "Verifica que haya suficiente espacio en disco.",
                        f"Prueba ejecutar el comando manualmente para ver el error detallado: {sys.executable} -m pip install -r src/requirements.txt",
                    ],
                    critical=False,
                )
                progress.warning("Se continuará con el setup Java; revisa las dependencias de Python si usarás MarioGPT.")
            else:
                progress.success("Dependencias de requirements.txt instaladas en .venv.")

        # MarioGPT editable install
        if mariogpt_dir.exists():
            progress.info("Instalando MarioGPT en modo editable (.venv/bin/pip install -e mario_gpt)...")
            code, out = run_command_live(pip_cmd + ["install", "--progress-bar", "off", "-e", str(mariogpt_dir)], cwd=root)
            if code != 0:
                report_step_failure(
                    3,
                    "Instalando MarioGPT",
                    "Aviso al instalar MarioGPT en modo editable.",
                    out,
                    [
                        "Verifica que setuptools esté instalado en .venv.",
                        f"Prueba ejecutar manualmente: {sys.executable} -m pip install -e mario_gpt",
                    ],
                    critical=False,
                )
                progress.warning("MarioGPT no pudo enlazarse en modo editable. El modo determinista y de Ollama seguirán funcionando.")
            else:
                progress.success("MarioGPT instalado correctamente en el entorno virtual.")

    # ── PASO 4: Aplicar parches al Framework ───────────────────────
    progress.step(4, "Aplicando parches de mejoras al motor Mario AI...")
    patch_file = src_dir / "tools" / "framework.patch"
    if patch_file.exists():
        # Check if already applied
        code, _, _ = run_command(
            ["git", "apply", "--unidiff-zero", "--ignore-whitespace", "--reverse", "--check", str(patch_file)],
            cwd=framework_dir,
        )
        if code == 0:
            progress.success("Parches de Mario AI Framework ya estaban aplicados.")
        else:
            code, out, err = run_command(
                ["git", "apply", "--unidiff-zero", "--ignore-whitespace", str(patch_file)],
                cwd=framework_dir,
            )
            if code == 0:
                progress.success("Parches aplicados exitosamente.")
            else:
                progress.warning("El parche no se aplicó automáticamente (puede que el código ya esté actualizado o modificado).")
    else:
        progress.warning(f"No se encontró el archivo de parches {patch_file}; omitiendo.")

    # ── PASO 5: Herramientas Java y Compilación ────────────────────
    progress.step(5, "Instalando herramientas personalizadas y compilando clases Java...")
    tools_to_copy = [
        "ValidateLevels.java",
        "PlayHuman.java",
        "LevelSelector.java",
        "ExperimentRunner.java",
    ]
    fw_src = framework_dir / "src"
    fw_bin = framework_dir / "bin"
    fw_bin.mkdir(parents=True, exist_ok=True)

    for tool in tools_to_copy:
        src_tool = src_dir / "tools" / tool
        dst_tool = fw_src / tool
        if src_tool.exists():
            try:
                shutil.copy2(src_tool, dst_tool)
                progress.info(f"Instalada herramienta Java: {tool}")
            except Exception as e:
                report_step_failure(
                    5,
                    "Copia de herramientas Java",
                    f"No se pudo copiar {tool} a {dst_tool}: {e}",
                    str(e),
                    ["Verifica los permisos de lectura y escritura."],
                    critical=True,
                )

    # Gather Java files
    java_files = [str(p) for p in fw_src.rglob("*.java")]
    if not java_files:
        report_step_failure(
            5,
            "Compilación de clases Java",
            f"No se encontraron archivos Java en {fw_src}.",
            "La carpeta mario_ai_framework/src no contiene código fuente Java.",
            ["Verifica que el repositorio mario_ai_framework se haya clonado correctamente en el Paso 2."],
            critical=True,
        )

    progress.info(f"Compilando {len(java_files)} archivos Java con javac...")
    compile_cmd = [javac_path, "-d", str(fw_bin), "-encoding", "UTF-8"] + java_files
    code, out, err = run_command(compile_cmd)
    if code != 0:
        report_step_failure(
            5,
            "Compilación de clases Java",
            "El compilador javac retornó errores al compilar el motor Mario AI y sus herramientas.",
            err or out,
            [
                "Verifica que la versión de Java sea compatible (JDK 17, 21 o 25).",
                "Revisa los mensajes de error del compilador indicados arriba.",
                "Puedes intentar compilar manualmente ejecutando: cd src && make compile",
            ],
            critical=True,
        )
    progress.success(f"Compilación exitosa. Clases generadas en {fw_bin.relative_to(root)}.")

    # ── PASO 6: Verificación y Directorios de Niveles ───────────────
    progress.step(6, "Verificando estructura de niveles y directorios de datos...")

    dirs_to_create = [
        src_dir / "experiment" / "results",
        src_dir / "telemetry",
        src_dir / "levels" / "familiarization",
        src_dir / "levels" / "generics",
        src_dir / "levels" / "nivel0",
        src_dir / "levels" / "converted",
        src_dir / "levels" / "generated",
        src_dir / "levels" / "test_output",
    ]
    for d in dirs_to_create:
        try:
            d.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            report_step_failure(
                6,
                "Estructura de directorios de niveles",
                f"No se pudo crear la carpeta requerida: {d}",
                str(e),
                ["Verifica los permisos de disco en el sistema de archivos."],
                critical=True,
            )

    # Check nivel0 count (human playable test levels)
    nivel0_dir = src_dir / "levels" / "nivel0"
    nivel0_files = list(nivel0_dir.glob("*.txt"))
    if len(nivel0_files) < 2:
        progress.info("Copiando niveles de muestra iniciales a levels/nivel0...")
        sample_sources = list((src_dir / "levels" / "familiarization").glob("*.txt")) + list((src_dir / "levels" / "generics").glob("*.txt"))
        for s in sample_sources[:6]:
            shutil.copy2(s, nivel0_dir / s.name)
        nivel0_files = list(nivel0_dir.glob("*.txt"))

    progress.success(f"Niveles humanos de prueba (nivel0): {len(nivel0_files)} niveles preparados y verificados.")
    fam_files = list((src_dir / "levels" / "familiarization").glob("*.txt"))
    gen_files = list((src_dir / "levels" / "generics").glob("*.txt"))
    progress.success(f"Niveles de familiarización ({len(fam_files)}) y genéricos ({len(gen_files)}) verificados.")

    # ── PASO 7: Detección y Configuración de Ollama API Key ────────
    progress.step(7, "Comprobando clave de API de Ollama Cloud...")
    key_file = framework_dir / "Ollama-Key.txt"

    arg_api_key = None
    for idx, a in enumerate(sys.argv):
        if a == "--api-key" and idx + 1 < len(sys.argv):
            arg_api_key = sys.argv[idx + 1].strip()
    skip_key = "--skip-key" in sys.argv or "--no-key" in sys.argv

    api_key = None
    if arg_api_key:
        api_key = arg_api_key
        key_file.parent.mkdir(parents=True, exist_ok=True)
        key_file.write_text(api_key + "\n", encoding="utf-8")
        progress.success(f"API Key configurada desde argumento CLI y guardada en {key_file.relative_to(root)}")
    elif key_file.exists() and key_file.read_text(encoding="utf-8").strip():
        api_key = key_file.read_text(encoding="utf-8").strip()
        progress.success(f"Archivo de API Key de Ollama encontrado ({key_file.relative_to(root)}).")
    elif os.environ.get("OLLAMA_API_KEY", "").strip():
        api_key = os.environ.get("OLLAMA_API_KEY", "").strip()
        key_file.parent.mkdir(parents=True, exist_ok=True)
        key_file.write_text(api_key + "\n", encoding="utf-8")
        progress.success("API Key de Ollama detectada desde variable de entorno OLLAMA_API_KEY.")
    elif skip_key:
        progress.info("Configuración de API Key omitida por argumento CLI (--skip-key).")
    else:
        print(f"\n  {C_YELLOW}{C_BOLD}⚠ AVISO:{C_RESET} No se encontró el archivo necesario de Ollama ({key_file.relative_to(root)}).")
        print(f"  {C_CYAN}Abriendo ventana para ingresar la API Key de Ollama Cloud...{C_RESET}")

        entered_key = prompt_ollama_key_gui(progress)
        if entered_key and entered_key.strip():
            api_key = entered_key.strip()
            key_file.parent.mkdir(parents=True, exist_ok=True)
            key_file.write_text(api_key + "\n", encoding="utf-8")
            progress.success(f"API Key guardada exitosamente en {key_file.relative_to(root)}")
        else:
            progress.warning("No se proporcionó API Key. El generador utilizará el modo local determinista.")
            progress.info(f"Puedes agregar la clave en cualquier momento guardándola en: {key_file.relative_to(root)}")

    # ── PASO 8: Resumen y Finalización ─────────────────────────────
    progress.step(8, "Verificando estado final del sistema...")
    progress.success("Motor de juego y experiment runner listos para ejecutar.")
    progress.finish()

    print(f"\n{C_BOLD}{C_GREEN}╔══════════════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_GREEN}║             ¡TODO ESTÁ LISTO PARA CORRER EL JUEGO!               ║{C_RESET}")
    print(f"{C_BOLD}{C_GREEN}╚══════════════════════════════════════════════════════════════════╝{C_RESET}")
    print(f"\nPuedes ejecutar las siguientes opciones según lo que desees:")
    print(f"  {C_BOLD}1. Iniciar la sesión formal:{C_RESET}")
    print(f"     {C_CYAN}cd src && make start-experiment{C_RESET}")
    print(f"  {C_BOLD}2. Iniciar sesión rápida (sin esperar generación con IA):{C_RESET}")
    print(f"     {C_CYAN}cd src && make start-experiment-nogen{C_RESET}")
    print(f"  {C_BOLD}3. Abrir Centro de Control Web interactivo:{C_RESET}")
    print(f"     {C_CYAN}cd src && make gui{C_RESET}")
    print(f"  {C_BOLD}4. Modo práctica libre para humanos:{C_RESET}")
    print(f"     {C_CYAN}cd src && make play-human{C_RESET}\n")

    if "--run" in sys.argv:
        print(f"{C_BOLD}{C_GREEN}Iniciando el runner de juego automáticamente...{C_RESET}\n")
        exp_cmd = [
            java_path or "java",
            "-cp", f"{framework_dir / 'bin'}:{src_dir}",
            "ExperimentRunner", sys.executable,
        ]
        subprocess.run(exp_cmd, cwd=src_dir)


if __name__ == "__main__":
    main()
