#!/usr/bin/env python3
import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "mario_gpt") not in sys.path:
    sys.path.insert(0, str(ROOT / "mario_gpt"))

try:
    from mario_gpt import MarioLM
except Exception as exc:  # pragma: no cover - fallback for local install issues
    raise SystemExit(
        "No se pudo importar MarioGPT. Asegúrate de tener el repo clonado en 'mario_gpt/' y las dependencias instaladas. "
        f"Detalle: {exc}"
    )

try:
    from telemetry_to_mariogpt_prompt import build_compact_prompt
except Exception:  # pragma: no cover
    build_compact_prompt = None


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


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


def parse_args():
    parser = argparse.ArgumentParser(description="Generate a Mario level from a MarioGPT-ready prompt.")
    parser.add_argument("--prompt", help="Prompt literal listo para MarioGPT. Si no se indica, se usa telemetry/latest.json.")
    parser.add_argument("--telemetry", default="telemetry/latest.json", help="Ruta del JSON de telemetría.")
    parser.add_argument("--steps", type=int, default=1400, help="Número de tokens a generar.")
    parser.add_argument("--temperature", type=float, default=2.0, help="Temperatura de generación.")
    parser.add_argument("--output-dir", default="levels/generated", help="Directorio donde guardar el nivel generado.")
    return parser.parse_args()


def main():
    args = parse_args()
    telemetry_path = ROOT / args.telemetry
    prompt_text = resolve_prompt(telemetry_path, args.prompt)

    output_dir = ROOT / args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    print("Prompt para MarioGPT:")
    print(prompt_text)
    print("\nGenerando nivel con MarioGPT...")

    mario_lm = MarioLM()
    generated = mario_lm.sample(
        prompts=[prompt_text],
        num_steps=args.steps,
        temperature=args.temperature,
        use_tqdm=False,
    )

    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    level_path = output_dir / f"generated_{timestamp}.txt"
    generated.save(str(level_path))

    print(f"\nNivel guardado en: {level_path}")
    print("\nPreview:")
    preview = generated.level
    print(preview[:800])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
