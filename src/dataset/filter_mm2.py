"""
filter_mm2.py
-------------
Stream the TheGreatRambler/mm2_level dataset (Hugging Face, ~26.6M levels,
Parquet, 196 shards) WITHOUT downloading it fully, and select a small subset
of high-quality human-made SMB1 levels to use as "Nivel 0" base levels.

Selection criteria:
  * gamestyle == 0              -> Super Mario Bros 1 style (what MAF/MarioGPT use)
  * 30.0 <= clear_rate <= 50.0  -> not trivial, not (near) impossible
  * attempts >= min_attempts    -> clear_rate estadísticamente confiable
  * likes >= min_likes          -> calidad subjetiva validada por jugadores
  * timer >= min_timer          -> proxy de nivel largo (sin decodificar el blob)
  * ranked by `likes` (quality signal)

Only the raw level_data blobs of the selected levels are kept (pickled), so the
converter step does not need to re-stream the dataset.

Outputs:
  * mm2_selected.csv         -> metadata of the selected levels
  * mm2_selected_levels.pkl  -> {data_id: level_data bytes} for the selected set
"""
import os
import sys
import csv
import heapq
import pickle
import argparse
import warnings

warnings.filterwarnings("ignore")

from datasets import load_dataset
from convert_mm2_to_maf import parse_level, convert


HERE = os.path.dirname(os.path.abspath(__file__))

GAMESTYLES = {0: "SMB1", 1: "SMB3", 2: "SMW", 3: "NSMBU", 4: "SM3DW"}

