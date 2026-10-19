#!/usr/bin/env python3
"""Post-process a bitstream built from a make_vivado_bundle.py bundle, as build_core.py does after
Vivado: add the git revision to the bitstream header and write the heatshrink-compressed .bit.hs.

Usage (from the repository root):
    python3 scripts/rev2/finish_bitstream.py path/to/boot-gamebub_rev2.bit --name boot

Writes dist/rev2/built/<name>.bit and dist/rev2/built/<name>.bit.hs. The git revision is read from
the GITREV file of the bundle (dist/vivado/<bundle>/GITREV) when it exists.
"""

import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "fpga" / "scripts"))
import build_core  # noqa: E402

import heatshrink2  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bit", type=Path)
    parser.add_argument("--name", required=True, help="system file name, e.g. boot, gameboy, gba")
    parser.add_argument("--bundle", type=Path, help="bundle directory (default: guessed from the .bit name)")
    parser.add_argument("--out", type=Path, default=REPO / "dist" / "rev2" / "built")
    args = parser.parse_args()

    bundle = args.bundle or (REPO / "dist" / "vivado" / args.bit.stem)
    gitrev_file = bundle / "GITREV"
    git_rev = gitrev_file.read_text().strip() if gitrev_file.exists() else ""

    bitstream = bytearray(args.bit.read_bytes())
    build_core.insert_bitstream_metadata(bitstream, ";GitRev=" + git_rev)
    # Same parameters as build_core.py.
    compressed = heatshrink2.encode(bytes(bitstream), window_sz2=9, lookahead_sz2=6)

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / f"{args.name}.bit").write_bytes(bitstream)
    (args.out / f"{args.name}.bit.hs").write_bytes(compressed)
    print(f"{args.out / (args.name + '.bit.hs')}: {len(compressed)} bytes (from {len(bitstream)}), GitRev={git_rev}")


if __name__ == "__main__":
    main()
