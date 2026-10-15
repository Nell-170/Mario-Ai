#!/usr/bin/env python3
"""
dashboard.py — Modern Local Web GUI & Control Center for Mario AI.
Zero external dependencies (uses standard Python library).
"""
import os
import sys
import json
import time
import re
import socket
import threading
import subprocess
import webbrowser
from pathlib import Path
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = ROOT.parent
UI_DIR = Path(__file__).resolve().parent / "dashboard_ui"

# Ensure JDK 25 is in PATH if not already present
def ensure_jdk_path():
    ide_java = Path("/home/marioc/.antigravity-ide/extensions/redhat.java-1.57.0-linux-x64/jre/25.0.4.1-linux-x86_64/bin")
    if ide_java.exists() and (ide_java / "javac").exists():
        current_path = os.environ.get("PATH", "")
        if str(ide_java) not in current_path:
            os.environ["PATH"] = f"{ide_java}:{current_path}"

ensure_jdk_path()


def get_ollama_status():
    key_file = PROJECT_ROOT / "mario_ai_framework" / "Ollama-Key.txt"

    key = ""
    if key_file.exists() and key_file.read_text(encoding="utf-8").strip():
        key = key_file.read_text(encoding="utf-8").strip()
    elif os.environ.get("OLLAMA_API_KEY", "").strip():
        key = os.environ.get("OLLAMA_API_KEY", "").strip()

    masked = ""
    if key:
        if len(key) > 8:
            masked = key[:4] + "••••••••" + key[-4:]
        else:
            masked = "••••••••"

    return {
        "configured": bool(key),
        "masked_key": masked,
        "key_path": str(key_file.relative_to(PROJECT_ROOT)) if key else "mario_ai_framework/Ollama-Key.txt"
    }


def save_ollama_key(key_value: str):
    key_value = key_value.strip()
    key_file = PROJECT_ROOT / "mario_ai_framework" / "Ollama-Key.txt"
    key_file.parent.mkdir(parents=True, exist_ok=True)
    key_file.write_text(key_value + "\n", encoding="utf-8")
    return True

# Reentrant lock to prevent self-deadlocks
STATE_LOCK = threading.RLock()
CURRENT_TASK = {
    "running": False,
    "target": None,
    "label": None,
    "progress": 0,
    "started_at": 0,
    "duration": 0,
    "exit_code": None,
    "status": "idle",  # idle, running, success, error, stopped
}
LOG_LINES = []
MAX_LOGS = 2000
ACTIVE_PROCESS = None

PROGRESS_REGEX = re.compile(r"\[\s*(\d{1,3})%\]")
TQDM_REGEX = re.compile(r"(\d{1,3})%\|")


def append_log(text, log_type="stdout"):
    timestamp = time.strftime("%H:%M:%S")
    with STATE_LOCK:
        LOG_LINES.append({
            "time": timestamp,
            "text": text,
            "type": log_type
        })
        if len(LOG_LINES) > MAX_LOGS:
            LOG_LINES.pop(0)


def extract_progress(line):
    # Match [ XX%] or tqdm XX%|
    m = PROGRESS_REGEX.search(line)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            pass
    m2 = TQDM_REGEX.search(line)
    if m2:
        try:
            return int(m2.group(1))
        except ValueError:
            pass
    return None


