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
    candidates = [
        Path("/home/marioc/.local/jdk-25/bin"),
        Path("/home/marioc/.local/bin"),
        Path("/home/marioc/.antigravity-ide/extensions/redhat.java-1.57.0-linux-x64/jre/25.0.4.1-linux-x86_64/bin"),
    ]
    current_path = os.environ.get("PATH", "")
    for cand in candidates:
        if cand.exists() and (cand / "javac").exists():
            if str(cand) not in current_path:
                os.environ["PATH"] = f"{cand}:{current_path}"
                current_path = os.environ["PATH"]
            break

ensure_jdk_path()

try:
    import ollama_keys
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import ollama_keys


def get_ollama_status():
    keys = ollama_keys.load_keys()
    key_file = ollama_keys.resolve_key_file()
    sm = ollama_keys.KeyStateManager()
    key_statuses = sm.get_all_status(keys) if keys else []
    avail_count = sum(1 for s in key_statuses if s["state"] == "lista")
    first_masked = key_statuses[0]["masked"] if key_statuses else ""

    return {
        "configured": len(keys) > 0,
        "count": len(keys),
        "available_count": avail_count,
        "masked_key": first_masked,
        "key_path": str(key_file.relative_to(PROJECT_ROOT)) if key_file.exists() else "mario_ai_framework/Ollama-Key.txt",
        "keys": key_statuses,
    }


def save_ollama_keys_raw(text: str) -> bool:
    key_file = ollama_keys.resolve_key_file()
    key_file.parent.mkdir(parents=True, exist_ok=True)
    lines = [line.strip() for line in text.strip().splitlines() if line.strip() and not line.startswith("#")]
    seen = set()
    cleaned = []
    for l in lines:
        if l not in seen:
            seen.add(l)
            cleaned.append(l)
    tmp = key_file.with_suffix(".tmp")
    tmp.write_text("\n".join(cleaned) + "\n", encoding="utf-8")
    if os.name != "nt":
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
    tmp.replace(key_file)
    return True


def append_ollama_key(key_value: str) -> bool:
    key_value = key_value.strip()
    if not key_value:
        return False
    keys = ollama_keys.load_keys()
    lines = [l.strip() for l in key_value.splitlines() if l.strip() and not l.startswith("#")]
    for l in lines:
        if l not in keys:
            keys.append(l)
    return save_ollama_keys_raw("\n".join(keys))


def save_ollama_key(key_value: str) -> bool:
    """Compatibilidad: anexa o guarda claves."""
    return append_ollama_key(key_value)


def remove_ollama_key(target_id: str) -> bool:
    keys = ollama_keys.load_keys()
    remaining = [k for k in keys if ollama_keys.key_id(k) != target_id]
    save_ollama_keys_raw("\n".join(remaining))
    ollama_keys.KeyStateManager().reset(target_id)
    return True


def get_generation_config():
    cfg_file = ROOT / "experiment" / "config.properties"
    props = {}
    if cfg_file.exists():
        for line in cfg_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()

    mode = props.get("generation_mode", "JeissonServer")
    jeisson_url = props.get("jeisson_server_url", "http://jeisson.ecci.ucr.ac.cr:8080")
    hpc_port = props.get("hpc_port", "8888")
    hpc_user = props.get("hpc_user", "mariogabriel.cordero@172.16.24.2")
    hpc_node = props.get("hpc_node", "auto")

    alive = False
    ping_ms = 0
    target_url = jeisson_url if "jeisson" in mode.lower() else f"http://localhost:{hpc_port}"
    if "nativo" not in mode.lower() and "local" not in mode.lower():
        try:
            import urllib.request
            t0 = time.time()
            req = urllib.request.Request(f"{target_url.rstrip('/')}/health", headers={"User-Agent": "MarioAI-Dashboard/1.0"})
            with urllib.request.urlopen(req, timeout=1.8) as resp:
                if resp.status == 200:
                    alive = True
                    ping_ms = round((time.time() - t0) * 1000)
        except Exception:
            alive = False

    return {
        "mode": mode,
        "jeisson_url": jeisson_url,
        "hpc_port": hpc_port,
        "hpc_user": hpc_user,
        "hpc_node": hpc_node,
        "target_url": target_url,
        "alive": alive,
        "ping_ms": ping_ms,
    }


