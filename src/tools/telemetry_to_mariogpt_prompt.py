#!/usr/bin/env python3
import argparse
import json
import os
import sys
from pathlib import Path
from urllib import request, error

DEFAULT_OLLAMA_CLOUD_URL = "https://ollama.com/api/chat"
DEFAULT_OLLAMA_CLOUD_MODEL = "gpt-oss:120b"

DEFAULT_SYSTEM_PROMPT = (
    "Eres un adaptador de telemetría para MarioGPT. Tu tarea es convertir un JSON "
    "de telemetría de un jugador humano en una sola línea de prompt lista para "
    "MarioGPT. No inventes información. Usa solo los datos del JSON.\n"
    "Formato de salida exacto: una sola línea en este esquema:\n"
    "difficulty=...; route=...; jump=...; enemies=...; hazards=...; rewards=...; "
    "progression=...; avoid=...; must_have=...\n"
    "Reglas:\n"
    "- Usa SMB1 / Mario classic style.\n"
    "- Mantén la salida compacta, técnica y utilizable para generación procedural.\n"
    "- No agregues markdown, explicaciones ni texto extra.\n"
    "- Si la telemetría sugiere fracaso o frustración, reduce huecos imposibles, traps y enemigos agresivos.\n"
    "- Si la telemetría sugiere avance parcial, prioriza rutas claras y saltos cortos.\n"
    "- Si la telemetría sugiere buen progreso, mantiene una progresión segura con pequeñas curvas de dificultad."
)


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def clamp(value, minimum, maximum):
    return max(minimum, min(value, maximum))


def classify_completion(completion):
    if completion >= 0.8:
        return "high"
    if completion >= 0.35:
        return "medium"
    return "low"


def classify_speed(speed):
    if speed >= 45:
        return "fast"
    if speed >= 20:
        return "moderate"
    return "slow"


def classify_difficulty(completion, speed, path_preference):
    if completion >= 0.8 and speed >= 35:
        return "medium"
    if completion < 0.25:
        return "easy"
    if speed >= 50 and path_preference == "high":
        return "medium"
    return "easy"


def classify_enemy_density(kills):
    if kills >= 8:
        return "high"
    if kills >= 3:
        return "medium"
    return "low"


def classify_hazard_density(hurts, death_positions):
    count = int(hurts) + max(0, len(death_positions or []))
    if count >= 3:
        return "high"
    if count >= 1:
        return "medium"
    return "low"


def classify_reward(coins):
    if coins >= 8:
        return "rich"
    if coins >= 3:
        return "moderate"
    return "sparse"


def classify_jump_complexity(jumps):
    if jumps >= 15:
        return "high"
    if jumps >= 6:
        return "medium"
    return "low"


def classify_progression(status, completion):
    if status == "WIN":
        return "steady"
    if completion >= 0.5:
        return "moderate"
    if completion >= 0.2:
        return "careful"
    return "safe"


def build_compact_prompt(data):
    completion = float(data.get("completion", 0.0))
    status = str(data.get("status", "UNKNOWN")).upper()
    path_pref = str(data.get("path_preference", "mixed")).lower()
    speed = float(data.get("average_speed_pixels_per_second", 0.0))
    jumps = int(data.get("jumps", 0))
    kills = int(data.get("kills", 0))
    hurts = int(data.get("hurts", 0))
    coins = int(data.get("coins", 0))
    death_positions = data.get("death_positions") or []

    difficulty = classify_difficulty(completion, speed, path_pref)
    enemy_density = classify_enemy_density(kills)
    hazard_density = classify_hazard_density(hurts, death_positions)
    reward = classify_reward(coins)
    jump_complexity = classify_jump_complexity(jumps)
    progression = classify_progression(status, completion)
    speed_bucket = classify_speed(speed)
    completion_bucket = classify_completion(completion)

    if status == "WIN":
        risk_note = "keep a clear progression and modest challenge"
    elif completion_bucket == "low" or status in {"LOSE", "TIME_OUT"}:
        risk_note = "avoid impossible gaps, trap sections, and punishing enemy placement"
    else:
        risk_note = "maintain readable routes and a forgiving rhythm with moderate risk"

    route_desc = "high" if path_pref == "high" else "low" if path_pref == "low" else "mixed"
    avoid = "impossible gaps, trap sections, unfair enemy placement"
    must_have = "clear progression, readable platforms, short jump arcs, safe reward area"

    if status == "WIN":
        avoid = "unfair traps, chaotic enemy flow"
        must_have = "clear progression, readable rhythm, small challenge spikes"

    return (
        f"difficulty={difficulty}; route={route_desc}; jump={jump_complexity}; enemies={enemy_density}; "
        f"hazards={hazard_density}; rewards={reward}; progression={progression}; "
        f"speed={speed_bucket}; completion={completion_bucket}; avoid={avoid}; must_have={must_have}"
    )


