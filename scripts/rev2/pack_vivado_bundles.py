#!/usr/bin/env python3
"""Pack several make_vivado_bundle.py bundles into one archive with a build_all.bat that builds them
in parallel on the Windows machine, each in its own window, at most --max-parallel at a time.

Usage (from the repository root, after creating the bundles):
    python3 scripts/rev2/pack_vivado_bundles.py boot gameboy gba --max-parallel 4

Result: dist/vivado/rev2-all.zip
"""

import argparse
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

BUILD_ALL = r"""@echo off
rem Builds every bundle in this folder in parallel, at most MAX at a time, each in its own window.
rem Each bundle writes logs\RESULT.txt (OK or FAILED) when it is done.
setlocal enabledelayedexpansion
cd /d "%~dp0"
set MAX={max_parallel}
set started=0
for /d %%d in (*-gamebub_*) do if exist "%%d\logs\RESULT.txt" del "%%d\logs\RESULT.txt"
for /d %%d in (*-gamebub_*) do (
    call :wait_for_slot
    echo Starting %%d
    start "%%d" cmd /c "cd /d "%%~fd" && build.bat & echo. & pause"
    set /a started+=1
    rem Give Vivado a moment before starting the next one.
    timeout /t 10 /nobreak >nul
)
echo.
echo All builds started (!started!). Each window shows its own progress and result.
echo Results: ^<folder^>\logs\RESULT.txt, bitstreams: ^<folder^>\^<folder^>.bit
pause
exit /b 0

:wait_for_slot
set done=0
for /d %%x in (*-gamebub_*) do if exist "%%x\logs\RESULT.txt" set /a done+=1
set /a running=started-done
if !running! geq %MAX% (
    timeout /t 30 /nobreak >nul
    goto :wait_for_slot
)
exit /b 0
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("names", nargs="+", help="bundle names, e.g. boot gameboy gba")
    parser.add_argument("--target", default="gamebub_rev2")
    parser.add_argument("--max-parallel", type=int, default=4)
    parser.add_argument("--dir", type=Path, default=REPO / "dist" / "vivado")
    parser.add_argument("--archive", default="rev2-all.zip")
    args = parser.parse_args()

    archive = args.dir / args.archive
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("rev2-all/build_all.bat",
                   BUILD_ALL.replace("{max_parallel}", str(args.max_parallel)).replace("\n", "\r\n"))
        for name in args.names:
            bundle = args.dir / f"{name}-{args.target}"
            if not (bundle / "build.bat").exists():
                raise SystemExit(f"missing bundle: {bundle} (run make_vivado_bundle.py first)")
            for p in sorted(bundle.rglob("*")):
                if p.is_file():
                    z.write(p, Path("rev2-all") / bundle.name / p.relative_to(bundle))
    print(f"Archive: {archive} ({archive.stat().st_size // 1024} KiB): {', '.join(args.names)}, "
          f"at most {args.max_parallel} in parallel")


if __name__ == "__main__":
    main()
