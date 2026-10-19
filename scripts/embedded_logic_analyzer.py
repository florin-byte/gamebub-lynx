import argparse
import base64
from serial import Serial
import sys
import time
import math
import json
import re
from typing import Optional
from vcd import VCDWriter # pyvcd

REG_SAMPLE_WIDTH = 0x0
REG_SAMPLE_DEPTH = 0x4
REG_LOG_INFO = 0x8
REG_POST_TRIGGER_SAMPLES = 0xC
REG_ARM = 0x10
REG_FORCE_TRIGGER = 0x14
SIGNAL_BASE = 0x40_0000
LOG_BASE = 0x80_0000

class Signal:
    name: str
    offset: int
    width: int
    variants: dict[int, str]

class Log:
    signals: list[Signal]
    trigger_index: int
    samples: list[tuple[int]]

    def write_vcd(self, f) -> None:
        vcd = VCDWriter(f, version="EmbeddedLogicAnalyzer")
        variables = []
        for signal in self.signals:
            var_type = "string" if signal.variants else "wire"
            variables.append(vcd.register_var(
                scope="ELA",
                name=signal.name,
                var_type=var_type,
                size=signal.width
            ))
        for ts, sample in enumerate(self.samples):
            for i, raw_value in enumerate(sample):
                signal = self.signals[i]
                if signal.variants:
                    if raw_value in signal.variants:
                        value = signal.variants[raw_value]
                    else:
                        value = f"({raw_value})"
                else:
                    value = raw_value
                vcd.change(variables[i], ts, value)
        vcd.close()


    def print(self) -> None:
        for i, sample in enumerate(self.samples):
            print(pad_int(i, len(self.samples)), ": ", end="")
            for value, signal in zip(sample, self.signals):
                if signal.variants:
                    # special case: enum with named variants
                    max_width = max(len(x) for x in signal.variants.values())
                    if value in signal.variants:
                        print(" ", signal.variants[value].rjust(max_width, " "), end="")
                    else:
                        print(f" ({hex(value)})", end="")
                elif signal.width == 1:
                    # special case 1-bit signals
                    print(" ", value, end="")
                else:
                    max_value = (1 << signal.width) - 1
                    print(" ", pad_hex(value, max_value), end="")
            if i == self.trigger_index:
                print("  <-- TRIGGER", end="")
            print()

class EmbeddedLogicAnalyzer:
    def __init__(self, serial: Serial, base_address: int, metadata) -> None:
        self.serial = serial
        self.base_address = base_address
        
        self.signals = []
        for raw in metadata["signals"]:
            signal = Signal()
            signal.name = raw["name"]
            signal.offset = raw["offset"]
            signal.width = raw["width"]
            signal.variants = {x[0]: x[1] for x in raw["variants"]}
            self.signals.append(signal)

        # Discard whatever's in the buffer first
        self.serial.timeout = 0.1
        data = self.serial.read(4096)
        self.serial.timeout = None

    def run_command(self, command: str) -> str:
        self.serial.write(b">" + command.encode() + b"\n")
        while True:
            response = self.serial.readline()
            if not response.startswith(b"<"):
                # Ignore a non-response line
                print(response)
                continue
            return response[1:].rstrip()

    def fpga_read(self, address: int, length: int) -> bytes:
        max_clock_mhz = 10
        word_size = 32
        command = f"fpga_read,{hex(address)},{word_size},{max_clock_mhz},{hex(length)}"
        output = self.run_command(command)
        if not output.startswith(b"ok"):
            raise Exception("Read failed: " + output.decode())
        return base64.b64decode(output[3:])

    def fpga_write(self, address: int, data: bytes) -> None:
        max_clock_mhz = 40
        word_size = 32
        data = base64.b64encode(data)
        command = f"fpga_write,{hex(address)},{word_size},{max_clock_mhz}," + data.decode()
        output = self.run_command(command)
        if not output.startswith(b"ok"):
            raise Exception("Write failed: " + output.decode())

    def read_register(self, address: int) -> int:
        data = self.fpga_read(self.base_address + address, 4)
        return int.from_bytes(data, "little")

    def write_register(self, address: int, value: int) -> None:
        data = value.to_bytes(4, byteorder="little")
        self.fpga_write(self.base_address + address, data)

    def set_signal_trigger(self, signal: int, match0: Optional[int] = None, match1: Optional[int] = None):
        """
        Enable triggering on a specific signal.

        match0 represents the matcher for the most recent value of the signal,
        and match1 represents the matcher for the second most recent value of the signal.

        For example, to match a 1-bit signal on a rising edge, set match0 to 1 and match1 to 0.
        """
        register_base = SIGNAL_BASE + (signal * 0x10)
        flags = 0b1 # Trigger
        if match0 is not None:
            flags |= 0b10
            self.write_register(register_base + 0x4, match0)
        if match1 is not None:
            flags |= 0b100
            self.write_register(register_base + 0x8, match1)
        self.write_register(register_base + 0x0, flags)

    def unset_signal_trigger(self, signal: int) -> None:
        """Disable triggering on a specific signal."""
        register = SIGNAL_BASE + (signal * 0x10)
        self.write_register(register, 0)

    def arm(self) -> None:
        """Arm the logic analyzer, starting the recording."""
        self.write_register(REG_ARM, 1)

    def force_trigger(self) -> None:
        """Immediately force a trigger, if the logic analyzer is armed."""
        self.write_register(REG_FORCE_TRIGGER, 1)

    def set_post_trigger_samples(self, count: int) -> None:
        """Set the number of samples to be collected after the trigger."""
        self.write_register(REG_POST_TRIGGER_SAMPLES, count)

    def get_is_triggered(self):
        log_info = self.read_register(REG_LOG_INFO)
        return bool((log_info >> 26) & 1)

    def read_log(self) -> Optional[Log]:
        """Reads the sample log, or returns None if the log is currently being recorded."""
        log_info = self.read_register(REG_LOG_INFO)
        log_write_index = log_info & 0xFFFFFF
        log_write_wrapped = (log_info >> 24) & 1
        log_is_recording = (log_info >> 25) & 1
        if log_is_recording == 1:
            return None

        sample_width = self.read_register(REG_SAMPLE_WIDTH)
        sample_depth = self.read_register(REG_SAMPLE_DEPTH)
        num_post_trigger = self.read_register(REG_POST_TRIGGER_SAMPLES)
        words_per_sample = int(math.ceil(sample_width / 32))
        # Stride (words per sample) rounded up to nearest power of two
        words_per_sample = 2 ** int(math.ceil(math.log2(words_per_sample)))

        # Read log in chunks of 1024 bytes
        full_log = bytearray()
        for i in range(0, sample_depth * words_per_sample * 4, 1024):
            full_log += self.fpga_read(self.base_address + LOG_BASE + i, 1024)

        log_length = sample_depth if log_write_wrapped else log_write_index
        log_start = log_write_index if log_write_wrapped else 0

        log = Log()
        log.signals = self.signals
        log.samples = []
        log.trigger_index = (log_length - 1) - num_post_trigger

        for i in range(log_length):
            offset = ((log_start + i) % sample_depth) * words_per_sample
            sample = 0
            for word_index in range(words_per_sample):
                byte_index = (offset + word_index) * 4
                word = int.from_bytes(full_log[byte_index : (byte_index + 4)], "little")
                sample |= word << (32 * word_index)

            values = [
                ((sample >> s.offset) & ((1 << s.width) - 1))
                for s in self.signals
            ]
            log.samples.append(tuple(values))

        return log