def get_sequence_server_status():
    cfg_file = ROOT / "experiment" / "config.properties"
    url = ""
    if cfg_file.exists():
        for line in cfg_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line.startswith("sequence_server_url") and "=" in line:
                url = line.split("=", 1)[1].strip()
    if not url:
        return {"configured": False, "alive": False, "url": "", "ping_ms": 0}
    alive = False
    ping_ms = 0
    try:
        import urllib.request
        t0 = time.time()
        base = url.rstrip("/")
        if base.endswith("/obtener_secuencia"):
            base = base[: -len("/obtener_secuencia")]
        req = urllib.request.Request(base + "/", headers={"User-Agent": "MarioAI-Dashboard/1.0"})
        try:
            urllib.request.urlopen(req, timeout=1.8).close()
        except urllib.error.HTTPError:
            pass
        alive = True
        ping_ms = round((time.time() - t0) * 1000)
    except Exception:
        alive = False
    return {"configured": True, "alive": alive, "url": url, "ping_ms": ping_ms}


def normalize_sequence_url(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        return ""
    if "://" not in raw:
        raw = "http://" + raw
    host = raw.split("://", 1)[1].split("/", 1)[0]
    if ":" not in host:
        scheme, rest = raw.split("://", 1)
        parts = rest.split("/", 1)
        raw = scheme + "://" + parts[0] + ":5000" + ("/" + parts[1] if len(parts) > 1 else "")
    return raw.rstrip("/")


def save_sequence_server_url(url: str) -> bool:
    cfg_file = ROOT / "experiment" / "config.properties"
    if not cfg_file.exists():
        return False
    text = cfg_file.read_text(encoding="utf-8")
    nl = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines()
    done = False
    for i, line in enumerate(lines):
        if line.strip().startswith("sequence_server_url"):
            lines[i] = f"sequence_server_url={url}"
            done = True
    if not done:
        lines.append(f"sequence_server_url={url}")
    cfg_file.write_text(nl.join(lines) + nl, encoding="utf-8", newline="")
    return True


def save_generation_config(new_mode: str, new_url: str = None, hpc_user: str = None, hpc_node: str = None, hpc_port: str = None):
    cfg_file = ROOT / "experiment" / "config.properties"
    if not cfg_file.exists():
        return False
    lines = cfg_file.read_text(encoding="utf-8").splitlines()
    updates = {"generation_mode": new_mode}
    if new_url:
        updates["jeisson_server_url"] = new_url
    if hpc_user:
        updates["hpc_user"] = hpc_user.strip()
    if hpc_node:
        updates["hpc_node"] = hpc_node.strip()
    if hpc_port:
        updates["hpc_port"] = str(hpc_port).strip()
    if "jeisson" in new_mode.lower():
        updates["server_url"] = new_url or "http://jeisson.ecci.ucr.ac.cr:8080"
    elif "tunel" in new_mode.lower() or "tunnel" in new_mode.lower():
        updates["server_url"] = f"http://localhost:{hpc_port or '8888'}"

    new_lines = []
    found = set()
    for line in lines:
        trimmed = line.strip()
        replaced = False
        for k, v in updates.items():
            if trimmed.startswith(f"{k}=") or trimmed.startswith(f"{k} "):
                new_lines.append(f"{k}={v}")
                found.add(k)
                replaced = True
                break
        if not replaced:
            new_lines.append(line)
    for k, v in updates.items():
        if k not in found:
            new_lines.append(f"{k}={v}")

    cfg_file.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
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


def run_target_thread(target, label, level=None):
    global ACTIVE_PROCESS
    level_hint = f' LEVEL="{level}"' if level else ''
    append_log(f"--- Iniciando tarea: {label} (make {target}{level_hint}) ---", "system")

    # If it's a graphical task, warn the user that a Java window opens on their desktop
    if target in ("play-human", "play-level", "play-levels-generated", "start-experiment", "start-experiment-nogen", "play-and-generate"):
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
    if level:
        cmd.append(f"LEVEL={level}")

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

    def count_participant_levels():
        p_dir = ROOT / "experiment" / "results"
        if not p_dir.exists():
            return 0
        total = 0
        for p in p_dir.iterdir():
            if not p.is_dir():
                continue
            g = p / "generated"
            if not g.exists():
                continue
            direct = [f for f in g.glob("*.txt") if f.stat().st_size > 0]
            if direct:
                total += len(direct)
            else:
                total += len([f for f in g.glob("gen*/*.txt") if f.stat().st_size > 0])
        return total

    return {
        "levels_nivel0": count_files("levels/nivel0"),
        "levels_generated": count_files("levels/generated") + count_participant_levels(),
        "levels_converted": count_files("levels/converted"),
        "telemetry_sessions": count_files("telemetry", ".json"),
        "experiment_participants": count_dirs("experiment/results"),
    }


def list_levels(folder_key):
    if folder_key == "participants":
        results_dir = ROOT / "experiment" / "results"
        if not results_dir.exists():
            return []
        items = []
        for p in sorted(results_dir.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
            if not p.is_dir():
                continue
            gen_dir = p / "generated"
            if not gen_dir.exists():
                continue
            direct = sorted([f for f in gen_dir.glob("*.txt") if f.stat().st_size > 0], key=lambda x: x.name)
            files_to_add = direct if direct else sorted([f for f in gen_dir.glob("gen*/*.txt") if f.stat().st_size > 0], key=lambda x: x.name)
            for f in files_to_add:
                st = f.stat()
                rel_path = str(f.relative_to(results_dir))
                items.append({
                    "name": f"[{p.name}] {f.name}",
                    "file": rel_path,
                    "folder": "participants",
                    "size": st.st_size,
                    "modified": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(st.st_mtime))
                })
        return items

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
        if st.st_size == 0:
            continue
        items.append({
            "name": f.name,
            "folder": folder_key,
            "size": st.st_size,
            "modified": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(st.st_mtime))
        })
    return items


