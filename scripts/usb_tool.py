import argparse
import struct
import usb.core

REQUEST_TYPE_VENDOR_OUT = 0x41
REQUEST_TYPE_VENDOR_IN = 0xC1

REQUEST_GET_INFO = 0
REQUEST_REBOOT = 1
REQUEST_ENABLE_DEBUG = 2
REQUEST_SCREENSHOT = 7
REQUEST_LCD_DEBUG = 8


def handle_get_info(device: usb.core.Device, args) -> None:
    data = device.ctrl_transfer(
        bmRequestType=REQUEST_TYPE_VENDOR_IN,
        bRequest=REQUEST_GET_INFO,
        data_or_wLength=24,
    )
    assert len(data) >= 24
    _, serial, hw_version, fw_version, commit = struct.unpack("<IIII8s", data)
    print(f"Serial Number:     {serial:08X}")
    print(f"Hardware Version:  {hw_version:08X}")
    print(f"Firmware Version:  {fw_version:08X}")
    print(f"Firmware Commit:   {commit.hex()}")


def handle_enable_debug(device: usb.core.Device, args) -> None:
    device.ctrl_transfer(
        bmRequestType=REQUEST_TYPE_VENDOR_OUT,
        bRequest=REQUEST_ENABLE_DEBUG,
    )


def handle_reboot(device: usb.core.Device, args) -> None:
    mode = 1
    if args.dfu:
        mode = 2

    device.ctrl_transfer(
        bmRequestType=REQUEST_TYPE_VENDOR_OUT,
        bRequest=REQUEST_REBOOT,
        wValue=mode,
    )


def handle_screenshot(device: usb.core.Device, args) -> None:
    device.ctrl_transfer(
        bmRequestType=REQUEST_TYPE_VENDOR_OUT,
        bRequest=REQUEST_SCREENSHOT,
    )


def handle_lcd_debug(device: usb.core.Device, args) -> None:
    device.ctrl_transfer(
        bmRequestType=REQUEST_TYPE_VENDOR_OUT,
        bRequest=REQUEST_LCD_DEBUG,
        data_or_wLength=bytes(args.values)
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Utility for interacting with Game Bub handheld"
    )
    subparsers = parser.add_subparsers(dest="command", help="Commands")

    debug_parser = subparsers.add_parser(
        "enable-debug", help="Enable USB Serial/JTAG mode"
    )
    debug_parser.set_defaults(func=handle_enable_debug)

    get_info_parser = subparsers.add_parser("get-info", help="Get device info")
    get_info_parser.set_defaults(func=handle_get_info)

    reboot_parser = subparsers.add_parser("reboot", help="Reboot device")
    reboot_parser.add_argument("--dfu", action="store_true")
    reboot_parser.set_defaults(func=handle_reboot)

    screenshot_parser = subparsers.add_parser("screenshot", help="Save a screenshot")
    screenshot_parser.set_defaults(func=handle_screenshot)

    lcd_parser = subparsers.add_parser("lcd", help="LCD")
    lcd_parser.add_argument("values", nargs="+", type=lambda x: int(x, 0))
    lcd_parser.set_defaults(func=handle_lcd_debug)


    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return

    device = usb.core.find(idVendor=0x1209, idProduct=0xB010)
    if device is None:
        if usb.core.find(idVendor=0x303A, idProduct=0x1001):
            raise SystemExit("Device not found (USB Serial/JTAG present)")
        else:
            raise SystemExit("Device not found")

    args.func(device, args)


if __name__ == "__main__":
    main()
