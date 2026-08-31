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
    "Eres un traductor especializado en telemetría de Mario. Tu tarea es convertir "
    "un JSON de telemetría de un jugador humano en una sola cadena descriptiva, "
    "breve y útil para MarioGPT. Regla principal: no inventes información. "
    "Devuelve solo una cadena de texto que describa el diseño del nivel. "
    "Debe ser técnica, compacta y orientada a generación procedimental. "
    "Si la telemetría sugiere fracaso o frustración, reduce huecos imposibles, "
    "trap sections y enemigos agresivos. Si la telemetría sugiere avance parcial, "
    "mantén claridad de ruta y dificultad moderada.\n"
    "Formato de salida: una sola línea, sin markdown, sin explicaciones, sin texto extra."
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

    return (
        "Generate a Mario level in classic SMB1 style with "
        f"difficulty={difficulty}, path_preference={path_pref}, completion={completion_bucket}, "
        f"speed={speed_bucket}, jump_complexity={jump_complexity}, enemy_density={enemy_density}, "
        f"hazard_density={hazard_density}, reward={reward}, progression={progression}. "
        f"Use a {'high' if path_pref == 'high' else 'low' if path_pref == 'low' else 'mixed'} route structure, "
        f"keep the level readable and fair, and {risk_note}. "
        "Avoid impossible gaps, unfair traps, and unclear progression. Keep the layout playable and compact."
    )


def build_user_message(data):
    return (
        "JSON de telemetría del jugador:\n"
        + json.dumps(data, ensure_ascii=False, separators=(",", ":"))
        + "\n\nProduce una sola cadena descriptiva de diseño de nivel para MarioGPT."
    )


def resolve_api_key():
    env_key = os.environ.get("OLLAMA_API_KEY")
    if env_key and env_key.strip():
        return env_key.strip()

    candidate_paths = [
        Path("mario_ai_framework/Ollama-Key.txt"),
        Path("../mario_ai_framework/Ollama-Key.txt"),
        Path("./mario_ai_framework/Ollama-Key.txt"),
    ]
    for path in candidate_paths:
        try:
            if path.exists():
                value = path.read_text(encoding="utf-8").strip()
                if value:
                    return value
        except OSError:
            pass
    return None


def call_ollama(model, telemetry_json, url):
    api_key = resolve_api_key()
    if not api_key:
        raise RuntimeError("No se encontró la API key de Ollama. Define OLLAMA_API_KEY o crea mario_ai_framework/Ollama-Key.txt")

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": DEFAULT_SYSTEM_PROMPT},
            {"role": "user", "content": build_user_message(telemetry_json)},
        ],
        "stream": False,
        "options": {"temperature": 0.2, "top_p": 0.9},
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }
    req = request.Request(url, data=data, headers=headers)
    try:
        with request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            if isinstance(body, dict):
                if "message" in body and isinstance(body["message"], dict):
                    text = body["message"].get("content")
                    if text:
                        return text.strip()
                text = body.get("response")
                if text:
                    return text.strip()
            raise ValueError(f"Respuesta inesperada de Ollama: {body!r}")
    except error.HTTPError as exc:
        raise RuntimeError(f"Ollama respondió con error HTTP {exc.code}: {exc.read().decode('utf-8', 'ignore')}")
    except Exception as exc:
        raise RuntimeError(f"No se pudo consultar Ollama: {exc}")


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
            result = call_ollama(args.ollama_model, telemetry, args.ollama_url)
            print("String para MarioGPT (Ollama):", file=sys.stderr)
            if args.json_output:
                print(json.dumps({"prompt": result}, ensure_ascii=False))
            else:
                print(result)
            return 0
        except Exception as exc:
            print(f"Advertencia: no se pudo usar Ollama ({exc}). Se usará la versión local determinista.", file=sys.stderr)

    print("Aviso: no se encontró OLLAMA_API_KEY ni mario_ai_framework/Ollama-Key.txt; usando prompt local.", file=sys.stderr)
    if args.json_output:
        print(json.dumps({"prompt": fallback_prompt}, ensure_ascii=False))
    else:
        print("String para MarioGPT (fallback local):")
        print(fallback_prompt)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
