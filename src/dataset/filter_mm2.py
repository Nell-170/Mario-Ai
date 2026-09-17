"""
filter_mm2.py
-------------
Stream the TheGreatRambler/mm2_level dataset (Hugging Face, ~26.6M levels,
Parquet, 196 shards) WITHOUT downloading it fully, and select a small subset
of high-quality human-made SMB1 levels to use as "Nivel 0" base levels.

Selection criteria:
  * gamestyle == 0              -> Super Mario Bros 1 style (what MAF/MarioGPT use)
  * 15.0 <= clear_rate <= 70.0  -> not trivial, not (near) impossible
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
    ap.add_argument("--min-clear", type=float, default=15.0)
    ap.add_argument("--max-clear", type=float, default=70.0)
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

    print(f"Streaming dataset (seeking {args.want} structurally viable MAF levels)...")
    for rec in ds:
        scanned += 1
        if scanned % 5000 == 0:
            print(f"  scanned={scanned} matched_meta={matched} viable_maf={viable} kept={len(heap)}")
        if scanned > args.max_scan:
            break

        if rec["gamestyle"] != args.gamestyle:
            continue

        cr = rec["clear_rate"]
        if cr is None or cr < args.min_clear or cr > args.max_clear:
            continue

        attempts = rec["attempts"] or 0
        if attempts < args.min_attempts:
            continue

        likes = rec.get("likes") or 0
        if likes < args.min_likes:
            continue

        timer = rec.get("timer") or 0
        if timer < args.min_timer:
            continue

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
        meta = {k: rec.get(k) for k in META_FIELDS}
        entry = (likes, rec["data_id"], meta, rec["level_data"])
        if len(heap) < args.want:
            heapq.heappush(heap, entry)
        elif likes > heap[0][0]:
            heapq.heapreplace(heap, entry)

    print(f"\nDone. scanned={scanned} matched_meta={matched} viable_maf={viable} selected={len(heap)}")

    selected = sorted(heap, key=lambda e: -e[0])  # most liked first

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