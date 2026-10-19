#!/usr/bin/env python3
"""Generate the "Retro Bub" logo files from the pixel art below.

The lettering follows the original logo (3 px strokes, shadow below and to the right); "e" and
"Bub" are the original pixels.

Outputs (paths relative to the repository root):
    firmware/handheld/res/logo.png          72+ x 12, face and shadow colors (reference image)
    firmware/handheld/res/logo-face.png     white mask of the face, colorized by the UI theme
    firmware/handheld/res/logo-shadow.png   white mask of the shadow
    fpga/src/main/resources/logo.png        2x scale, FPGA boot logo: white = face, gray 128 = shadow,
                                            opaque black = background (see core/boot/Logo.scala)

Usage: python3 scripts/rev2/make_logo.py   (needs Pillow)
"""

from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]

# '#' = face, '+' = shadow, ' ' = background. 12 rows.
LOGO = [
    "########                                     ########            ###     ",
    "#########           ###                      #########           ###     ",
    "###+++###           ###                      ###+++###           ###     ",
    "###   ###   #####   ##### ### ##  ######     ###   ###  ###  ### ######  ",
    "########+  #######  ##### ###### ########    ########+  ###  ### ####### ",
    "#########  ###  ### ###++ ####++ ###  ###    #########  ###  ### ###++###",
    "###++###   ######## ###   ###    ###  ###    ###++++### ###  ### ###  ###",
    "###  ###   ###+++++ ###   ###    ###  ###    ###    ### ###  ### ###  ###",
    "###   ###  ###      ###   ###    ###  ###    ###    ### ###  ### ###  ###",
    "###   ###  #######  ##### ###    ########    ########## +####### ########",
    "###    ### +######  +#### ###    +######+    #########+  +#####+ +######+",
    "+++    +++  ++++++   ++++ +++     ++++++     +++++++++    +++++   ++++++ ",
]

FACE = (0x81, 0x0F, 0x97)
SHADOW = (0x5B, 0x0B, 0x6A)


def main() -> None:
    width = max(len(row) for row in LOGO)
    rows = [row.ljust(width) for row in LOGO]
    height = len(rows)

    color = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    face = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    fpga = Image.new("RGBA", (width * 2, height * 2), (0, 0, 0, 255))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                color.putpixel((x, y), FACE + (255,))
                face.putpixel((x, y), (255, 255, 255, 255))
                level = (255, 255, 255, 255)
            elif ch == "+":
                color.putpixel((x, y), SHADOW + (255,))
                shadow.putpixel((x, y), (255, 255, 255, 255))
                level = (128, 128, 128, 255)
            else:
                continue
            for dy in (0, 1):
                for dx in (0, 1):
                    fpga.putpixel((2 * x + dx, 2 * y + dy), level)

    res = REPO / "firmware" / "handheld" / "res"
    color.save(res / "logo.png", optimize=True)
    face.save(res / "logo-face.png", optimize=True)
    shadow.save(res / "logo-shadow.png", optimize=True)
    fpga.save(REPO / "fpga" / "src" / "main" / "resources" / "logo.png", optimize=True)
    print(f"logo {width}x{height}, FPGA logo {width * 2}x{height * 2}")


if __name__ == "__main__":
    main()