META_FIELDS = [
    "data_id", "name", "gamestyle", "theme", "difficulty",
    "clear_rate", "clears", "attempts", "plays", "likes", "boos",
    "num_comments", "timer", "autoscroll_speed", "uploaded",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--want", type=int, default=50, help="levels to keep")
    ap.add_argument("--max-scan", type=int, default=80000,
                    help="max streamed records to inspect")
    ap.add_argument("--min-clear", type=float, default=30.0)
    ap.add_argument("--max-clear", type=float, default=50.0)
    ap.add_argument("--gamestyle", type=int, default=0)  # 0 = SMB1
    ap.add_argument("--min-attempts", type=int, default=150,
                    help="ignore levels with very few attempts (noisy clear_rate)")
    ap.add_argument("--min-likes", type=int, default=30,
                    help="minimum likes (calidad subjetiva validada por jugadores)")
    ap.add_argument("--min-timer", type=int, default=250,
                    help="proxy de nivel largo: timer mínimo en segundos (sin decodificar blob)")
    args = ap.parse_args()

    ds = load_dataset("TheGreatRambler/mm2_level", streaming=True, split="train")

    # Min-heap of (likes, data_id, record-dict, level_data) keeping the top-N by likes.
    heap = []
    scanned = 0
    matched = 0
    viable = 0
    funnel = dict.fromkeys(
        ["inspected", "gamestyle", "clear_rate", "attempts", "likes", "timer", "maf_viable"], 0)

    print(f"Streaming dataset (buscando {args.want} niveles viables MAF, máx scan: {args.max_scan})...")
    for rec in ds:
        scanned += 1
        if scanned % 250 == 0 or scanned == args.max_scan:
            ratio = min(1.0, len(heap) / args.want) if args.want > 0 else 1.0
            pct = int(ratio * 100)
            filled = int(ratio * 20)
            bar = "█" * filled + "░" * (20 - filled)
            sys.stdout.write(f"\r\033[1;36m[{pct:3d}%]\033[0m \033[1;32m[{bar}]\033[0m Encontrados: {len(heap)}/{args.want} (Escaneados: {scanned:,} | Viables: {viable})\n")
            sys.stdout.flush()
        if scanned >= args.max_scan or (len(heap) >= args.want and scanned >= 4000):
            break

        funnel["inspected"] += 1
        if rec["gamestyle"] != args.gamestyle:
            continue
        funnel["gamestyle"] += 1

        cr = rec["clear_rate"]
        if cr is None or cr < args.min_clear or cr > args.max_clear:
            continue
        funnel["clear_rate"] += 1

        attempts = rec["attempts"] or 0
        if attempts < args.min_attempts:
            continue
        funnel["attempts"] += 1

        likes = rec.get("likes") or 0
        if likes < args.min_likes:
            continue
        funnel["likes"] += 1

        timer = rec.get("timer") or 0
        if timer < args.min_timer:
            continue
        funnel["timer"] += 1

        matched += 1

        # ---- In-stream MAF Structural & Geometric Viability Check ----
        # Verify immediately in memory: spawn footing, goal footing, no giant voids.
        try:
            level = parse_level(rec["level_data"])
            lines, stats = convert(level)
            if lines is None:
                continue
        except Exception:
            continue

        viable += 1
        funnel["maf_viable"] += 1
        meta = {k: rec.get(k) for k in META_FIELDS}
        entry = (likes, rec["data_id"], meta, rec["level_data"])
        if len(heap) < args.want:
            heapq.heappush(heap, entry)
        elif likes > heap[0][0]:
            heapq.heapreplace(heap, entry)

    print(f"\nDone. scanned={scanned} matched_meta={matched} viable_maf={viable} selected={len(heap)}")

    selected = sorted(heap, key=lambda e: -e[0])  # most liked first

    steps = [
        ("inspected", "Niveles inspeccionados", "-"),
        ("gamestyle", "Estilo de juego", f"gamestyle == {args.gamestyle}"),
        ("clear_rate", "Tasa de victoria", f"{args.min_clear} <= clear_rate <= {args.max_clear}"),
        ("attempts", "Intentos minimos", f"attempts >= {args.min_attempts}"),
        ("likes", "Likes minimos", f"likes >= {args.min_likes}"),
        ("timer", "Timer minimo", f"timer >= {args.min_timer}"),
        ("maf_viable", "Viable para MAF (convert)", "convert() != None"),
    ]
    rows = []
    prev = None
    first = funnel["inspected"]
    print("\nEmbudo de filtrado:")
    print(f"{'Paso':<28}{'Criterio':<34}{'Quedan':>9}{'Descartados':>13}{'% del paso previo':>19}{'% del total':>13}")
    for key, label, crit in steps:
        n = funnel[key]
        dropped = 0 if prev is None else prev - n
        pct_prev = 100.0 if not prev else n * 100.0 / prev
        pct_total = 100.0 if not first else n * 100.0 / first
        print(f"{label:<28}{crit:<34}{n:>9,}{dropped:>13,}{pct_prev:>18.1f}%{pct_total:>12.2f}%")
        rows.append([label, crit, n, dropped, f"{pct_prev:.2f}", f"{pct_total:.2f}"])
        prev = n
    print(f"{'Seleccionados (top por likes)':<62}{len(heap):>9,}")
    rows.append(["Seleccionados (top por likes)", f"top {args.want}", len(heap), max(0, funnel["maf_viable"] - len(heap)), "", ""])

    funnel_path = os.path.join(HERE, "mm2_filter_funnel.csv")
    with open(funnel_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["paso", "criterio", "quedan", "descartados", "pct_del_paso_previo", "pct_del_total"])
        w.writerows(rows)
    print(f"Wrote funnel       -> {funnel_path}")

    # Write metadata CSV
    csv_path = os.path.join(HERE, "mm2_selected.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=META_FIELDS + ["gamestyle_name"])
        w.writeheader()
        for likes, data_id, meta, _ in selected:
            row = dict(meta)
            row["gamestyle_name"] = GAMESTYLES.get(meta["gamestyle"], "?")
            w.writerow(row)
    print(f"Wrote metadata -> {csv_path}")

    # Persist raw level_data for the converter step
    blobs = {data_id: level_data for _, data_id, _, level_data in selected}
    pkl_path = os.path.join(HERE, "mm2_selected_levels.pkl")
    with open(pkl_path, "wb") as f:
        pickle.dump(blobs, f)
    print(f"Wrote level blobs  -> {pkl_path}  ({len(blobs)} levels)")


if __name__ == "__main__":
    main()
    os._exit(0)