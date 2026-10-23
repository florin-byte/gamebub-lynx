# SD card files

| folder | for |
|---|---|
| `cores/Retro-Bub.Lynx/` | stock beta3 firmware: four slots in a 512 KiB `.lnxstate` container, written at normal exit |
| `savestate-firmware/cores/Retro-Bub.Lynx/` | a firmware with on-demand core files (`firmware/handheld/` of this repository): individual `.ss0`–`.ss3` files, written immediately |

Copy one `Retro-Bub.Lynx` folder to the card's `cores/` folder and put the uncompressed bitstream next to the JSON
files (`lynx-gamebub_rev4.bit` for rev4, `lynx-gamebub_rev2.bit` for rev2; `core.json` lists both). The Lynx boot ROM
is required and not included: `system/lynxboot.img` (512 bytes), dumped from your own Lynx.

These JSONs must ship with the matching framework-v2 unified bitstream. I tested the on-demand path on rev2
on 23 Oct 2026. The stock path is left for other users to test; the new rev4 build is hardware-untested. Stock save states created during a session are lost if the game does not exit
normally. Existing individual slot files are not automatically converted into the stock container.