def pad_int(value: int, max_value: int) -> str:
    width = int(math.floor(math.log10(max_value))) + 1
    return str(value).rjust(width + 1, " ")

def pad_hex(value: int, max_value: int) -> str:
    width = int(math.floor(math.log(max_value, 16))) + 1
    return f"{value:#0{width + 2}x}"

def main() -> None:
    parser = argparse.ArgumentParser(prog="EmbeddedLogicAnalyzer")
    parser.add_argument("--serial", required=True, help="Path to serial device")
    parser.add_argument("--metadata", required=True, help="Path to ELA metadata JSON file")
    parser.add_argument("--address", type=lambda x: int(x, 0), help="Base address of ELA", default="0x3000_0000")
    parser.add_argument("--output", help="Path to output VCD file")
    parser.add_argument("--post", type=int, help="Number of post-trigger samples to collect", default=0)
    parser.add_argument("triggers", nargs="*", help="List of triggers: <signal>=<match0>[,<match1>]")
    args = parser.parse_args()

    metadata = json.load(open(args.metadata))
    signal_names = [s["name"] for s in metadata["signals"]]
    print("=== Signals ===")
    for signal in signal_names:
        print(signal)
    print()

    # Parse trigger specifiers
    triggers = []
    for spec in args.triggers:
        match = re.match(r"([^=]+)=(0x[a-fA-F0-9]+|[0-9]+)(?:,(0x[a-fA-F0-9]+|[0-9]+))?", spec)
        if not match:
            print(f"Error: invalid trigger specifier \"{spec}\"")
            return
        signal, match0, match1 = match.groups()
        if match1:
            # Makes more sense to specify them in the order you see them.
            match0, match1 = match1, match0
        if match0:
            match0 = int(match0, 0)
        if match1:
            match1 = int(match1, 0)

        signal_index = None
        try:
            signal_index = signal_names.index(signal)
        except ValueError:
            print(f"Error: unknown signal \"{signal}\"")
            return
        
        triggers.append((signal_index, match0, match1))

    # Instantiate EmbeddedLogicAnalyzer
    serial = Serial(args.serial)
    ela = EmbeddedLogicAnalyzer(serial, args.address, metadata)

    # Configure and arm
    for i in range(len(ela.signals)):
        ela.unset_signal_trigger(i)
    for (signal, match0, match1) in triggers:
        ela.set_signal_trigger(signal, match0, match1)
    ela.set_post_trigger_samples(args.post)
    ela.arm()

    if len(triggers) == 0:
        print("No triggers specified, forcing trigger")
        ela.force_trigger()

    try:
        print("Waiting for trigger", end="")
        while not ela.get_is_triggered():
            print(".", end="", flush=True)
            time.sleep(0.2)
        print()

        log = ela.read_log()
        if log is None:
            print("Waiting for recording to finish...")
            while log is None:
                time.sleep(0.2)
                log = ela.read_log()
        print()
    except KeyboardInterrupt:
        return

    if args.output:
        with open(args.output, "w") as f:
            log.write_vcd(f)
    else:
        log.print()

if __name__ == '__main__':
    main()