def build_user_message(data):
    return (
        "JSON de telemetría del jugador:\n"
        + json.dumps(data, ensure_ascii=False, separators=(",", ":"))
        + "\n\nGenera exactamente una línea en el formato requerido para MarioGPT."
    )


# Intentar importar gestor de rotación de claves
try:
    import ollama_keys
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import ollama_keys


def resolve_api_key():
    """Mantenido para compatibilidad hacia atrás: devuelve la primera clave válida."""
    keys = ollama_keys.load_keys()
    return keys[0] if keys else None


def call_ollama(model, telemetry_json, url):
    """
    Invoca Ollama con rotación de claves, cooldowns y fallback a daemon local.
    """
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": DEFAULT_SYSTEM_PROMPT},
            {"role": "user", "content": build_user_message(telemetry_json)},
        ],
        "stream": False,
        "options": {"temperature": 0.2, "top_p": 0.9},
    }

    try:
        # 1. Intentar Ollama Cloud con rotación de claves
        return ollama_keys.ollama_chat(payload, url=url)
    except ollama_keys.AllKeysUnavailable as exc:
        print(f"[OLLAMA] Claves Cloud no disponibles ({exc}). Probando daemon local...", file=sys.stderr)
        # 2. Intentar Ollama daemon local (localhost:11434)
        local_res = ollama_keys.local_ollama_chat(payload)
        if local_res:
            return local_res
        raise RuntimeError(f"Ollama Cloud y Local no disponibles ({exc})")
    except Exception as exc:
        # 2b. Intentar Ollama daemon local ante cualquier error de red cloud
        local_res = ollama_keys.local_ollama_chat(payload)
        if local_res:
            return local_res
        raise RuntimeError(f"Fallo en consulta a Ollama: {exc}")


def parse_args():
    parser = argparse.ArgumentParser(description="Convert telemetry JSON into a short MarioGPT prompt.")
    parser.add_argument("--telemetry", default="telemetry/latest.json", help="Ruta del JSON de telemetría.")
    parser.add_argument("--ollama-model", default=DEFAULT_OLLAMA_CLOUD_MODEL, help="Nombre del modelo de Ollama Cloud/local a usar.")
    parser.add_argument("--ollama-url", default=DEFAULT_OLLAMA_CLOUD_URL, help="URL del endpoint de Ollama.")
    parser.add_argument("--json-output", action="store_true", help="Imprime la salida como JSON con la cadena final.")
    parser.add_argument("--allow-cloud", action="store_true", help="Intenta usar Ollama Cloud si se encontró la API key.")
    return parser.parse_args()


def main():
    args = parse_args()
    telemetry_path = Path(args.telemetry)
    if not telemetry_path.exists():
        print(f"No se encontró el archivo de telemetría: {telemetry_path}", file=sys.stderr)
        return 1

    telemetry = load_json(telemetry_path)
    fallback_prompt = build_compact_prompt(telemetry)

    if args.allow_cloud:
        try:
            res = call_ollama(args.ollama_model, telemetry, args.ollama_url)
            prompt_text = res.get("content") if isinstance(res, dict) else str(res).strip()
            source_val = res.get("source", "ollama") if isinstance(res, dict) else "ollama"
            kid = res.get("key_id") if isinstance(res, dict) else None
            attempts_cnt = res.get("attempts", 1) if isinstance(res, dict) else 1

            print(f"\nString para MarioGPT (Ollama - {kid or 'cloud'}):", file=sys.stderr)
            if args.json_output:
                out = {
                    "prompt": prompt_text,
                    "source": source_val,
                    "model": args.ollama_model,
                    "key_id": kid,
                    "attempts": attempts_cnt,
                }
                print(json.dumps(out, ensure_ascii=False))
            else:
                print(prompt_text)
            return 0
        except Exception as exc:
            print(f"Advertencia: no se pudo usar Ollama ({exc}). Se usará la versión local determinista.", file=sys.stderr)

    print("Aviso: usando prompt determinista heurístico como respaldo.", file=sys.stderr)
    if args.json_output:
        print(json.dumps({"prompt": fallback_prompt, "source": "heuristic", "model": None, "fallback": "heuristic"}, ensure_ascii=False))
    else:
        print("\nString para MarioGPT (fallback local):")
        print(fallback_prompt)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
