#!/usr/bin/env python3
"""
ollama_keys.py — Gestor de rotación robusta de API Keys para Ollama Cloud.

Maneja:
- Carga de múltiples claves desde mario_ai_framework/Ollama-Key.txt y variables de entorno.
- Enmascaramiento y huella segura (sha256[:10]) para no exponer secretos en logs.
- Estado persistente de cooldowns con bloqueo seguro entre procesos (IPC lock).
- Clasificación de errores (429 cuota vs límite transitorio, 401/403 auth, 5xx transitorio).
- Rotación inmediata en agotamiento de cuota y fallback en cascada a Ollama local.
- CLI de diagnóstico: `status` y `reset`.
"""

import os
import sys
import json
import time
import random
import hashlib
from pathlib import Path
from typing import List, Dict, Optional, Tuple, Any
from urllib import request, error

# ── Configuración y constantes ──────────────────────────────────────────────
DEFAULT_OLLAMA_CLOUD_URL = "https://ollama.com/api/chat"
DEFAULT_OLLAMA_CLOUD_MODEL = "gpt-oss:120b"
DEFAULT_OLLAMA_LOCAL_URL = "http://127.0.0.1:11434/api/chat"

# Tiempos base de enfriamiento (segundos), configurables por variables de entorno
COOLDOWN_QUOTA_WEEKLY_S = int(os.environ.get("OLLAMA_COOLDOWN_QUOTA_WEEKLY_S", 12 * 3600))   # 12 horas
COOLDOWN_QUOTA_SESSION_S = int(os.environ.get("OLLAMA_COOLDOWN_QUOTA_SESSION_S", 3600))     # 1 hora
COOLDOWN_RATE_S = int(os.environ.get("OLLAMA_COOLDOWN_RATE_S", 10))                          # 10 segundos
COOLDOWN_AUTH_S = int(os.environ.get("OLLAMA_COOLDOWN_AUTH_S", 24 * 3600))                   # 24 horas

MAX_COOLDOWN_WEEKLY_S = 7 * 24 * 3600
MAX_COOLDOWN_SESSION_S = 6 * 3600
MAX_COOLDOWN_RATE_S = 300
MAX_COOLDOWN_AUTH_S = 7 * 24 * 3600

DEFAULT_BUDGET_S = float(os.environ.get("OLLAMA_TOTAL_BUDGET_S", 60.0))
CONNECT_TIMEOUT_S = 8.0
READ_TIMEOUT_S = 30.0

PROJECT_ROOT = Path(__file__).resolve().parents[2]


class AllKeysUnavailable(Exception):
    """Lanzada cuando todas las claves están en enfriamiento o fallaron."""
    pass


class NonRotatableError(Exception):
    """Lanzada ante errores donde rotar clave no ayuda (ej. 400 Bad Request o 422)."""
    pass


# ── Utilidades de clave e identidad segura ──────────────────────────────────
def key_id(key: str) -> str:
    """Devuelve un identificador hash de 10 caracteres seguro para logs y estado."""
    clean = key.strip()
    return hashlib.sha256(clean.encode("utf-8")).hexdigest()[:10]


def mask_key(key: str) -> str:
    """Enmascara la clave dejando 4 caracteres iniciales y 4 finales."""
    clean = key.strip()
    if not clean:
        return ""
    if len(clean) > 8:
        return clean[:4] + "••••••••" + clean[-4:]
    return "••••••••"


def resolve_key_file() -> Path:
    """Busca la ruta canónica absoluta al archivo Ollama-Key.txt."""
    candidates = [
        PROJECT_ROOT / "mario_ai_framework" / "Ollama-Key.txt",
        Path.cwd() / "mario_ai_framework" / "Ollama-Key.txt",
        Path.cwd() / "Ollama-Key.txt",
    ]
    for c in candidates:
        if c.exists():
            return c.resolve()
    return (PROJECT_ROOT / "mario_ai_framework" / "Ollama-Key.txt").resolve()


def resolve_state_file() -> Path:
    """Ruta canónica del archivo de estado compartido de enfriamiento."""
    return (PROJECT_ROOT / "mario_ai_framework" / ".ollama-key-state.json").resolve()


