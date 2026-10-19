#!/usr/bin/env python3
"""Prepare a self-contained Vivado project for a core, to be built on another machine.

This splits fpga/scripts/build_core.py in two: the Chisel elaboration and the Edalize project
generation run here (they need mill, Java, firtool and Python), and only Vivado runs on the build
machine. The bundle contains every source file, the generated Tcl scripts and a build.bat that
runs the same two Vivado commands as the Makefile Edalize generates.

Usage (from the repository root):
    python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.boot.HandheldBoot \
        --name boot --target gamebub_rev2

Result: dist/vivado/<name>-<target>/ and dist/vivado/<name>-<target>.zip
Afterwards, scripts/rev2/finish_bitstream.py turns the .bit from the build machine into .bit.hs.
"""

import argparse
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
FPGA = REPO / "fpga"
sys.path.insert(0, str(FPGA / "scripts"))
import build_core  # noqa: E402  (TARGETS, the Edalize backend and the bitstream helpers)
from edalize.edatool import get_edatool  # noqa: E402

# Summary for the build: run states, timing, main resources, critical warnings and errors,
# plus copies of the reports, the logs and the bitstream, all in report\.
SUMMARY_TCL = r"""
set project [current_project]
file mkdir report
set f [open report/SUMMARY.txt w]
puts $f "Project: $project"
puts $f "Vivado: [version -short]"
puts $f ""
foreach run {synth_1 impl_1} {
    puts $f "$run: [get_property STATUS [get_runs $run]]"
}
set impl [get_runs impl_1]
if {[get_property PROGRESS $impl] == "100%"} {
    puts $f ""
    puts $f "Timing: WNS [get_property STATS.WNS $impl] ns, TNS [get_property STATS.TNS $impl] ns, WHS [get_property STATS.WHS $impl] ns, THS [get_property STATS.THS $impl] ns"
    if {[catch {open_run impl_1}] == 0} {
        puts $f ""
        puts $f "Resources (used / available / %):"
        set seen {}
        foreach line [split [report_utilization -return_string] "\n"] {
            foreach key {"Slice LUTs " "Slice Registers " "Block RAM Tile " "DSPs "} {
                if {[string match "| $key*" $line] && [lsearch $seen $key] < 0} {
                    lappend seen $key
                    set cols [lmap c [split $line "|"] {string trim $c}]
                    puts $f "  [lindex $cols 1]: [lindex $cols 2] / [lindex $cols 5] ([lindex $cols 6]%)"
                }
            }
        }
        report_timing_summary -file report/timing_summary.rpt
        report_utilization -file report/utilization.rpt
    }
}
puts $f ""
puts $f "Critical warnings and errors:"
set count 0
set dir [get_property DIRECTORY $project]
foreach log [list $dir/$project.runs/synth_1/runme.log $dir/$project.runs/impl_1/runme.log] {
    if {[file exists $log]} {
        file copy -force $log report/[file tail [file dirname $log]]_runme.log
        set in [open $log r]
        while {[gets $in line] >= 0} {
            if {[string match "CRITICAL WARNING*" $line] || [string match "ERROR:*" $line]} {
                puts $f "  $line"
                incr count
            }
        }
        close $in
    }
}
if {$count == 0} { puts $f "  none" }
foreach bit [glob -nocomplain *.bit] { file copy -force $bit report/ }
foreach log [glob -nocomplain logs/*.log] { file copy -force $log report/ }
close $f
"""


FILE_TYPES = {".xdc": "xdc", ".v": "verilogSource", ".sv": "systemVerilogSource", ".vhdl": "vhdlSource",
              ".vhd": "vhdlSource-2008"}

# VHDL files that must be read as VHDL-93. The elaboration copies black-box sources without their directories, so
# they are recognized by name. The Lynx core is read as VHDL-93 throughout, as in the gamebub-0.1 fork's Vivado
# builds (tested on the console); its `dpram.vhd` uses a shared variable for the true dual-port RAM (VHDL-2008
# requires a protected type).
VHDL93_FILES = {p.name for p in (FPGA / "vendor" / "lynx_mister" / "rtl").glob("*.vhd")}
VHDL93_FILES |= {p.name for p in (FPGA / "src" / "main" / "vhdl" / "lynx").glob("*.vhd")}


