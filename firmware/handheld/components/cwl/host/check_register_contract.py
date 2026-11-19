#!/usr/bin/env python3
"""Check that every FPGA link register the C link code reads or writes is
allowed by the Rust driver (link_read / link_write in drivers/fpga/mod.rs).

A missing entry makes the Rust side refuse the access before SPI, which on
hardware looks like a link reset loop (GB-104: 0x90/0x94/0x98).

Usage: check_register_contract.py [--self-test]
Exit 0 when the contract holds, 1 otherwise.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CWL = HERE.parent
RUST = CWL.parents[1] / "src" / "device" / "drivers" / "fpga" / "mod.rs"

# C file -> (read wrapper, write wrapper)
C_FILES = {
    "gamebub_cwl_bridge.c": ("read_reg", "write_reg"),
    "gamebub_cwl_gba.c": ("rd", "wr"),
    "gamebub_cwl_sync.c": ("rd", "wr"),  # GB-147; its register enum is in the header
}


def c_enum(text):
    return {n: int(v, 16) for n, v in re.findall(r"\b([A-Z_]+)\s*=\s*(0x[0-9a-fA-F]+)", text)}


def c_accesses(name, text, read_fn, write_fn):
    consts = c_enum(text)
    reads, writes = set(), set()
    found = re.findall(r"\b(%s|%s)\(\s*s\s*,\s*([A-Z_]+)" % (read_fn, write_fn), text)
    # GB-114 batch entries: OPR(REG) reads, OPW(REG, value) writes.
    found += [("OPR", r) for r in re.findall(r"\bOPR\(\s*([A-Z_]+)\s*\)", text)]
    found += [("OPW", r) for r in re.findall(r"\bOPW\(\s*([A-Z_]+)\s*,", text)]
    for fn, reg in found:
        if reg not in consts:
            raise SystemExit(f"{name}: register {reg} has no address")
        (reads if fn in (read_fn, "OPR") else writes).add((reg, consts[reg]))
    return reads, writes


def rust_whitelist(text):
    consts = {n: int(v, 16) for n, v in re.findall(r"pub const ((?:GBA_)?LINK_[A-Z_]+): u32 = (0x[0-9a-fA-F]+);", text)}

    def arm(fn):
        # Single source for single accesses and batches (GB-114).
        body = re.search(r"pub const fn %s_ok\(register: u32\) -> bool \{\s*matches!\(register,(.*?)\)" % fn,
                         text, re.S)
        if not body:
            raise SystemExit(f"mod.rs: {fn}_ok matches! not found")
        return {consts[n] for n in re.findall(r"[A-Z_]+", body.group(1))}

    return arm("link_read"), arm("link_write")


def check(rust_text, c_texts):
    allowed_r, allowed_w = rust_whitelist(rust_text)
    errors = []
    total = 0
    for name, text in c_texts.items():
        read_fn, write_fn = C_FILES[name]
        reads, writes = c_accesses(name, text, read_fn, write_fn)
        total += len(reads) + len(writes)
        errors += [f"{name}: read {r} 0x{a:02x} refused by link_read" for r, a in sorted(reads) if a not in allowed_r]
        errors += [f"{name}: write {r} 0x{a:02x} refused by link_write" for r, a in sorted(writes) if a not in allowed_w]
    # event_pop reads EVENT and writes POP through its own Rust function.
    if 0x4C not in allowed_r or 0x50 not in allowed_w:
        errors.append("mod.rs: LINK_EVENT read / LINK_EVENT_POP write missing (event_pop)")
    return total, errors


def main():
    rust_text = RUST.read_text(encoding="utf-8")
    c_texts = {n: (CWL / n).read_text(encoding="utf-8") for n in C_FILES}
    c_texts["gamebub_cwl_sync.c"] += (CWL / "gamebub_cwl_sync.h").read_text(encoding="utf-8")
    total, errors = check(rust_text, c_texts)
    if "--self-test" in sys.argv:
        # The contract must fail when 0x90 is dropped from both lists (the GB-104 bug); 0x9c (GB-117) likewise.
        broken = rust_text.replace("GBA_LINK_SNAPSHOT | GBA_LINK_SNAPSHOT_CYCLES | GBA_LINK_SAMPLED)", "GBA_LINK_EVENT_CYCLES)").replace(
            "GBA_LINK_SNAPSHOT | GBA_LINK_COMMAND_ANCHOR)", "GBA_LINK_COMMAND_CYCLES)")
        _, broken_errors = check(broken, c_texts)
        if not broken_errors:
            print("FAIL self-test: removing 0x90/0x94/0x98/0x9c was not detected")
            return 1
        print(f"self-test: removing 0x90/0x94/0x98/0x9c detected ({len(broken_errors)} errors)")
    for e in errors:
        print("FAIL", e)
    if errors:
        return 1
    print(f"PASS register contract: {total} distinct C accesses allowed by the Rust driver")
    return 0


if __name__ == "__main__":
    sys.exit(main())