def load_keys(custom_file: Optional[Path] = None) -> List[str]:
    """
    Carga y deduplica las API keys disponibles en orden de prioridad:
    1. OLLAMA_API_KEYS (lista separada por comas)
    2. OLLAMA_API_KEY (clave única)
    3. Archivo Ollama-Key.txt (múltiples líneas)
    """
    keys: List[str] = []
    seen = set()

    def add_key(k: str):
        cleaned = k.strip().lstrip("\ufeff")  # Remover espacios y BOM
        if not cleaned or cleaned.startswith("#") or cleaned in seen:
            return
        # Soporte para comentarios al final de la línea (# etiqueta)
        if "#" in cleaned:
            cleaned = cleaned.split("#", 1)[0].strip()
        if cleaned and cleaned not in seen:
            seen.add(cleaned)
            keys.append(cleaned)

    # 1. Variables de entorno
    env_keys = os.environ.get("OLLAMA_API_KEYS", "").strip()
    if env_keys:
        for k in env_keys.split(","):
            add_key(k)

    single_env = os.environ.get("OLLAMA_API_KEY", "").strip()
    if single_env:
        add_key(single_env)

    # 2. Archivo en disco
    kf = custom_file or resolve_key_file()
    if kf.exists():
        try:
            content = kf.read_text(encoding="utf-8", errors="ignore")
            for line in content.splitlines():
                add_key(line)
        except OSError:
            pass

    return keys


# ── Clasificador de respuestas y errores HTTP ──────────────────────────────
class Outcome:
    def __init__(
        self,
        kind: str,
        cooldown_s: float = 0.0,
        rotate: bool = False,
        retry_same: bool = False,
        error_msg: str = "",
        status_code: Optional[int] = None,
    ):
        self.kind = kind
        self.cooldown_s = cooldown_s
        self.rotate = rotate
        self.retry_same = retry_same
        self.error_msg = error_msg
        self.status_code = status_code

    def __repr__(self):
        return (f"Outcome(kind={self.kind}, cooldown_s={self.cooldown_s}, "
                f"rotate={self.rotate}, retry_same={self.retry_same})")


def classify_response(
    status: int,
    headers: Any,
    body_text: str,
    exc: Optional[Exception] = None,
    current_strikes: int = 0
) -> Outcome:
    """
    Clasifica de forma pura el resultado de una llamada a Ollama Cloud.
    """
    body_lower = (body_text or "").lower()

    if status == 200:
        return Outcome(kind="ok", cooldown_s=0.0, rotate=False, retry_same=False, status_code=200)

    # 429 Too Many Requests
    if status == 429:
        # A. Cuota semanal agotada
        if "weekly" in body_lower:
            cd = min(COOLDOWN_QUOTA_WEEKLY_S * (2 ** current_strikes), MAX_COOLDOWN_WEEKLY_S)
            return Outcome(kind="quota_weekly", cooldown_s=cd, rotate=True, retry_same=False,
                           error_msg="Cuota semanal agotada (weekly usage limit)", status_code=429)

        # B. Cuota de sesión o límite de uso general
        if any(term in body_lower for term in ["session", "usage limit", "quota", "upgrade", "exceeded"]):
            cd = min(COOLDOWN_QUOTA_SESSION_S * (2 ** current_strikes), MAX_COOLDOWN_SESSION_S)
            return Outcome(kind="quota_session", cooldown_s=cd, rotate=True, retry_same=False,
                           error_msg="Límite de uso de sesión alcanzado", status_code=429)

        # C. Cabecera Retry-After
        retry_after = None
        if headers:
            ra_val = headers.get("Retry-After") if hasattr(headers, "get") else None
            if ra_val:
                try:
                    retry_after = float(ra_val)
                except ValueError:
                    pass

        if retry_after is not None and retry_after > 0:
            cd = max(1.0, min(retry_after, 86400.0))
            return Outcome(kind="rate", cooldown_s=cd, rotate=True, retry_same=False,
                           error_msg=f"Rate limit temporal (Retry-After: {cd}s)", status_code=429)

        # D. 429 sin pistas específicas: límite de tasa o concurrencia
        cd = min(COOLDOWN_RATE_S * (2 ** current_strikes), MAX_COOLDOWN_RATE_S)
        return Outcome(kind="rate", cooldown_s=cd, rotate=True, retry_same=False,
                       error_msg=f"Rate limit / concurrencia ({cd}s)", status_code=429)

    # 401 / 403 Autenticación fallida o clave rechazada
    if status in (401, 403):
        cd = min(COOLDOWN_AUTH_S * (2 ** current_strikes), MAX_COOLDOWN_AUTH_S)
        return Outcome(kind="auth", cooldown_s=cd, rotate=True, retry_same=False,
                       error_msg=f"Clave no autorizada o rechazada (HTTP {status})", status_code=status)

    # 402 Pago requerido (tratado similar a cuota agotada)
    if status == 402:
        cd = min(COOLDOWN_QUOTA_WEEKLY_S * (2 ** current_strikes), MAX_COOLDOWN_WEEKLY_S)
        return Outcome(kind="payment", cooldown_s=cd, rotate=True, retry_same=False,
                       error_msg="Pago requerido / suscripción vencida (HTTP 402)", status_code=402)

    # 400 / 422 Solicitud inválida: rotar clave no ayuda
    if status in (400, 404, 422):
        return Outcome(kind="bad_request", cooldown_s=0.0, rotate=False, retry_same=False,
                       error_msg=f"Error en estructura de la petición (HTTP {status}): {body_text[:100]}", status_code=status)

    # 5xx Errores de servidor u otros transitorios
    if 500 <= status < 600:
        return Outcome(kind="transient", cooldown_s=0.0, rotate=True, retry_same=True,
                       error_msg=f"Error en servidor Ollama (HTTP {status})", status_code=status)

    # Otros errores HTTP desconocidos
    return Outcome(kind="transient", cooldown_s=0.0, rotate=True, retry_same=False,
                   error_msg=f"Respuesta HTTP {status}: {body_text[:80]}", status_code=status)


