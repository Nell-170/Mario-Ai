#!/usr/bin/env python3
"""
db_tunnel.py — Túnel SSH hacia la VM de PostgreSQL y configuración centralizada.

Comandos (ejecutar desde src/, normalmente vía make):

  python tools/db_tunnel.py tunnel        Abre el túnel y lo mantiene hasta Ctrl+C   (make db-tunnel)
  python tools/db_tunnel.py push          Sube experiment/database.properties a la VM (make db-push-config)
  python tools/db_tunnel.py pull          Descarga database.properties desde la VM    (make db-pull-config)
  python tools/db_tunnel.py run -- CMD    Abre el túnel, descarga la configuración,
                                          ejecuta CMD y cierra el túnel al terminar  (make gui)

Equivale a:  ssh -N -L 15432:localhost:5432 investigacioncc@10.1.70.39
Los valores se leen de experiment/config.properties (claves db_tunnel_*), con valores
por defecto si no existen. Variables de entorno con el mismo nombre en mayúsculas
(p. ej. DB_TUNNEL_SSH) tienen prioridad.

Al descargar la configuración, db_host/db_port se reescriben a localhost/<puerto local
del túnel>, así el experimento siempre usa el túnel abierto por este script.
"""
import argparse
import os
import posixpath
import socket
import subprocess
import sys
import time
from pathlib import Path

SRC = Path(__file__).resolve().parents[1]
EXPERIMENT = SRC / "experiment"
CONFIG_FILE = EXPERIMENT / "config.properties"
DB_PROPS = EXPERIMENT / "database.properties"
IS_WINDOWS = os.name == "nt"

DEFAULTS = {
    "db_tunnel_enabled": "true",
    "db_tunnel_ssh": "investigacioncc@10.1.70.39",
    "db_tunnel_local_port": "15432",
    "db_tunnel_remote_port": "5432",
    "db_tunnel_remote_config": "~/mario-ai/database.properties",
}

PASSWORD_WAIT_SECONDS = 90  # tiempo para escribir la contraseña de SSH si no hay llave


def info(msg):
    print(f"[TUNEL] {msg}", flush=True)


def warn(msg):
    print(f"[TUNEL] ⚠ {msg}", file=sys.stderr, flush=True)


# ── configuración ──────────────────────────────────────────────
def load_props(path: Path) -> dict:
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def settings() -> dict:
    cfg = dict(DEFAULTS)
    cfg.update({k: v for k, v in load_props(CONFIG_FILE).items() if k.startswith("db_tunnel_")})
    for key in DEFAULTS:
        env = os.environ.get(key.upper())
        if env:
            cfg[key] = env
    return cfg


