#!/usr/bin/env bash
# Builds a simulation of LynxCore (the Lynx core as the firmware drives it, with the vendored
# AtariLynx_MiSTer machine) and runs a cartridge through it, saving the screen as PPM images.
#
# The VHDL is converted to Verilog with GHDL (simulation only; Vivado builds the VHDL itself, as VHDL-93)
# and run with Verilator. Differences from the hardware build: the `synthesis translate_off` debug code is
# removed before the conversion (Vivado ignores it too), the RAMs start without initial values, the SDRAM is
# a model with a fixed latency.
#
# Usage (from the repository root):
#   scripts/rev2/lynx_test_sim.sh build
#   scripts/rev2/lynx_test_sim.sh run <boot rom> <cartridge> <out dir>
# Environment for run: see fpga/sim/lynx/core_main.cpp (FRAMES, EVERY, LATENCY, CONFIG, START, PRESS_A,
# STATE_TEST, STATE_NO_LOAD).
# Needs ghdl (6.x), Verilator and Python 3.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/build/lynx-sim"
SIM="$REPO/fpga/sim/lynx"

build() {
    mkdir -p "$OUT"
    rm -rf "$OUT/work" "$OUT/vhdl" && mkdir -p "$OUT/work" "$OUT/vhdl"
    (cd "$REPO/fpga" && ./mill -i --no-build-lock root.runMain net.gamebub.core.lynx.SimLynx \
        --target-dir="$OUT/core") > "$OUT/core-mill.log" 2>&1 \
        || { echo "Elaboration failed, see $OUT/core-mill.log"; exit 1; }
    # The core's VHDL, in the order of LynxSources (the elaboration's file list), without the debug code.
    local files=()
    while read -r f; do
        case "$f" in
            *.vhd)
                local name; name=$(basename "$f")
                # Also the Verilog location comments firtool appends to black-box sources (BUNDLE-VHDL-01).
                perl -0pe 's/--\s*synthesis translate_off.*?--\s*synthesis translate_on//gsi;
                           s{([ \t]+)//(?=[ \t]+[^\n]*\.scala:\d+:\d+[ \t]*$)}{$1--}mg' \
                    "$OUT/core/$f" > "$OUT/vhdl/$name"
                files+=("$OUT/vhdl/$name")
                ;;
        esac
    done < <(grep '\.vhd$' "$OUT/core/filelist.f")
    local GHDL=(--std=08 -frelaxed -fsynopsys --workdir="$OUT/work")
    # Imported, then analyzed in dependency order (the file list is not in that order).
    ghdl -i "${GHDL[@]}" "${files[@]}" > "$OUT/ghdl.log" 2>&1
    (cd "$OUT" && ghdl -m "${GHDL[@]}" lynxTopWrap) >> "$OUT/ghdl.log" 2>&1
    ghdl --synth "${GHDL[@]}" --out=verilog lynxTopWrap > "$OUT/lynx_sim.v" 2>> "$OUT/ghdl.log"
    # Never-written ROMs come out as huge X constants, beyond Verilator's number width limit.
    perl -pi -e "s/= \d{5,}'bX;/= '0;/" "$OUT/lynx_sim.v"
    local generated
    generated=$(grep -v '^\s*$' "$OUT/core/filelist.f" | grep -v '\.vhd$' | sed "s|^|$OUT/core/|")
    (cd "$OUT" && verilator --cc --exe --build -j 8 -O2 --top-module LynxCore +1364-2005ext+v \
        $generated lynx_sim.v "$SIM/core_main.cpp" \
        -Wno-fatal -Wno-WIDTH -Wno-lint -Wno-style -Wno-PROCASSWIRE --x-assign fast --x-initial fast \
        -CFLAGS "-O2 -std=c++17" --Mdir obj -o lynx_core > verilator.log 2>&1) \
        || { echo "Verilator build failed, see $OUT/verilator.log"; exit 1; }
    echo "Simulator: $OUT/obj/lynx_core"
}

run() {
    [ -x "$OUT/obj/lynx_core" ] || build
    mkdir -p "$3"
    "$OUT/obj/lynx_core" "$1" "$2" "$3/shot"
}

case "${1:-}" in
    build) build ;;
    run) shift; run "$@" ;;
    *) echo "usage: $0 build | run <boot rom> <cartridge> <out dir>"; exit 2 ;;
esac