# ── Gestor de estado de enfriamiento con bloqueo entre procesos ─────────────
class KeyStateManager:
    def __init__(self, state_file: Optional[Path] = None):
        self.state_file = state_file or resolve_state_file()
        self.lock_file = self.state_file.with_suffix(".json.lock")

    def _lock(self):
        """Bloqueo de archivo multiplataforma (fcntl en Unix, no-op seguro en Windows)."""
        self.lock_file.parent.mkdir(parents=True, exist_ok=True)
        f = open(self.lock_file, "w")
        if os.name != "nt":
            import fcntl
            fcntl.flock(f, fcntl.LOCK_EX)
        return f

    def _unlock(self, f):
        if os.name != "nt":
            import fcntl
            try:
                fcntl.flock(f, fcntl.LOCK_UN)
            except OSError:
                pass
        try:
            f.close()
        except OSError:
            pass

    def load_data(self) -> Dict[str, Any]:
        if not self.state_file.exists():
            return {"version": 1, "keys": {}}
        try:
            raw = self.state_file.read_text(encoding="utf-8")
            data = json.loads(raw)
            if data.get("version") == 1 and isinstance(data.get("keys"), dict):
                return data
        except Exception:
            pass
        return {"version": 1, "keys": {}}

    def save_data(self, data: Dict[str, Any]):
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        if os.name != "nt":
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
        tmp.replace(self.state_file)

    def is_available(self, key: str, now: Optional[float] = None) -> bool:
        kid = key_id(key)
        now_ts = now if now is not None else time.time()
        lf = self._lock()
        try:
            data = self.load_data()
            kinfo = data["keys"].get(kid)
            if not kinfo:
                return True
            return now_ts >= kinfo.get("cooldown_until", 0.0)
        finally:
            self._unlock(lf)

    def get_available_keys(self, keys: List[str], now: Optional[float] = None) -> List[str]:
        now_ts = now if now is not None else time.time()
        lf = self._lock()
        try:
            data = self.load_data()
            available = []
            for k in keys:
                kid = key_id(k)
                kinfo = data["keys"].get(kid)
                if not kinfo or now_ts >= kinfo.get("cooldown_until", 0.0):
                    available.append(k)
            return available
        finally:
            self._unlock(lf)

    def min_cooldown_remaining(self, keys: List[str], now: Optional[float] = None) -> Optional[float]:
        now_ts = now if now is not None else time.time()
        lf = self._lock()
        try:
            data = self.load_data()
            remaining = []
            for k in keys:
                kid = key_id(k)
                kinfo = data["keys"].get(kid)
                if kinfo:
                    cd = kinfo.get("cooldown_until", 0.0) - now_ts
                    if cd > 0:
                        remaining.append(cd)
            return min(remaining) if remaining else None
        finally:
            self._unlock(lf)

    def get_strikes(self, key: str) -> int:
        kid = key_id(key)
        lf = self._lock()
        try:
            data = self.load_data()
            kinfo = data["keys"].get(kid, {})
            return kinfo.get("strikes", 0)
        finally:
            self._unlock(lf)

    def record_outcome(self, key: str, outcome: Outcome, now: Optional[float] = None):
        kid = key_id(key)
        now_ts = now if now is not None else time.time()
        lf = self._lock()
        try:
            data = self.load_data()
            kinfo = data["keys"].setdefault(kid, {
                "cooldown_until": 0.0,
                "reason": None,
                "strikes": 0,
                "last_ok": 0.0,
                "last_error": None,
                "last_status": None,
            })

            if outcome.kind == "ok":
                kinfo["strikes"] = 0
                kinfo["cooldown_until"] = 0.0
                kinfo["reason"] = None
                kinfo["last_ok"] = now_ts
                kinfo["last_error"] = None
                kinfo["last_status"] = 200
            else:
                if outcome.cooldown_s > 0:
                    kinfo["cooldown_until"] = now_ts + outcome.cooldown_s
                    kinfo["reason"] = outcome.kind
                    kinfo["strikes"] = kinfo.get("strikes", 0) + 1
                kinfo["last_error"] = outcome.error_msg
                kinfo["last_status"] = outcome.status_code

            self.save_data(data)
        finally:
            self._unlock(lf)

    def reset(self, target_key_id: Optional[str] = None):
        lf = self._lock()
        try:
            if not self.state_file.exists():
                return
            data = self.load_data()
            if target_key_id:
                data["keys"].pop(target_key_id, None)
            else:
                data["keys"].clear()
            self.save_data(data)
        finally:
            self._unlock(lf)

    def reset_cooldowns(self, keys: Optional[List[str]] = None) -> int:
        """Reinicia el enfriamiento de las claves dadas (o de todas si es None)."""
        if keys:
            count = 0
            for k in keys:
                self.reset(key_id(k))
                count += 1
            return count
        self.reset()
        return 1

    def get_all_status(self, keys: List[str], now: Optional[float] = None) -> List[Dict[str, Any]]:
        now_ts = now if now is not None else time.time()
        lf = self._lock()
        try:
            data = self.load_data()
            results = []
            for idx, k in enumerate(keys, 1):
                kid = key_id(k)
                kinfo = data["keys"].get(kid, {})
                cd_until = kinfo.get("cooldown_until", 0.0)
                rem = max(0.0, cd_until - now_ts)
                reason = kinfo.get("reason")
                state = "lista"
                if rem > 0:
                    state = "apartada" if reason != "auth" else "inválida"
                results.append({
                    "index": idx,
                    "id": kid,
                    "masked": mask_key(k),
                    "state": state,
                    "reason": reason,
                    "cooldown_remaining_s": round(rem, 1),
                    "strikes": kinfo.get("strikes", 0),
                    "last_error": kinfo.get("last_error"),
                    "last_status": kinfo.get("last_status"),
                })
            return results
        finally:
            self._unlock(lf)

    get_status_list = get_all_status


