# SD card files

| folder | for |
|---|---|
| `cores/Retro-Bub.Lynx/` | the stock Game Bub firmware (1.1 or later): everything but save states |
| `savestate-firmware/cores/Retro-Bub.Lynx/` | a firmware with on-demand core files (`firmware/handheld/` of this repository): adds State Slot, Save State and Load State |

Copy one `Retro-Bub.Lynx` folder to the card's `cores/` folder and put the uncompressed bitstream next to the JSON
files (`lynx-gamebub_rev4.bit` for rev4, `lynx-gamebub_rev2.bit` for rev2; `core.json` lists both). The Lynx boot ROM
is required and not included: `system/lynxboot.img` (512 bytes), dumped from your own Lynx.
