"""
convert_mm2_to_maf.py
---------------------
Convert Mario Maker 2 level blobs (parsed with the provided Kaitai struct) into
the Mario AI Framework (MAF) text-grid format used by MarioGPT (16 rows x N cols).

Coordinate systems (verified empirically against the dataset):
  * ground[] tiles : x, y are DIRECT tile coordinates (u1, 0..~240 / 0..~26).
  * objects[]      : x, y are in units of 160 per tile  ->  tile = coord // 160.
  * MM2 y-origin is the BOTTOM of the level (y = 0 is the floor).
  * MAF grids are top-origin: row 0 = top, row (H-1) = bottom.
        maf_row = (H - 1) - mm2_tile_y            (tiles with y >= H are dropped)

We fix H = 16 rows to stay compatible with the whole pipeline (MAF's classic
SMB1 levels AND MarioGPT, which both operate on 16-tall grids). Tall structures
above row 15 are cropped; the A* validation step later discards any level that
this makes unbeatable.

MAF tile characters used (see engine/core/MarioLevel.java):
  -  air            X  solid floor/ground     #  solid (platform)
  S  breakable brick                          ?  question block (power-up)
  o  coin           g  goomba                 k  green koopa
  y  spiny          <>  pipe top L/R          [] pipe body L/R
  B  bullet blaster head    b  bullet blaster body
"""
import os
import sys
import pickle
import zlib
import argparse
from io import BytesIO

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kaitaistruct import KaitaiStream
from level import Level

HERE = os.path.dirname(os.path.abspath(__file__))
HEIGHT = 16
UNIT = 160  # object-coordinate units per tile

AIR = "-"

# ---- object-id (obj_id enum) -> MAF character -------------------------------
# Solid, rectangle-filled (respect width x height)
SOLID_FILL = {
    6:  "X",   # hard_block
    7:  "X",   # ground (as object)
    63: "X",   # ice_block
    75: "X",   # stone
    4:  "S",   # block (breakable brick)
    16: "#",   # semisolid_platform
    14: "#",   # mushroom_platform
    71: "#",   # half_collision_platform
    17: "#",   # bridge
    21: "#",   # donut_block
    29: "S",   # hidden_block -> brick (visible so A* can use / avoid)
}
# Single-tile placements (placed at their bottom-left tile only)
SINGLE = {
    5:  "?",   # question_block
    8:  "o",   # coin
    70: "o",   # big_coin
    92: "o",   # red_coin
    0:  "g",   # goomba
    1:  "k",   # koopa
    28: "k",   # buzzy_beetle
    46: "g",   # dry_bones
    25: "y",   # spiny
    40: "y",   # spike_top
}
PIPE_ID = 9
BLASTER_ID = 13
ENEMY_CHARS = set("gky")


def parse_level(blob):
    return Level(KaitaiStream(BytesIO(zlib.decompress(blob))))