# ── Invocación HTTP y Bucle de Rotación ─────────────────────────────────────
def _http_chat_attempt(
    key: str,
    payload: dict,
    url: str,
    timeout: float
) -> Tuple[int, Any, str, Optional[str]]:
    """
    Ejecuta un intento individual de llamada HTTP.
    Devuelve: (status_code, headers, body_text, parsed_content)
    """
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {key}",
    }
    req = request.Request(url, data=data, headers=headers)
    try:
        with request.urlopen(req, timeout=timeout) as resp:
            status = resp.status if hasattr(resp, "status") else 200
            body_bytes = resp.read()
            body_text = body_bytes.decode("utf-8", errors="ignore")
            content = None
            try:
                body = json.loads(body_text)
                if isinstance(body, dict):
                    if "message" in body and isinstance(body["message"], dict):
                        content = body["message"].get("content")
                    elif "response" in body:
                        content = body.get("response")
            except Exception:
                pass
            return (status, resp.headers, body_text, content.strip() if content else None)
    except error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="ignore")
        return (exc.code, exc.headers, err_body, None)
    except error.URLError as exc:
        return (503, {}, str(exc.reason), None)
    except Exception as exc:
        return (500, {}, str(exc), None)


def ollama_chat(
    payload: dict,
    url: str = DEFAULT_OLLAMA_CLOUD_URL,
    custom_keys: Optional[List[str]] = None,
    state_manager: Optional[KeyStateManager] = None,
    budget_s: float = DEFAULT_BUDGET_S,
    time_fn=time.time,
    sleep_fn=time.sleep,
) -> Dict[str, Any]:
    """
    Ejecuta la llamada de chat con rotación automática entre keys utilizables.
    Garantiza:
    - Prioridad estricta por orden de configuración.
    - Rotación inmediata (< 0.1s) ante cuota agotada (429) o auth (401/403).
    - Presupuesto global de tiempo acotado.
    - Cero filtración de secretos a logs o salida.
    """
    keys = custom_keys if custom_keys is not None else load_keys()
    if not keys:
        raise AllKeysUnavailable("No se encontraron claves de Ollama Cloud configuradas.")

    sm = state_manager or KeyStateManager()
    t0 = time_fn()
    attempts_record = []
    total_attempts = 0

    while (time_fn() - t0) < budget_s:
        available_keys = sm.get_available_keys(keys, now=time_fn())

        if not available_keys:
            # Si todas están en cooldown, verificar si alguna expira pronto dentro del budget
            min_cd = sm.min_cooldown_remaining(keys, now=time_fn())
            elapsed = time_fn() - t0
            rem_budget = budget_s - elapsed
            if min_cd is None or min_cd > rem_budget or min_cd > 3.0:
                break  # Salir para activar fallback a Ollama local
            sleep_fn(min(min_cd, 1.5))
            continue

        used_any_key = False
        for k in available_keys:
            elapsed = time_fn() - t0
            rem_budget = budget_s - elapsed
            if rem_budget <= 1.0:
                break

            used_any_key = True
            kid = key_id(k)
            strikes = sm.get_strikes(k)
            timeout = max(2.0, min(READ_TIMEOUT_S, rem_budget))

            total_attempts += 1
            status, headers, body_text, content = _http_chat_attempt(k, payload, url, timeout)
            outcome = classify_response(status, headers, body_text, current_strikes=strikes)

            sm.record_outcome(k, outcome, now=time_fn())

            if outcome.kind == "ok" and content:
                print(f"[OLLAMA] key={kid} -> OK (intento {total_attempts})", file=sys.stderr)
                return {
                    "content": content,
                    "key_id": kid,
                    "attempts": total_attempts,
                    "source": "ollama",
                }

            # Si es error 200 pero respuesta corrupta
            if outcome.kind == "ok" and not content:
                outcome = Outcome(kind="bad_response", cooldown_s=0.0, rotate=True,
                                  error_msg="Respuesta 200 sin campo message.content", status_code=200)

            # Error no rotable (ej: 400 Bad Request en el prompt)
            if outcome.kind == "bad_request":
                print(f"[OLLAMA] key={kid} -> Error no recuperable ({outcome.error_msg})", file=sys.stderr)
                raise NonRotatableError(outcome.error_msg)

            # Reintento con backoff breve para errores transitorios (5xx / conexión)
            if outcome.retry_same and (budget_s - (time_fn() - t0)) > 3.0:
                backoff = random.uniform(0.5, 1.2)
                sleep_fn(backoff)
                total_attempts += 1
                status, headers, body_text, content = _http_chat_attempt(k, payload, url, timeout)
                outcome = classify_response(status, headers, body_text, current_strikes=strikes)
                sm.record_outcome(k, outcome, now=time_fn())
                if outcome.kind == "ok" and content:
                    print(f"[OLLAMA] key={kid} -> OK tras reintento", file=sys.stderr)
                    return {
                        "content": content,
                        "key_id": kid,
                        "attempts": total_attempts,
                        "source": "ollama",
                    }

            # Notificar rotación en stderr sin revelar la clave
            cd_info = f"; apartada {int(outcome.cooldown_s)}s" if outcome.cooldown_s > 0 else ""
            print(f"[OLLAMA] key={kid} -> {outcome.kind} (HTTP {outcome.status_code or 'ERR'}){cd_info}; rotando clave...", file=sys.stderr)
            attempts_record.append({"key_id": kid, "kind": outcome.kind, "error": outcome.error_msg})

            # ROTACIÓN INMEDIATA: no se duerme ante 429 de cuota o 401; pasa de inmediato a la siguiente key

        if not used_any_key:
            break

    raise AllKeysUnavailable(f"Todas las claves agotaron su cuota o fallaron tras {total_attempts} intentos.")


