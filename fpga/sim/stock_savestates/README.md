# Stock save-state protocol tests

The shared controller test uses `controller.cpp` with `SimStockSaveStateSlots` (add `--sms` for GG).
It covers all four slot addresses, arbitrary GG save counters, empty/invalid headers, wrong GG game ID,
legacy size, blocked requests, latched slot, overlapping requests, completion/error and timeout.
The emitted target has a deliberately short 1024-cycle watchdog. Build its generated SV with Verilator
`--cc --exe --build --top-module StockSaveStateSlots` and this C++ source; pass any argument to the
resulting controller executable for GG, no arguments for Lynx.

Core integration uses actual engines and modeled SDRAM. From the repository root, choose fresh absolute
SIM_OUT directories and build:

```sh
SIM_OUT=/tmp/lynx-stock-test bash scripts/rev2/lynx_test_sim.sh build
STOCK_TEST=1 CONFIG=0 LATENCY=3 /tmp/lynx-stock-test/obj/lynx_core /path/to/bios.bin /path/to/game.lnx
```

Repeat the two execution commands with STOCK_FILE_TEST=1 instead of STOCK_TEST=1 for readiness,
truncation, bulk-open and busy rejection checks. Both harness modes assert and exit on failure.
`protocol.hpp` tests empty loads, all four saves/loads, changes/rejections while busy, isolation,
CORE_HALT before export completion, byte-exact container reimport and native file-2 payload transfers.
These tests do not compare restored video/audio, run physical SPI/SD or validate stock firmware itself.
See the repository README for the two deployment methods and slot-file sizes. The other core uses the same
controller and protocol harness in its own repository.