def convert(level):
    """Return (grid_lines, stats) for the overworld of a parsed level."""
    ow = level.overworld

    # ---- determine width from ground + objects --------------------------
    max_tx = 0
    for i in range(ow.ground_count):
        max_tx = max(max_tx, ow.ground[i].x)
    obj_tiles = []
    for i in range(ow.object_count):
        o = ow.objects[i]
        tx, ty = o.x // UNIT, o.y // UNIT
        w = max(1, o.width)
        h = max(1, o.height)
        obj_tiles.append((tx, ty, w, h, int(o.id.value if hasattr(o.id, "value") else o.id)))
        max_tx = max(max_tx, tx + w - 1)

    width = max_tx + 1
    if width < 16:
        width = 16
    # cap absurd widths (corrupt / vertical levels) to keep files sane
    width = min(width, 400)

    grid = [[AIR for _ in range(width)] for _ in range(HEIGHT)]

    def put(tx, ty, ch, only_air=False):
        if tx < 0 or tx >= width:
            return
        row = (HEIGHT - 1) - ty
        if row < 0 or row >= HEIGHT:
            return
        if only_air and grid[row][tx] != AIR:
            return
        grid[row][tx] = ch

    stats = {"ground": 0, "solid": 0, "single": 0, "pipe": 0, "blaster": 0, "skipped": 0}

    # ---- 1) terrain / ground tiles (all solid) --------------------------
    for i in range(ow.ground_count):
        g = ow.ground[i]
        put(g.x, g.y, "X")
        stats["ground"] += 1

    # ---- 2) objects -----------------------------------------------------
    for tx, ty, w, h, oid in obj_tiles:
        if oid in SOLID_FILL:
            ch = SOLID_FILL[oid]
            for dx in range(w):
                for dy in range(h):
                    put(tx + dx, ty + dy, ch)
            stats["solid"] += 1
        elif oid == PIPE_ID:
            # pipe: 2 tiles wide, h tiles tall, grows upward from (tx,ty)
            top = ty + h - 1
            for dy in range(h):
                yy = ty + dy
                left, right = ("<", ">") if yy == top else ("[", "]")
                put(tx, yy, left)
                put(tx + 1, yy, right)
            stats["pipe"] += 1
        elif oid == BLASTER_ID:
            top = ty + h - 1
            for dy in range(h):
                put(tx, ty + dy, "B" if (ty + dy) == top else "b")
            stats["blaster"] += 1
        elif oid in SINGLE:
            ch = SINGLE[oid]
            # enemies/coins only on empty space so they don't overwrite terrain
            put(tx, ty, ch, only_air=(ch in ENEMY_CHARS or ch == "o"))
            stats["single"] += 1
        else:
            stats["skipped"] += 1

    # ---- 3) crop to the solid-content bounding box ----------------------
    # MM2 stores a left/right boundary margin, so the playfield does not start
    # at column 0. MAF spawns Mario at column 0 (and the exit at the last col)
    # on the first floor found; if those columns are empty it breaks. Crop the
    # grid horizontally to the first..last column that contains a structural
    # solid so both ends have ground.
    structural = set("XS#?<>[]Bb")
    cols_with_solid = [x for x in range(width)
                       if any(grid[r][x] in structural for r in range(HEIGHT))]
    if cols_with_solid:
        c0, c1 = cols_with_solid[0], cols_with_solid[-1]
        grid = [row[c0:c1 + 1] for row in grid]
    stats["width"] = len(grid[0])

    # ---- 4) MAF Structural Validation Checks ----------------------------
    # A) Reject vertical or short 1-screen levels (MAF requires horizontal levels >= 60 cols)
    if stats["width"] < 60:
        return None, {"skipped": "width_too_short", "width": stats["width"]}

    # B) Reject levels without a solid ground floor 'X' in the bottom 2 rows (rows 14 & 15)
    bottom_ground_count = grid[14].count("X") + grid[15].count("X")
    if bottom_ground_count < 10:
        return None, {"skipped": "no_solid_ground", "ground_count": bottom_ground_count}

    lines = ["".join(r) for r in grid]
    return lines, stats



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkl", default=os.path.join(HERE, "mm2_selected_levels.pkl"))
    ap.add_argument("--out", default=os.path.join(HERE, "..", "levels", "converted"))
    args = ap.parse_args()

    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    with open(args.pkl, "rb") as f:
        blobs = pickle.load(f)

    print(f"Converting {len(blobs)} levels -> {out_dir}")
    ok = 0
    for data_id, blob in blobs.items():
        try:
            level = parse_level(blob)
            lines, stats = convert(level)
            if lines is None:
                print(f"  mm2_{data_id}: REJECTED {stats}")
                continue
            path = os.path.join(out_dir, f"mm2_{data_id}.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            ok += 1
            print(f"  mm2_{data_id}: {len(lines[0])} cols  {stats}")
        except Exception as e:
            print(f"  mm2_{data_id}: FAILED {type(e).__name__}: {e}")

    print(f"\nConverted {ok}/{len(blobs)} levels.")


if __name__ == "__main__":
    main()