# Per-core build settings, keyed by core class (none of the cores in this tree needs any):
#  - defines: Verilog defines for the whole project;
#  - vhdl93: read every VHDL file of the core as VHDL-93;
#  - directive: the opt / place / phys_opt / route directive of the implementation, and the matching strategy
#    for the project flow (build.bat);
#  - sources: vendored HDL copied into the bundle with its directories (for cores whose file names repeat across
#    directories, which the elaboration's flat copy of black-box sources would overwrite); directories are taken
#    whole, sorted; VHDL in dependency order where listed explicitly;
#  - prepare: a script run before the copy (e.g. generating ROM entities).
# Per-core constraints are verilog/handheld/core_<name>.xdc when that file exists.
CORE_SETTINGS = {}
STRATEGY_FOR_DIRECTIVE = {"Explore": "Performance_Explore"}


def fix_vhdl_source_locations(path: Path) -> None:
    """BUNDLE-VHDL-01: firtool appends Verilog location comments to VHDL black boxes.

    Only change generated Scala provenance comments; preserve the HDL and its encoding.
    """
    original = path.read_bytes()
    corrected = re.sub(
        rb"(?m)([ \t]+)//(?=[ \t]+[^\r\n]*\.scala:[0-9]+:[0-9]+[ \t]*\r?$)",
        rb"\1--", original,
    )
    if corrected != original:
        path.write_bytes(corrected)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-class", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--target", default="gamebub_rev2", choices=[t.name for t in build_core.TARGETS])
    parser.add_argument("--out", type=Path, default=REPO / "dist" / "vivado")
    parser.add_argument("--vivado", help="path of vivado.bat on the build machine (tried first)")
    args = parser.parse_args()

    target = next(t for t in build_core.TARGETS if t.name == args.target)
    project = f"{args.name}-{args.target}"
    bundle = (args.out / project).resolve()
    if bundle.exists():
        shutil.rmtree(bundle)
    (bundle / "src").mkdir(parents=True)

    # 1. Chisel -> SystemVerilog (same command as build_core.build()).
    generated = bundle / "src" / "generated"
    subprocess.run(
        [str(build_core.MILL_PATH), "-i", "--no-build-lock", "root.runMain", target.main_class,
         args.core_class, *target.chisel_args, f"--target-dir={generated}"],
        cwd=FPGA, check=True,
    )
    files = []
    for line in (generated / "filelist.f").read_text().splitlines():
        if line.strip():
            source = generated / line.strip()
            if source.suffix.lower() in {".vhd", ".vhdl"}:
                fix_vhdl_source_locations(source)
            files.append(source)

    settings = CORE_SETTINGS.get(args.core_class, {})
    for script in settings.get("prepare", []):
        subprocess.run([sys.executable, "-I", str(FPGA / script)], check=True)

    # 2. The target's fixed RTL and constraints, copied next to the generated files, and the core's own
    # constraints (verilog/handheld/core_<name>.xdc) if it has any.
    fixed = list(target.files)
    core_xdc = f"verilog/handheld/core_{args.name}.xdc"
    if (build_core.FRAMEWORK_ROOT / core_xdc).exists():
        fixed.append(core_xdc)
    for rel in fixed:
        dst = bundle / "src" / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(build_core.FRAMEWORK_ROOT / rel, dst)
        files.append(dst)
    seen = set()
    for entry in settings.get("sources", []):
        path = FPGA / entry
        if path.is_dir():
            candidates = sorted(p for p in path.rglob("*") if p.suffix in {".v", ".sv", ".vhd", ".vhdl"})
        elif path.exists():
            candidates = [path]
        else:
            sys.exit(f"Missing core source: {path}")
        for candidate in candidates:
            if candidate in seen:
                continue
            seen.add(candidate)
            dst = bundle / "src" / candidate.relative_to(FPGA)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(candidate, dst)
            files.append(dst)

    edam_files = []
    # Include files the core's sources `include` (copied by the elaboration, not in its filelist): their
    # directory becomes an include path.
    for f in sorted(generated.glob("*.svh")):
        edam_files.append({"name": f.relative_to(bundle).as_posix(), "file_type": "systemVerilogSource",
                           "is_include_file": True})
    for f in files:
        file_type = FILE_TYPES.get(f.suffix)
        if f.suffix == ".vhd" and (f.name in VHDL93_FILES or settings.get("vhdl93")):
            file_type = "vhdlSource"
        if file_type is None:
            print("WARNING: skipping unknown file type:", f)
            continue
        edam_files.append({"name": f.relative_to(bundle).as_posix(), "file_type": file_type})

    # 3. Vivado project scripts (Edalize configure only; no build here).
    edam = {
        "name": project,
        "toplevel": target.top_module,
        "files": edam_files,
        "parameters": {
            name: {"datatype": "bool", "paramtype": "vlogdefine", "default": True}
            for name in [*target.verilog_defines, *settings.get("defines", [])]
        },
        "tool_options": {"vivado": {"part": target.part}},
    }
    get_edatool("vivado")(edam=edam, work_root=str(bundle)).configure()

    # Implementation effort: set as the strategy of impl_1 (build.bat, Makefile).
    directive = settings.get("directive")
    if directive:
        (bundle / "impl_directive.txt").write_text(directive + "\n")
        run_tcl = bundle / f"{project}_run.tcl"
        run_tcl.write_text(
            "# Added by make_vivado_bundle.py: implementation effort for timing closure.\n"
            f"set_property strategy {STRATEGY_FOR_DIRECTIVE[directive]} [get_runs impl_1]\n\n"
            + run_tcl.read_text()
        )

    (bundle / f"{project}_summary.tcl").write_text(SUMMARY_TCL.lstrip())

    git_rev = build_core.get_git_revision()
    (bundle / "GITREV").write_text(git_rev + "\n")

    # 4. build.bat: the two Vivado steps of the generated Makefile (project, then synth + run),
    # each with its own log in logs\.
    lines = [
        "@echo off",
        "rem Builds the bitstream with Vivado. Set VIVADO to vivado.bat to use a specific installation.",
        "cd /d \"%~dp0\"",
    ]
    if args.vivado:
        lines.append(f"if \"%VIVADO%\"==\"\" if exist \"{args.vivado}\" set \"VIVADO={args.vivado}\"")
    lines += [
        "rem Otherwise look for an installation (newest version last wins).",
        "if \"%VIVADO%\"==\"\" for %%r in (C:\\Xilinx\\Vivado C:\\AMDDesignTools H:\\AMDDesignTools) do "
        "for /d %%v in (\"%%r\\*\") do (if exist \"%%v\\bin\\vivado.bat\" set \"VIVADO=%%v\\bin\\vivado.bat\") "
        "& (if exist \"%%v\\Vivado\\bin\\vivado.bat\" set \"VIVADO=%%v\\Vivado\\bin\\vivado.bat\")",
        "if \"%VIVADO%\"==\"\" set VIVADO=vivado",
        f"echo [{project}] Using %VIVADO%",
        "if not exist logs mkdir logs",
        f"echo [{project}] 1/2 project",
        f"call \"%VIVADO%\" -notrace -mode batch -log logs\\1-project.log -journal logs\\1-project.jou "
        f"-source {project}.tcl || goto :failed",
        f"echo [{project}] 2/2 synthesis, implementation, bitstream",
        f"call \"%VIVADO%\" -notrace -mode batch -log logs\\2-build.log -journal logs\\2-build.jou "
        f"-source {project}_synth.tcl {project}_run.tcl {project}.xpr || goto :failed",
        f"if not exist {project}.bit goto :failed",
        "call :summary",
        "echo.",
        f"echo [{project}] Done. Bring the folder %CD%\\report",
        f"echo OK> logs\\RESULT.txt",
        "exit /b 0",
        ":failed",
        "call :summary",
        "echo.",
        f"echo [{project}] Build FAILED. Bring the folder %CD%\\report",
        f"echo FAILED> logs\\RESULT.txt",
        "exit /b 1",
        ":summary",
        f"echo [{project}] Writing report\\SUMMARY.txt",
        f"call \"%VIVADO%\" -notrace -mode batch -log logs\\3-summary.log -journal logs\\3-summary.jou "
        f"-source {project}_summary.tcl {project}.xpr",
        "exit /b 0",
    ]
    (bundle / "build.bat").write_text("\r\n".join(lines) + "\r\n")

    archive = args.out / f"{project}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(bundle.rglob("*")):
            if p.is_file():
                z.write(p, Path(project) / p.relative_to(bundle))
    print(f"Bundle: {bundle}\nArchive: {archive} ({archive.stat().st_size // 1024} KiB), git {git_rev}")


if __name__ == "__main__":
    main()
