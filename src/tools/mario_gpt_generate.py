#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
for candidate in [ROOT / "mario_gpt", ROOT.parent / "mario_gpt"]:
    if candidate.exists() and str(candidate) not in sys.path:
        sys.path.insert(0, str(candidate))

try:
    from telemetry_to_mariogpt_prompt import build_compact_prompt
except Exception:  # pragma: no cover
    build_compact_prompt = None


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def to_mariogpt_keywords(prompt: str) -> str:
    """Adapts telemetry or structured prompts into MarioGPT's native keyword conditioning."""
    if not prompt or not prompt.strip():
        return "many pipes, some enemies, some blocks, high elevation"

    lower = prompt.lower()
    if any(k in lower for k in ["pipes", "enemies", "elevation"]) and ";" not in prompt:
        return prompt.strip()

    kv = {}
    for part in prompt.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            kv[k.strip().lower()] = v.strip().lower()
        elif ":" in part:
            k, v = part.split(":", 1)
            kv[k.strip().lower()] = v.strip().lower()

    # Route / Elevation
    route = kv.get("route", "")
    elevation = "high elevation" if "high" in route else "low elevation" if "low" in route else "high elevation"

    # Enemies
    enemies_val = kv.get("enemies", "")
    if any(x in enemies_val for x in ["high", "many", "dense"]):
        enemies = "many enemies"
    elif any(x in enemies_val for x in ["low", "none", "sparse"]):
        enemies = "little enemies"
    else:
        enemies = "some enemies"

    # Pipes / Hazards
    hazards_val = kv.get("hazards", "")
    if any(x in hazards_val for x in ["high", "many"]):
        pipes = "many pipes"
    elif any(x in hazards_val for x in ["low", "none"]):
        pipes = "little pipes"
    else:
        pipes = "some pipes"

    # Blocks / Rewards
    rewards_val = kv.get("rewards", "")
    if any(x in rewards_val for x in ["rich", "high", "many"]):
        blocks = "many blocks"
    else:
        blocks = "some blocks"

    return f"{pipes}, {enemies}, {blocks}, {elevation}"


def try_generate_remote(server_url: str, prompt: str, timeout: float = 35.0) -> str | None:
    """Attempts to generate a level from the remote HPC MarioGPT REST server."""
    base = server_url.rstrip("/")

    # 1. Health check
    try:
        req_health = urllib.request.Request(f"{base}/health", headers={"User-Agent": "MarioAI-Experiment/1.0"})
        with urllib.request.urlopen(req_health, timeout=2.5) as resp:
            if resp.status != 200:
                print(f"[HPC] Servidor en {base} respondió con código {resp.status} en /health.")
                return None
    except Exception as e:
        print(f"[HPC] Servidor en {base} no disponible ({e}). Recurriendo a generación local.")
        return None

    # 2. Level generation request
    try:
        kw_prompt = to_mariogpt_keywords(prompt)
        print(f"\n[HPC] Generando en nodo GPU del clúster: {base}/level")
        print(f"[HPC] Prompt acondicionado: '{kw_prompt}'")

        query = urllib.parse.urlencode({"prompt": kw_prompt})
        req_level = urllib.request.Request(f"{base}/level?{query}", headers={"User-Agent": "MarioAI-Experiment/1.0"})
        with urllib.request.urlopen(req_level, timeout=timeout) as resp:
            if resp.status == 200:
                body = resp.read().decode("utf-8", errors="replace").strip()
                lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
                if len(lines) >= 14:
                    print(f"[HPC] ¡Nivel recibido exitosamente desde el clúster! ({len(lines)} líneas, {len(body)} caracteres)")
                    return body
                else:
                    print(f"[HPC] Advertencia: La respuesta del servidor tiene {len(lines)} líneas (se esperaban >= 14).")
    except Exception as e:
        print(f"[HPC] Error durante la llamada al clúster: {e}")

    return None