# ── utilidades SSH ─────────────────────────────────────────────
def port_open(port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def control_path(local_port: int):
    """Socket de conexión compartida (una sola contraseña para túnel + descarga). No existe en Windows."""
    if IS_WINDOWS:
        return None
    return f"/tmp/mario-db-tunnel-{os.getuid()}-{local_port}.sock"


def ssh_cmd(cfg, *extra, use_control=True):
    cmd = ["ssh", "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"]
    sock = control_path(int(cfg["db_tunnel_local_port"])) if use_control else None
    if sock and os.path.exists(sock):
        cmd += ["-o", f"ControlPath={sock}"]
    return cmd + list(extra)


# ── túnel ──────────────────────────────────────────────────────
def start_tunnel(cfg):
    """Abre el túnel en segundo plano. Devuelve el proceso, o None si ya había uno abierto."""
    local = int(cfg["db_tunnel_local_port"])
    remote = int(cfg["db_tunnel_remote_port"])
    target = cfg["db_tunnel_ssh"]
    if port_open(local):
        info(f"Ya hay un túnel abierto en localhost:{local}; se reutiliza.")
        return None

    cmd = ["ssh", "-N", "-L", f"{local}:localhost:{remote}",
           "-o", "ExitOnForwardFailure=yes",
           "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"]
    sock = control_path(local)
    if sock:
        if os.path.exists(sock):
            os.remove(sock)
        cmd += ["-o", "ControlMaster=yes", "-o", f"ControlPath={sock}"]
    cmd.append(target)

    info(f"Abriendo túnel: localhost:{local} -> {target} (puerto {remote} en la VM)")
    info("Si SSH pide contraseña, escríbela aquí.")
    proc = subprocess.Popen(cmd)
    deadline = time.time() + PASSWORD_WAIT_SECONDS
    while time.time() < deadline:
        if proc.poll() is not None:
            warn(f"SSH terminó con código {proc.returncode}. ¿Estás conectada a la VPN / red del laboratorio?")
            return False
        if port_open(local):
            info(f"Túnel listo en localhost:{local}.")
            return proc
        time.sleep(0.5)
    warn("El túnel no respondió a tiempo; se cancela.")
    stop_tunnel(proc, cfg)
    return False


def stop_tunnel(proc, cfg):
    if not proc:
        return
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    sock = control_path(int(cfg["db_tunnel_local_port"]))
    if sock and os.path.exists(sock):
        try:
            os.remove(sock)
        except OSError:
            pass
    info("Túnel cerrado.")


# ── configuración centralizada en la VM ────────────────────────
def pull_config(cfg) -> bool:
    remote_path = cfg["db_tunnel_remote_config"]
    result = subprocess.run(ssh_cmd(cfg, cfg["db_tunnel_ssh"], f"cat {remote_path}"),
                            capture_output=True, text=True)
    if result.returncode != 0 or "db_name" not in result.stdout:
        detail = (result.stderr or "").strip().splitlines()[-1:] or ["archivo vacío"]
        warn(f"No se pudo descargar {remote_path} de la VM ({detail[0]}).")
        if DB_PROPS.exists():
            warn("Se mantiene el database.properties local que ya existía.")
        else:
            warn("No hay database.properties local: los datos quedarán en experiment/db_pending/ hasta configurarlo.")
        return False

    local_port = cfg["db_tunnel_local_port"]
    lines = []
    for line in result.stdout.splitlines():
        key = line.split("=", 1)[0].strip()
        if key in ("db_host", "db_port"):
            continue
        lines.append(line)
    content = (f"# Descargado de la VM ({cfg['db_tunnel_ssh']}:{remote_path}) por tools/db_tunnel.py.\n"
               f"# db_host/db_port apuntan al túnel SSH local. No editar aquí: editar en la VM o usar make db-push-config.\n"
               f"db_host=localhost\n"
               f"db_port={local_port}\n" + "\n".join(lines).rstrip() + "\n")
    tmp = DB_PROPS.with_suffix(".properties.tmp")
    tmp.write_text(content, encoding="utf-8")
    if not IS_WINDOWS:
        os.chmod(tmp, 0o600)
    os.replace(tmp, DB_PROPS)
    info(f"Configuración descargada de la VM -> {DB_PROPS.relative_to(SRC)}")
    return True


def push_config(cfg) -> int:
    if not DB_PROPS.exists():
        warn(f"No existe {DB_PROPS}. Créalo a partir de database.properties.example.")
        return 1
    props = load_props(DB_PROPS)
    missing = [k for k in ("db_name", "db_user", "db_password") if not props.get(k)]
    if missing:
        warn(f"database.properties está incompleto (falta {', '.join(missing)}).")
        return 1
    remote_path = cfg["db_tunnel_remote_config"]
    remote_dir = posixpath.dirname(remote_path) or "."
    remote_cmd = f"mkdir -p {remote_dir} && umask 077 && cat > {remote_path} && chmod 600 {remote_path}"
    info(f"Subiendo {DB_PROPS.relative_to(SRC)} a {cfg['db_tunnel_ssh']}:{remote_path} ...")
    result = subprocess.run(ssh_cmd(cfg, cfg["db_tunnel_ssh"], remote_cmd),
                            input=DB_PROPS.read_text(encoding="utf-8"), text=True)
    if result.returncode != 0:
        warn("No se pudo subir el archivo.")
        return 1
    info("Listo. Las demás PCs lo descargan con make gui o make db-pull-config.")
    return 0


# ── comandos ───────────────────────────────────────────────────
def cmd_tunnel(cfg) -> int:
    proc = start_tunnel(cfg)
    if proc is False:
        return 1
    if proc is None:
        return 0
    info("Túnel activo. Presiona Ctrl+C para cerrarlo.")
    try:
        proc.wait()
    except KeyboardInterrupt:
        pass
    finally:
        stop_tunnel(proc, cfg)
    return 0


def cmd_pull(cfg) -> int:
    return 0 if pull_config(cfg) else 1


def cmd_run(cfg, child) -> int:
    if not child:
        warn("Falta el comando a ejecutar después de '--'.")
        return 2
    proc = None
    if cfg["db_tunnel_enabled"].lower() == "true":
        proc = start_tunnel(cfg)
        if proc is False:
            warn("Se continúa sin túnel: los datos se guardan en experiment/db_pending/ y se envían después (make sync-db).")
            proc = None
        else:
            pull_config(cfg)
    else:
        info("Túnel desactivado (db_tunnel_enabled=false en config.properties).")
    try:
        return subprocess.call(child)
    except KeyboardInterrupt:
        return 130
    finally:
        stop_tunnel(proc, cfg)


def main() -> int:
    parser = argparse.ArgumentParser(description="Túnel SSH a la VM de PostgreSQL del experimento.")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("tunnel", help="Abre el túnel y lo mantiene hasta Ctrl+C.")
    sub.add_parser("push", help="Sube experiment/database.properties a la VM.")
    sub.add_parser("pull", help="Descarga database.properties desde la VM.")
    run = sub.add_parser("run", help="Abre el túnel, descarga la configuración y ejecuta un comando.")
    run.add_argument("child", nargs=argparse.REMAINDER, help="Comando a ejecutar (después de --).")
    args = parser.parse_args()
    cfg = settings()

    if args.command == "tunnel":
        return cmd_tunnel(cfg)
    if args.command == "push":
        return push_config(cfg)
    if args.command == "pull":
        return cmd_pull(cfg)
    child = args.child[1:] if args.child[:1] == ["--"] else args.child
    return cmd_run(cfg, child)


if __name__ == "__main__":
    raise SystemExit(main())