def local_ollama_chat(
    payload: dict,
    local_url: str = DEFAULT_OLLAMA_LOCAL_URL,
    timeout: float = 8.0
) -> Optional[Dict[str, Any]]:
    """
    Intenta invocar una instancia local de Ollama (ej: localhost:11434).
    Verifica primero si el daemon está vivo vía /api/tags (timeout 1.5s).
    """
    try:
        base_url = local_url.split("/api/")[0]
        tags_url = f"{base_url}/api/tags"
        req_probe = request.Request(tags_url)
        with request.urlopen(req_probe, timeout=1.5) as resp:
            if resp.status != 200:
                return None
    except Exception:
        return None

    # Si responde, enviar solicitud de chat
    try:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = request.Request(local_url, data=data, headers={"Content-Type": "application/json"})
        with request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            content = None
            if isinstance(body, dict):
                if "message" in body and isinstance(body["message"], dict):
                    content = body["message"].get("content")
                elif "response" in body:
                    content = body.get("response")
            if content and content.strip():
                print(f"[OLLAMA] Completado usando daemon local ({local_url})", file=sys.stderr)
                return {
                    "content": content.strip(),
                    "key_id": "local",
                    "attempts": 1,
                    "source": "ollama",
                    "backend": "local",
                }
    except Exception as exc:
        print(f"[OLLAMA] Falló consulta al daemon local: {exc}", file=sys.stderr)
    return None


