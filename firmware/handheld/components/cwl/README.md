# Game Bub CWL transport (L1a)

The portable `cwl_proto.c/.h`, `cwl_lx.c/.h` and `host/proto_test.c` are
byte-for-byte copies from Shiny Relay 1.1.5, distributed under GPL-3.0.
Original comments are preserved (the upstream files have no SPDX header).
The license is included as `COPYING`.

Source: `https://install.shinyrelay.com/download/shiny-relay-src-1.1.5.tar.gz`,
`mcu/components/cwl/` (including `host/proto_test.c`). The Chromatic project's
`README.md` records the archive SHA-256 as `2eea73f7…1160d` (abbreviated there;
no full digest is asserted here).
Local reference: `H:\DevCentral\workspaces\Chromatic GameBub\sources\shiny-relay-src-1.1.5`.

`gamebub_cwl_logic` implements the host-testable session policy. The IDF task
owns that state, calls it every 5 ms or on received traffic, and publishes a
locked snapshot. RX callbacks only copy frames into a bounded static queue.
A full queue drops the frame and increments `rx_drop`. `tx` counts attempted
frames; `tx_fail` counts synchronous ESP-NOW submission errors, not MAC ACKs.
`pongs` counts replies matching the outstanding ping ID and timestamp.

STA uses RAM storage with driver NVS disabled. No AP, UDP, TLS, BT, saved keys,
FPGA port callbacks or send-ahead are used. Full radio is maintained throughout
an active session. A confirmed partner is pinned only until stop/start.
Connected requires mutual pairing and a live CWL2 peer, never NAME or TX alone.

Rust serializes lifecycle calls. Stop joins the session task, unregisters RX,
deinitializes ESP-NOW, stops Wi-Fi and deinitializes the exclusively owned
driver. Static RX queue storage remains valid across late callbacks and is
reset on restart. Netif and the shared default event loop stay initialized.

`make -C firmware/handheld/components/cwl/host test` builds outside the source
tree in `/tmp/gamebub-gb031-host`. `proto_test` runs the upstream 44 scenarios
(including its expected-failure negative control); `lx_test` checks framing
and locks; `adaptor_test` runs two instances of production session policy over
a deterministic lossy radio. Hardware radio/queue scheduling and heap remain
console checks. The FPGA exchange semantics are tested only by upstream
`proto_test`; L1a has no game port bridge.
