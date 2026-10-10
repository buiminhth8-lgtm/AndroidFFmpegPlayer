#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Extract Arial glyph bitmaps for the Slice 8 target-id recognizer.

Renders each character large (48 px, via ffmpeg drawtext), crops to the ink
bounding box, then downsamples to a fixed 12-row grid using area coverage.
Output is printed as Java-ready `String[]` literals ('#' = ink) so the
recognizer can embed real-font templates instead of 7-segment glyphs.

Run:
    /d/develop/python312/python.exe tools/roi_blue_mask/extract_glyph_templates.py

Requires the PNGs produced by the companion ffmpeg loop (g*.png) in /tmp/fontgen2.
"""
import os
import sys

from PIL import Image

GRID_ROWS = 12          # template height in grid rows
MAX_COLS = 12           # template width in grid columns (widest glyph)
COVERAGE_THRESHOLD = 0.45

SRC = sys.argv[1] if len(sys.argv) > 1 else "/tmp/fontgen2"

# name -> file
GLYPHS = [
    ("'0'", "g0.png"), ("'1'", "g1.png"), ("'2'", "g2.png"), ("'3'", "g3.png"),
    ("'4'", "g4.png"), ("'5'", "g5.png"), ("'6'", "g6.png"), ("'7'", "g7.png"),
    ("'8'", "g8.png"), ("'9'", "g9.png"),
    ("'i'", "g_i.png"), ("'d'", "g_d.png"), ("':'", "g_c.png"),
]


def ink_box(img):
    w, h = img.size
    px = img.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if px[x, y] > 128:
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y
    if maxx < 0:
        return None
    return (minx, miny, maxx, maxy)


def coverage(px, x0, y0, x1, y1):
    on = 0
    total = 0
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            total += 1
            if px[x, y] > 128:
                on += 1
    return on / float(total) if total else 0.0


def downsample(img, box, rows, cols):
    minx, miny, maxx, maxy = box
    px = img.load()
    bw = maxx - minx + 1
    bh = maxy - miny + 1
    out = []
    for r in range(rows):
        line = []
        y0 = miny + int(round(r * bh / rows))
        y1 = miny + max(y0 - miny + 1, int(round((r + 1) * bh / rows))) - 1
        y1 = min(y1, maxy)
        for c in range(cols):
            x0 = minx + int(round(c * bw / cols))
            x1 = minx + max(x0 - minx + 1, int(round((c + 1) * bw / cols))) - 1
            x1 = min(x1, maxx)
            cov = coverage(px, x0, y0, x1, y1)
            line.append('#' if cov >= COVERAGE_THRESHOLD else '.')
        out.append(''.join(line))
    return out, bw, bh


def main():
    print("=== glyph templates, grid %dx%d (w x h), coverage>=%.2f ==="
          % (MAX_COLS, GRID_ROWS, COVERAGE_THRESHOLD))
    for label, fname in GLYPHS:
        path = os.path.join(SRC, fname)
        if not os.path.isfile(path):
            print("MISSING", path)
            continue
        img = Image.open(path).convert("L")
        box = ink_box(img)
        if box is None:
            print("EMPTY", label)
            continue
        bw = box[2] - box[0] + 1
        bh = box[3] - box[1] + 1
        cols = max(1, min(MAX_COLS, int(round(MAX_COLS * bw / bh))))
        rows, _, _ = downsample(img, box, GRID_ROWS, cols)
        print("\n// %s  ink=%dx%d  grid=%dx%d" % (label, bw, bh, cols, GRID_ROWS))
        for line in rows:
            print('            "%s",' % line)


if __name__ == "__main__":
    main()