def get_level_content(folder_key, file_name):
    if folder_key == "participants":
        results_dir = (ROOT / "experiment" / "results").resolve()
        target_path = (results_dir / file_name).resolve()
        if not str(target_path).startswith(str(results_dir)):
            return None
        if not target_path.exists() or not target_path.is_file():
            return None
        return target_path.read_text(encoding="utf-8", errors="replace")

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

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        query = parse_qs(url.query)

        if path in ("/recibidor", "/kiosk", "/recibidor/", "/kiosk/"):
            self.send_response(302)
            self.send_header("Location", "/recibidor.html")
            self.end_headers()
            return

        if path == "/api/stats":
            stats = get_stats()
            stats["ollama"] = get_ollama_status()
            self.send_json(stats)
        elif path == "/api/ollama-key":
            self.send_json(get_ollama_status())
        elif path == "/api/sequence-server":
            self.send_json(get_sequence_server_status())
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
        elif path == "/api/generation-config":
            self.send_json(get_generation_config())
        elif path == "/api/detect-slurm":
            user_target = query.get("user", ["mariogabriel.cordero@172.16.24.2"])[0]
            if "@" not in user_target:
                user_target += "@172.16.24.2"
            detected = None
            try:
                res = subprocess.run(
                    ["ssh", "-o", "BatchMode=yes", user_target, "squeue -n mariogpt-srv -ho '%N'"],
                    capture_output=True, text=True, timeout=5
                )
                if res.returncode == 0:
                    for token in res.stdout.split():
                        token = token.strip()
                        if "cngpu" in token:
                            detected = token
                            break
            except Exception:
                pass
            self.send_json({"detected": detected or "cngpu002"})
        else:
            return super().do_GET()

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/api/generation-config":
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8")
            try:
                data = json.loads(post_body)
            except Exception:
                self.send_error(400, "JSON inválido")
                return

            new_mode = data.get("mode", "JeissonServer")
            new_url = data.get("url")
            hpc_user = data.get("hpc_user")
            hpc_node = data.get("hpc_node")
            hpc_port = data.get("hpc_port")
            saved = save_generation_config(new_mode, new_url, hpc_user, hpc_node, hpc_port)
            append_log(f"⚡ Configuración de MarioGPT actualizada ({new_mode})", "system")
            self.send_json({"status": "saved" if saved else "error", "config": get_generation_config()})
            return

        elif url.path == "/api/sequence-server":
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8")
            try:
                data = json.loads(post_body)
            except Exception:
                self.send_error(400, "JSON inválido")
                return
            new_url = normalize_sequence_url(str(data.get("url", "")))
            saved = save_sequence_server_url(new_url)
            append_log(f"⚖️ Servidor de balanceo: {new_url or 'desactivado (asignación local)'}", "system")
            self.send_json({"status": "saved" if saved else "error", "details": get_sequence_server_status()})
            return

        elif url.path in ("/api/ollama-key", "/api/ollama-key/reset", "/api/ollama-key/remove"):
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8")
            data = {}
            if post_body:
                try:
                    data = json.loads(post_body)
                except Exception:
                    self.send_error(400, "JSON inválido")
                    return

            action = data.get("action", "")
            if url.path == "/api/ollama-key/reset" or action == "reset":
                target_id = data.get("id")
                ollama_keys.KeyStateManager().reset(target_id)
                append_log(f"🔄 Enfriamientos de Ollama restablecidos ({target_id or 'todas las claves'}).", "system")
                self.send_json({"status": "reset", "details": get_ollama_status()})
                return

            if url.path == "/api/ollama-key/remove" or action == "remove":
                target_id = data.get("id", "").strip()
                if not target_id:
                    self.send_error(400, "Falta el id de la clave a eliminar")
                    return
                remove_ollama_key(target_id)
                append_log(f"🗑️ Clave de Ollama {target_id} eliminada.", "system")
                self.send_json({"status": "removed", "details": get_ollama_status()})
                return

            raw_keys = data.get("keys") or data.get("key", "").strip()
            if not raw_keys:
                self.send_error(400, "La clave no puede estar vacía")
                return

            if action == "replace":
                save_ollama_keys_raw(raw_keys)
                append_log("🔑 Lista de claves de Ollama Cloud reemplazada correctamente.", "system")
            else:
                append_ollama_key(raw_keys)
                append_log("🔑 Clave(s) de Ollama Cloud agregada(s) correctamente.", "system")

            self.send_json({"status": "saved", "configured": True, "details": get_ollama_status()})
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
            level = data.get("level")

            with STATE_LOCK:
                if CURRENT_TASK["running"]:
                    self.send_json({"error": "Ya hay una tarea en ejecución"}, status=409)
                    return

            threading.Thread(target=run_target_thread, args=(target, label, level), daemon=True).start()
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
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return start_port


def main():
    ThreadingHTTPServer.allow_reuse_address = True
    port = find_free_port(5050)
    server_address = ("127.0.0.1", port)

    UI_DIR.mkdir(parents=True, exist_ok=True)

    httpd = ThreadingHTTPServer(server_address, DashboardHandler)
    open_recibidor = ("--recibidor" in sys.argv or "--kiosk" in sys.argv)
    target_path = "/recibidor.html" if open_recibidor else ""
    url = f"http://localhost:{port}{target_path}"
    dash_url = f"http://localhost:{port}"
    recibidor_url = f"http://localhost:{port}/recibidor.html"

    print(f"\n=======================================================")
    print(f"  🍄 Mario AI — Modern Web Control Center")
    print(f"  Dashboard: {dash_url}")
    print(f"  Recibidor Kiosco: {recibidor_url}")
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