def run_target_thread(target, label):
    global ACTIVE_PROCESS
    append_log(f"--- Iniciando tarea: {label} (make {target}) ---", "system")

    # If it's a graphical task, warn the user that a Java window opens on their desktop
    if target in ("play-human", "play-levels-generated", "start-experiment", "start-experiment-nogen", "play-and-generate"):
        append_log("🎮 Se abrirá una ventana gráfica de Java en tu pantalla. La tarea se mantendrá activa mientras juegues.", "system")
    elif target == "install":
        append_log("🚀 Iniciando instalador unificado Mario AI (entorno virtual, repos, dependencias, compilación y niveles)...", "system")

    with STATE_LOCK:
        CURRENT_TASK["running"] = True
        CURRENT_TASK["target"] = target
        CURRENT_TASK["label"] = label
        CURRENT_TASK["progress"] = 0
        CURRENT_TASK["started_at"] = time.time()
        CURRENT_TASK["duration"] = 0
        CURRENT_TASK["exit_code"] = None
        CURRENT_TASK["status"] = "running"

    is_windows = sys.platform.startswith("win")
    cmd = ["make", target]

    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"

    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            universal_newlines=True,
            shell=is_windows,
            env=env
        )
        ACTIVE_PROCESS = proc

        for line in proc.stdout:
            clean_line = line.rstrip("\r\n")
            if not clean_line:
                continue

            p = extract_progress(clean_line)
            with STATE_LOCK:
                if p is not None:
                    CURRENT_TASK["progress"] = p
                CURRENT_TASK["duration"] = round(time.time() - CURRENT_TASK["started_at"], 1)

            append_log(clean_line, "stdout")

        proc.wait()
        exit_code = proc.returncode

        with STATE_LOCK:
            CURRENT_TASK["running"] = False
            CURRENT_TASK["exit_code"] = exit_code
            CURRENT_TASK["duration"] = round(time.time() - CURRENT_TASK["started_at"], 1)
            is_stopped = (CURRENT_TASK["status"] == "stopped")

            if exit_code == 0:
                CURRENT_TASK["progress"] = 100
                CURRENT_TASK["status"] = "success"
            elif is_stopped:
                pass
            else:
                CURRENT_TASK["status"] = "error"

        if exit_code == 0:
            append_log(f"--- Tarea completada con éxito ({label}) ---", "system")
        elif is_stopped:
            append_log(f"--- Tarea cancelada por el usuario ({label}) ---", "system")
        else:
            append_log(f"--- Error en tarea ({label}), código de salida: {exit_code} ---", "stderr")

    except Exception as exc:
        with STATE_LOCK:
            CURRENT_TASK["running"] = False
            CURRENT_TASK["status"] = "error"
            CURRENT_TASK["exit_code"] = -1
        append_log(f"Excepción al ejecutar tarea: {exc}", "stderr")
    finally:
        ACTIVE_PROCESS = None


def stop_active_task():
    global ACTIVE_PROCESS
    with STATE_LOCK:
        CURRENT_TASK["status"] = "stopped"
        proc = ACTIVE_PROCESS

    if proc is not None:
        try:
            proc.terminate()
            time.sleep(0.4)
            if proc.poll() is None:
                proc.kill()
            append_log("Se envió señal de detención al proceso.", "system")
            return True
        except Exception as e:
            append_log(f"Error al detener proceso: {e}", "stderr")
    return False


def get_stats():
    def count_files(dir_path, ext=".txt"):
        p = ROOT / dir_path
        if not p.exists():
            return 0
        return sum(1 for f in p.glob(f"*{ext}") if f.is_file())

    def count_dirs(dir_path):
        p = ROOT / dir_path
        if not p.exists():
            return 0
        return sum(1 for d in p.iterdir() if d.is_dir())

    return {
        "levels_nivel0": count_files("levels/nivel0"),
        "levels_generated": count_files("levels/generated"),
        "levels_converted": count_files("levels/converted"),
        "telemetry_sessions": count_files("telemetry", ".json"),
        "experiment_participants": count_dirs("experiment/results"),
    }


def list_levels(folder_key):
    folder_map = {
        "nivel0": ROOT / "levels" / "nivel0",
        "generated": ROOT / "levels" / "generated",
        "converted": ROOT / "levels" / "converted",
        "generics": ROOT / "levels" / "generics",
        "familiarization": ROOT / "levels" / "familiarization",
    }
    target_dir = folder_map.get(folder_key, folder_map["nivel0"])
    if not target_dir.exists():
        return []

    items = []
    for f in sorted(target_dir.glob("*.txt"), key=lambda x: x.stat().st_mtime, reverse=True):
        st = f.stat()
        items.append({
            "name": f.name,
            "folder": folder_key,
            "size": st.st_size,
            "modified": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(st.st_mtime))
        })
    return items


def get_level_content(folder_key, file_name):
    file_name = Path(file_name).name
    folder_map = {
        "nivel0": ROOT / "levels" / "nivel0",
        "generated": ROOT / "levels" / "generated",
        "converted": ROOT / "levels" / "converted",
        "generics": ROOT / "levels" / "generics",
        "familiarization": ROOT / "levels" / "familiarization",
    }
    target_path = folder_map.get(folder_key, folder_map["nivel0"]) / file_name
    if not target_path.exists() or not target_path.is_file():
        return None
    return target_path.read_text(encoding="utf-8", errors="replace")