# ── Comandos CLI para diagnóstico y gestión ────────────────────────────────
def cli_status():
    keys = load_keys()
    kf = resolve_key_file()
    print("=================================================================")
    print(" ESTADO DEL POOL DE API KEYS DE OLLAMA CLOUD")
    print(f" Archivo: {kf}")
    print("=================================================================")
    if not keys:
        print(" [!] No hay claves configuradas en el archivo ni en el entorno.")
        return 0

    sm = KeyStateManager()
    statuses = sm.get_all_status(keys)
    avail_count = sum(1 for s in statuses if s["state"] == "lista")
    print(f" Claves configuradas: {len(keys)}  |  Disponibles ahora: {avail_count}/{len(keys)}\n")
    print(f" {'#':<3} {'ID':<12} {'CLAVE ENMASCARADA':<22} {'ESTADO':<12} {'ENFRIAMIENTO':<14} {'DETALLES'}")
    print(" ----------------------------------------------------------------------------------------")
    for s in statuses:
        cd_str = f"{int(s['cooldown_remaining_s'])}s" if s["cooldown_remaining_s"] > 0 else "-"
        detail = s["last_error"] or "-"
        if len(detail) > 35:
            detail = detail[:32] + "..."
        state_label = s["state"].upper()
        print(f" {s['index']:<3} {s['id']:<12} {s['masked']:<22} {state_label:<12} {cd_str:<14} {detail}")
    print("=================================================================")
    return 0


def cli_reset(target_id: Optional[str] = None):
    sm = KeyStateManager()
    sm.reset(target_id)
    if target_id:
        print(f"[✓] Enfriamiento restablecido para la clave ID: {target_id}")
    else:
        print("[✓] Todos los enfriamientos han sido restablecidos. Todas las claves están listas.")
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "reset":
        tid = sys.argv[2] if len(sys.argv) > 2 else None
        sys.exit(cli_reset(tid))
    sys.exit(cli_status())


if __name__ == "__main__":
    main()
