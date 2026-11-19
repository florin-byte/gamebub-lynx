#!/usr/bin/env python3
"""GB-173: build the outline font of the in-game menu from Ark Pixel 12px Prop (OFL-1.1, TakWolf).

Every glyph becomes its pixels grown by one pixel in the eight directions: drawn in black under the
same text in the normal font, it gives the same 1 px outline as eight black copies shifted by one
pixel, with one extra text instead of eight (the software renderer keeps every glyph of a frame in
one list, and eight copies ran the console out of memory over GBA games).

Usage: make_outline_font.py <ark-pixel-12px-proportional.ttf> <output.ttf>   (needs fontTools)
"""
import sys

from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

FAMILY = "Retro Bub Menu Outline 12px"  # renamed derivative, as the OFL asks

src, dst = sys.argv[1], sys.argv[2]
font = TTFont(src)
upem = font["head"].unitsPerEm
px = upem // 12  # 12 px font: one pixel in font units
glyf = font["glyf"]
gs = font.getGlyphSet()


def polygons(name):
    pen = DecomposingRecordingPen(gs)
    gs[name].draw(pen)
    polys, cur = [], []
    for op, args in pen.value:
        if op == "moveTo":
            cur = [args[0]]
        elif op == "lineTo":
            cur.append(args[0])
        elif op in ("qCurveTo", "curveTo"):
            raise SystemExit(f"glyph {name}: curves are not expected in a pixel font")
        elif op in ("closePath", "endPath"):
            if cur:
                polys.append(cur)
            cur = []
    return polys


def inside(polys, x, y):
    """Non-zero winding of point (x, y) over all contours."""
    wn = 0
    for poly in polys:
        n = len(poly)
        for i in range(n):
            (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
            if y0 <= y < y1 and (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) > 0:
                wn += 1
            elif y1 <= y < y0 and (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) < 0:
                wn -= 1
    return wn != 0


def pixels(name):
    polys = polygons(name)
    if not polys:
        return set()
    xs = [p[0] for poly in polys for p in poly]
    ys = [p[1] for poly in polys for p in poly]
    for v in xs + ys:
        if v % px:
            raise SystemExit(f"glyph {name}: point {v} is not on the {px}-unit pixel grid")
    out = set()
    for gy in range(min(ys) // px, max(ys) // px):
        for gx in range(min(xs) // px, max(xs) // px):
            if inside(polys, gx * px + px / 2, gy * px + px / 2):
                out.add((gx, gy))
    return out


changed = 0
for name in font.getGlyphOrder():
    pix = pixels(name)
    if not pix:
        continue
    # Grown, then moved one pixel right: the renderer draws nothing left of where a text starts, so
    # the outline text is placed one pixel left of the normal one (see components/outline.slint).
    grown = {(x + dx + 1, y + dy) for (x, y) in pix for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
    pen = TTGlyphPen(None)
    # One rectangle per horizontal run of each pixel row (contours do not overlap).
    for y in sorted({y for _, y in grown}):
        row = sorted(x for x, yy in grown if yy == y)
        start = prev = row[0]
        for x in row[1:] + [None]:
            if x is not None and x == prev + 1:
                prev = x
                continue
            x0, x1, y0, y1 = start * px, (prev + 1) * px, y * px, (y + 1) * px
            pen.moveTo((x0, y0))
            pen.lineTo((x0, y1))
            pen.lineTo((x1, y1))
            pen.lineTo((x1, y0))
            pen.closePath()
            if x is not None:
                start = prev = x
    glyph = pen.glyph()
    glyph.recalcBounds(glyf)
    glyf[name] = glyph
    adv, _ = font["hmtx"][name]
    font["hmtx"][name] = (adv, glyph.xMin)
    changed += 1

# The line metrics stay those of the normal font, so both texts are laid out on the same baseline
# (the grown glyphs reach one pixel past them, which the renderer draws anyway).

name_table = font["name"]
for rec in list(name_table.names):
    if rec.nameID in (1, 3, 4, 6, 16, 17):
        name_table.removeNames(nameID=rec.nameID)
name_table.setName(FAMILY, 1, 3, 1, 0x409)
name_table.setName("Regular", 2, 3, 1, 0x409)
name_table.setName(FAMILY + " Regular", 3, 3, 1, 0x409)
name_table.setName(FAMILY, 4, 3, 1, 0x409)
name_table.setName(FAMILY.replace(" ", ""), 6, 3, 1, 0x409)
name_table.setName("Derived from Ark Pixel 12px Prop by TakWolf (OFL-1.1): each glyph grown by "
                   "one pixel, for the outline of the Retro Bub in-game menu.", 10, 3, 1, 0x409)
font["head"].recalcBBoxes = True
font.save(dst)
print(f"{changed} glyphs grown, saved {dst}")
