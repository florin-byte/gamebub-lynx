#!/usr/bin/env python3
"""Draw the rev2 Game Bub for the first-start tutorial (`firmware/handheld/res/device-rev2.png`).

Same style as Eli's rev4 drawing (`res/device.png`): 1 bit, lines opaque, the rest transparent; the UI colorizes it.
The positions come from Eli's rev2 case and board in the Game Bub 0.1 sources (3d/handheld_rev2/Front.stl and
pcb/handheld_rev2/handheld.kicad_pcb, not in this repository), measured in mm from the case center (x) and the case
bottom (z), and drawn at 0.8 px/mm. The volume buttons are on the left side, the power button on the right side.

The highlight positions for `res/ui/screens/setup.slint` are printed at the end.

Usage: python3 scripts/rev2/make_device_rev2.py
"""

from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "firmware/handheld/res/device-rev2.png"

SCALE = 0.8  # px per mm
CASE_W, CASE_H = 95.1, 140.0  # mm
SIDE = 2  # px left and right of the case for the side buttons
W = SIDE * 2 + round(CASE_W * SCALE)
H = round(CASE_H * SCALE)

px = set()


def X(x_mm):
    """Case x (mm from the center, right positive) to a pixel column."""
    return round(SIDE + (x_mm + CASE_W / 2) * SCALE)


def Y(z_mm):
    """Case z (mm from the bottom) to a pixel row."""
    return round((CASE_H - z_mm) * SCALE)


def hline(x0, x1, y):
    for x in range(x0, x1 + 1):
        px.add((x, y))


def vline(x, y0, y1):
    for y in range(y0, y1 + 1):
        px.add((x, y))


def rounded_rect(x0, y0, x1, y1, r):
    """Outline with pixel corners of radius r (1 to 3)."""
    corner = {1: [(0, 1)], 2: [(0, 2), (1, 1)], 3: [(0, 3), (0, 2), (1, 1), (2, 0)]}[r]
    hline(x0 + r, x1 - r, y0)
    hline(x0 + r, x1 - r, y1)
    vline(x0, y0 + r, y1 - r)
    vline(x1, y0 + r, y1 - r)
    for dx, dy in corner:
        for cx, sx in ((x0, 1), (x1, -1)):
            for cy, sy in ((y0, 1), (y1, -1)):
                px.add((cx + sx * dx, cy + sy * dy))
                px.add((cx + sx * dy, cy + sy * dx))


CIRCLES = {
    5: [".###.", "#...#", "#...#", "#...#", ".###."],
    7: ["..###..", ".#...#.", "#.....#", "#.....#", "#.....#", ".#...#.", "..###.."],
}


def circle(cx, cy, d):
    rows = CIRCLES[d]
    for j, row in enumerate(rows):
        for i, c in enumerate(row):
            if c == "#":
                px.add((cx - d // 2 + i, cy - d // 2 + j))


def plus(cx, cy, size, arm):
    """D-pad outline: a plus `size` px across with arms `arm` px wide."""
    h, a = size // 2, arm // 2
    pts = [(-a, -h), (a, -h), (a, -a), (h, -a), (h, a), (a, a), (a, h), (-a, h), (-a, a), (-h, a), (-h, -a), (-a, -a)]
    for (ax, ay), (bx, by) in zip(pts, pts[1:] + pts[:1]):
        if ax == bx:
            vline(cx + ax, cy + min(ay, by), cy + max(ay, by))
        else:
            hline(cx + min(ax, bx), cx + max(ax, bx), cy + ay)


# Case 95.1 x 140 mm.
case_x0, case_x1 = SIDE, W - SIDE - 1
rounded_rect(case_x0, 0, case_x1, H - 1, 3)

# Glass 92 x 67.8 mm (z 70.7 to 138.5), drawn 2 px inside the case so the two lines stay apart.
glass = (case_x0 + 2, 2, case_x1 - 2, Y(70.7))
rounded_rect(*glass, 2)

# Screen (the clear part of the glass): 73.69 x 49.21 mm, centered on the glass, 3:2 like the panel.
scr_w, scr_h = 60, 40
scr_cx = (glass[0] + glass[2]) // 2
scr_cy = (glass[1] + glass[3]) // 2
scr = (scr_cx - scr_w // 2, scr_cy - scr_h // 2, scr_cx - scr_w // 2 + scr_w - 1, scr_cy - scr_h // 2 + scr_h - 1)
rounded_rect(*scr, 1)

# Status LED (41.0, 75.6), the clear dot in the bottom right corner of the glass.
led = (X(41.0), Y(75.6))
for dx in (0, 1):
    for dy in (0, 1):
        px.add((led[0] - 1 + dx, led[1] - 1 + dy))

# D-pad (-27.6, 46.0), 18.4 mm.
dpad = (X(-27.6), Y(46.0))
plus(*dpad, 15, 5)

# A B X Y, 7.8 mm, around (27.5, 46.0).
buttons = {"x": (27.5, 53.5), "y": (20.1, 46.0), "a": (35.0, 46.0), "b": (27.5, 38.5)}
centers = {k: (X(x), Y(z)) for k, (x, z) in buttons.items()}
for c in centers.values():
    circle(*c, 7)

# Home (0, 32.3), Select (-3.9, 16.7), Start (3.9, 16.7).
home = (X(0), Y(32.3))
circle(*home, 5)
for x in (-3.9, 3.9):
    circle(X(x), Y(16.7), 5)

# Speakers: 7 x 3 holes, 2 mm apart, at x 24.3 to 36.3 and z 6.2 to 10.2; drawn as 5 x 3 dots 2 px apart.
for sx in (-1, 1):
    for i in range(5):
        for j in range(3):
            cx = X(sx * 30.3) + (i - 2) * 2
            px.add((cx, Y(8.2) + (j - 1) * 2))

# Side buttons (board rev2: volume up / down at y 48.4 / 58.4 mm on the left side, power at y 36 mm on the right
# side; the case top is board y 17.5).
def side_y(board_y):
    return Y(157.5 - board_y)


vol_up = (side_y(48.4) - 3, side_y(48.4) + 2)
vol_down = (side_y(58.4) - 3, side_y(58.4) + 2)
power = (side_y(36.0) - 3, side_y(36.0) + 2)


def side_button(x, y0, y1, out):
    """A bump 2 px out of the case side, like the buttons on top of Eli's rev4 drawing."""
    vline(x + 2 * out, y0 + 1, y1 - 1)
    px.add((x + out, y0))
    px.add((x + out, y1))


for y0, y1 in (vol_up, vol_down):
    side_button(case_x0, y0, y1, -1)
side_button(case_x1, *power, 1)

img = Image.new("1", (W, H), 0)
for x, y in px:
    if 0 <= x < W and 0 <= y < H:
        img.putpixel((x, y), 1)
img.save(OUT, transparency=0, optimize=True)
print(f"{OUT}: {W}x{H}")
print("highlights (x, y) for setup.slint:")
print(f"  a: {centers['a']}  b: {centers['b']}  home: {home}")
print(f"  power: x {case_x1 + 1}..{case_x1 + 2}, y {power[0]}..{power[1]}")
print(f"  volume up: x {case_x0 - 2}..{case_x0 - 1}, y {vol_up[0]}..{vol_up[1]}  volume down: y {vol_down[0]}..{vol_down[1]}")
print(f"  screen: {scr}")