def resolve_prompt(telemetry_path: Path, explicit_prompt: str | None):
    if explicit_prompt:
        return explicit_prompt

    if telemetry_path.exists():
        data = load_json(telemetry_path)
        if build_compact_prompt is not None:
            return build_compact_prompt(data)

    return (
        "difficulty=medium; route=high; jump=medium; enemies=low; hazards=medium; "
        "rewards=moderate; progression=safe; avoid=impossible gaps, trap sections; "
        "must_have=clear progression, readable platforms, short jump arcs"
    )


def next_level_number(output_dir: Path) -> int:
    pattern = re.compile(r"^Nivel generado (\d+)\.txt$")
    numbers = []
    for path in output_dir.iterdir():
        match = pattern.match(path.name)
        if match:
            numbers.append(int(match.group(1)))
    return max(numbers, default=0) + 1


def parse_args():
    parser = argparse.ArgumentParser(description="Generate a Mario level from a MarioGPT-ready prompt.")
    parser.add_argument("--prompt", help="Prompt literal listo para MarioGPT. Si no se indica, se usa telemetry/latest.json.")
    parser.add_argument("--telemetry", default="telemetry/latest.json", help="Ruta del JSON de telemetría.")
    parser.add_argument("--steps", type=int, default=1400, help="Número de tokens a generar.")
    parser.add_argument("--temperature", type=float, default=2.0, help="Temperatura de generación.")
    parser.add_argument("--output-dir", default="levels/generated", help="Directorio donde guardar el nivel generado.")
    parser.add_argument("--server-url", default=os.environ.get("MARIO_SERVER_URL", "http://localhost:8888"),
                        help="URL del servidor REST MarioGPT en clúster (default: http://localhost:8888).")
    parser.add_argument("--no-remote", action="store_true", help="Desactiva la generación remota y fuerza local.")
    return parser.parse_args()


def main():
    args = parse_args()
    raw_telemetry = Path(args.telemetry)
    telemetry_path = raw_telemetry if raw_telemetry.is_absolute() else ROOT / raw_telemetry
    prompt_text = resolve_prompt(telemetry_path, args.prompt)

    raw_output = Path(args.output_dir)
    output_dir = raw_output if raw_output.is_absolute() else ROOT / raw_output
    output_dir.mkdir(parents=True, exist_ok=True)

    print("Prompt base:")
    print(prompt_text)

    level_content = None

    # 1. Intentar generación remota en el clúster HPC primero
    if not args.no_remote and args.server_url:
        level_content = try_generate_remote(args.server_url, prompt_text)

    # 2. Si falló o no está activo, recurrir a generación local
    if level_content is None:
        print("\nGenerando nivel con MarioGPT local (PyTorch)...")
        try:
            import torch
            from mario_gpt import MarioLM
        except Exception as exc:
            raise SystemExit(
                "No se pudo conectar al servidor REST del clúster ni importar MarioGPT local. "
                "Asegúrate de tener el túnel SSH activo a http://localhost:8888 o tener mario_gpt instalado. "
                f"Detalle: {exc}"
            )

        mario_lm = MarioLM()
        if torch.cuda.is_available():
            mario_lm = mario_lm.to(torch.device("cuda"))
            print("Usando GPU local (CUDA)")
        else:
            print("GPU local no disponible, usando CPU en su lugar")

        generated = mario_lm.sample(
            prompts=[prompt_text],
            num_steps=args.steps,
            temperature=args.temperature,
            use_tqdm=True,
        )
        if isinstance(generated.level, list):
            level_content = "\n".join(generated.level)
        else:
            level_content = str(generated.level)

    level_number = next_level_number(output_dir)
    level_path = output_dir / f"Nivel generado {level_number}.txt"
    with level_path.open("w", encoding="utf-8") as f:
        f.write(level_content.strip() + "\n")

    print(f"\nNivel guardado en: {level_path}")
    print("\nPreview:")
    print(level_content[:600])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
