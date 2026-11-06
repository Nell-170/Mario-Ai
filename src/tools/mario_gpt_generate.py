#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import time
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
        print(f"\n[HPC] Generando en nodo GPU del clúster: {base}/level")
        print(f"[HPC] Prompt enviado: '{prompt}'")

        query = urllib.parse.urlencode({"prompt": prompt})
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

    started = time.time()
    meta = {"input_prompt": prompt_text, "backend": None, "server_url": None, "mariogpt_prompt": None}

    level_content = None

    # 1. Intentar generación remota en el clúster HPC primero
    if not args.no_remote and args.server_url:
        level_content = try_generate_remote(args.server_url, prompt_text)
        if level_content is not None:
            meta["backend"] = "remote"
            meta["server_url"] = args.server_url
            meta["mariogpt_prompt"] = to_mariogpt_keywords(prompt_text)

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
            meta["backend"] = "local_cuda"
            print("Usando GPU local (CUDA)")
        else:
            meta["backend"] = "local_cpu"
            print("GPU local no disponible, usando CPU en su lugar")

        # MarioGPT solo entiende sus 4 atributos (pipes, enemies, blocks, elevation):
        # se usa la misma traducción que en la ruta remota.
        kw_prompt = to_mariogpt_keywords(prompt_text)
        meta["mariogpt_prompt"] = kw_prompt
        print(f"Prompt acondicionado: '{kw_prompt}'")
        generated = mario_lm.sample(
            prompts=[kw_prompt],
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

    # Metadatos para la base de datos del experimento (los lee ExperimentRunner).
    meta["seconds"] = round(time.time() - started, 2)
    meta["level_file"] = level_path.name
    with (output_dir / "generation_meta.json").open("w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    print("\nPreview:")
    print(level_content[:600])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