def get_experiment_summary():
    results_dir = ROOT / "experiment" / "results"
    if not results_dir.exists():
        return {"participants": [], "responses_count": 0}

    participants = []
    for d in sorted(results_dir.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
        if not d.is_dir():
            continue
        meta_file = d / "participant.txt"
        meta = {}
        if meta_file.exists():
            for line in meta_file.read_text(encoding="utf-8", errors="ignore").splitlines():
                if "=" in line:
                    k, v = line.split("=", 1)
                    meta[k.strip()] = v.strip()
        participants.append({
            "id": d.name,
            "group": meta.get("group", "—"),
            "steps": meta.get("steps", "—"),
            "modified": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(d.stat().st_mtime))
        })

    resp_file = results_dir / "responses.tsv"
    resp_lines = 0
    if resp_file.exists():
        resp_lines = max(0, len(resp_file.read_text(encoding="utf-8", errors="ignore").splitlines()) - 1)

    return {"participants": participants, "responses_count": resp_lines}


class DashboardHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(UI_DIR), **kwargs)

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        query = parse_qs(url.query)

        if path == "/api/stats":
            stats = get_stats()
            stats["ollama"] = get_ollama_status()
            self.send_json(stats)
        elif path == "/api/ollama-key":
            self.send_json(get_ollama_status())
        elif path == "/api/status":
            with STATE_LOCK:
                task_data = dict(CURRENT_TASK)
                if task_data["running"]:
                    task_data["duration"] = round(time.time() - task_data["started_at"], 1)
            self.send_json(task_data)
        elif path == "/api/logs":
            since = int(query.get("since", [0])[0])
            with STATE_LOCK:
                logs = LOG_LINES[since:]
                total = len(LOG_LINES)
            self.send_json({"logs": logs, "total": total})
        elif path == "/api/levels":
            folder = query.get("folder", ["nivel0"])[0]
            self.send_json(list_levels(folder))
        elif path == "/api/level-content":
            folder = query.get("folder", ["nivel0"])[0]
            file_name = query.get("file", [""])[0]
            content = get_level_content(folder, file_name)
            if content is None:
                self.send_error(404, "Nivel no encontrado")
            else:
                self.send_json({"content": content, "file": file_name, "folder": folder})
        elif path == "/api/experiment-summary":
            self.send_json(get_experiment_summary())
        else:
            return super().do_GET()

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/api/ollama-key":
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8")
            try:
                data = json.loads(post_body)
            except Exception:
                self.send_error(400, "JSON inválido")
                return

            key = data.get("key", "").strip()
            if key:
                save_ollama_key(key)
                append_log("🔑 Clave de Ollama Cloud actualizada y guardada correctamente.", "system")
                self.send_json({"status": "saved", "configured": True, "details": get_ollama_status()})
            else:
                self.send_error(400, "La clave no puede estar vacía")
            return

        elif url.path == "/api/run":
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8")
            try:
                data = json.loads(post_body)
            except Exception:
                self.send_error(400, "JSON inválido")
                return

            target = data.get("target")
            label = data.get("label", target)

            with STATE_LOCK:
                if CURRENT_TASK["running"]:
                    self.send_json({"error": "Ya hay una tarea en ejecución"}, status=409)
                    return

            threading.Thread(target=run_target_thread, args=(target, label), daemon=True).start()
            self.send_json({"status": "started", "target": target, "label": label})

        elif url.path == "/api/stop":
            stopped = stop_active_task()
            self.send_json({"status": "stopped" if stopped else "no_process_to_stop"})
        else:
            self.send_error(404, "Endpoint no encontrado")

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


def find_free_port(start_port=5050):
    for port in range(start_port, start_port + 50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return start_port


def main():
    port = find_free_port(5050)
    server_address = ("127.0.0.1", port)

    UI_DIR.mkdir(parents=True, exist_ok=True)

    httpd = ThreadingHTTPServer(server_address, DashboardHandler)
    url = f"http://localhost:{port}"

    print(f"\n=======================================================")
    print(f"  🍄 Mario AI — Modern Web Control Center")
    print(f"  Dashboard disponible en: {url}")
    print(f"  Presiona Ctrl+C para detener el servidor")
    print(f"=======================================================\n")

    def open_browser():
        time.sleep(0.8)
        try:
            webbrowser.open(url)
        except Exception:
            pass

    threading.Thread(target=open_browser, daemon=True).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nCerrando servidor web Mario AI...")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
