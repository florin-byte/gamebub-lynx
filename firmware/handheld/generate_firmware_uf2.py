import argparse
import os
import sys
import tempfile
import subprocess
import struct
from pathlib import Path

UF2_FAMILY_ESP32S3: int = 0xC47E5767
UF2_EXT_DEVICE_TYPE: int = 0xC8A729
UF2_EXT_FW_VERSION: int = 0x9FC7BC
UF2_EXT_COMMIT: int = 0x8A4E54


def pad_to_multiple(data: bytes, value: int) -> bytes:
    new_size = ((len(data) + value - 1) // value) * value
    return data.ljust(new_size, b"\0")


class PartitionTableEntry:
    name: str
    type: str
    subtype: str
    offset: int
    size: int

    @staticmethod
    def parse_table_number(number: str) -> int:
        multiplier = 1
        if number.endswith("M"):
            multiplier = 1024 * 1024
            number = number[:-1]
        elif number.endswith("K"):
            multiplier = 1024
            number = number[:-1]
        return multiplier * int(number, base=0)

    def __init__(self, line):
        name, type, subtype, offset_str, size_str, flags = [
            x.strip() for x in line.split(",")
        ]
        self.name = name
        self.type = type
        self.subtype = subtype
        self.offset = PartitionTableEntry.parse_table_number(offset_str)
        self.size = PartitionTableEntry.parse_table_number(size_str)
        self.flags = flags


class PartitionTable:
    def __init__(self, file):
        self.entries = []
        for line in file:
            if line.startswith("#"):
                continue
            self.entries.append(PartitionTableEntry(line))

    def find(self, name: str) -> PartitionTableEntry | None:
        for e in self.entries:
            if e.name == name:
                return e
        return None


class Uf2Builder:
    BLOCK_SIZE: int = 4096
    PAGE_SIZE: int = 256
    MAGIC_START_0: int = 0x0A324655
    MAGIC_START_1: int = 0x9E5D5157
    MAGIC_END: int = 0x0AB16F30

    def __init__(self):
        self.segments = []
        self.family_id = 0
        self.extension_tags = bytearray()
        self.max_extension_length = 476 - self.PAGE_SIZE - 4

    def add_flash_segment(self, offset: int, data: bytes) -> None:
        assert (offset % self.BLOCK_SIZE) == 0
        data = pad_to_multiple(data, self.BLOCK_SIZE)
        self.segments.append((offset, data))

    def add_extension_tag(self, tag: int, data: bytes) -> None:
        assert (tag & 0xFF000000) == 0
        assert len(data) < (256 - 4)
        header = struct.pack("<I", (tag << 8) | (4 + len(data)))
        data = pad_to_multiple(data, 4)
        assert (
            len(self.extension_tags) + len(header) + len(data)
        ) <= self.max_extension_length
        self.extension_tags.extend(header)
        self.extension_tags.extend(data)

    def build(self) -> bytearray:
        output = bytearray()

        block_index = 0
        num_blocks = 0
        for _, segment in self.segments:
            num_blocks += len(segment) // self.PAGE_SIZE

        flags = 0
        if self.family_id != 0:
            flags |= 0x00002000
        if self.extension_tags:
            flags |= 0x00008000

        for flash_offset, segment in self.segments:
            for i in range(0, len(segment), self.PAGE_SIZE):
                payload = bytearray(segment[i : (i + self.PAGE_SIZE)])
                payload.extend(self.extension_tags)
                payload = payload.ljust(476, b"\0")

                block = struct.pack(
                    "<IIIIIIII476sI",
                    self.MAGIC_START_0,
                    self.MAGIC_START_1,
                    flags,
                    flash_offset,
                    self.PAGE_SIZE,
                    block_index,
                    num_blocks,
                    self.family_id,
                    payload,
                    self.MAGIC_END,
                )
                assert len(block) == 512
                output.extend(block)
                flash_offset += self.PAGE_SIZE
                block_index += 1

        return output


def main() -> None:
    esp_idf_path = os.environ.get("IDF_PATH")
    if not esp_idf_path:
        print("IDF_PATH not set: source the esp-idf export.sh")
        sys.exit(1)

    parser = argparse.ArgumentParser()
    parser.add_argument("--firmware", type=Path)
    parser.add_argument("--system-data", type=Path)
    parser.add_argument("--hw-revision", type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    commit_hash = subprocess.check_output(["git", "rev-parse", "HEAD"]).strip()
    # little endian: [variant, minor, major, product]
    device_type = b"bub!" + bytes([0, 0, args.hw_revision, 1])

    bundle = Uf2Builder()
    bundle.family_id = UF2_FAMILY_ESP32S3
    bundle.add_extension_tag(UF2_EXT_DEVICE_TYPE, device_type)
    bundle.add_extension_tag(UF2_EXT_COMMIT, commit_hash)

    with open("partitions.csv") as f:
        partition_table = PartitionTable(f)
    app_partition = partition_table.find("factory")
    data_partition = partition_table.find("system_data")

    if app_partition is None:
        raise SystemExit(f"Couldn't find app partition in table")
    if data_partition is None:
        raise SystemExit(f"Couldn't find system data partition in table")

    # Add firmware
    out_file = tempfile.NamedTemporaryFile(delete_on_close=False, suffix=".app.bin")
    out_file.close()
    subprocess.run(
        [
            "espflash",
            "save-image",
            "--chip",
            "esp32s3",
            str(args.firmware),
            out_file.name,
        ],
        check=True,
    )
    with open(out_file.name, "rb") as f:
        firmware = f.read()
        if len(firmware) > app_partition.size:
            raise SystemExit("firmware overflows partition")
        bundle.add_flash_segment(app_partition.offset, firmware)

    # Generate the system data partition
    out_file = tempfile.NamedTemporaryFile(delete_on_close=False, suffix=".fat.bin")
    out_file.close()
    script_path = os.path.join(esp_idf_path, "components/fatfs/fatfsgen.py")
    subprocess.run(
        [
            script_path,
            "--output",
            out_file.name,
            "--partition_size",
            str(data_partition.size),
            "--fat_count",
            "1",
            "--long_name_support",
            str(args.system_data),
        ],
        check=True,
    )
    with open(out_file.name, "rb") as f:
        system_data = f.read()
        if len(system_data) > data_partition.size:
            raise SystemExit("system data overflows partition")
        bundle.add_flash_segment(data_partition.offset, system_data)

    # Save output
    with open(args.output, "wb") as f:
        f.write(bundle.build())


if __name__ == "__main__":
    main()
