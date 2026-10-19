import os
import sys
import tempfile
import subprocess

PARTITION_NAME: str = "system_data"

def parse_table_number(number: str) -> int:
    multiplier = 1
    if number.endswith("M"):
        multiplier = 1024 * 1024
        number = number[:-1]
    elif number.endswith("K"):
        multiplier = 1024
        number = number[:-1]
    return multiplier * int(number, base=0)


def main() -> None:
    esp_idf_path = os.environ.get("IDF_PATH")
    if not esp_idf_path:
        print("IDF_PATH not set: source the esp-idf export.sh")
        sys.exit(1)
    
    if len(sys.argv) != 2:
        print("usage: {sys.argv[0]} path/to/directory")
        
    directory = sys.argv[1]
    if directory.endswith("/"):
        directory = directory[:-1]

    partition_offset = None
    partition_size = None
    with open("partitions.csv") as f:
        for line in f:
            entry = [x.strip() for x in line.split(",")]
            if entry[0] == PARTITION_NAME:
                partition_offset = parse_table_number(entry[3])
                partition_size = parse_table_number(entry[4])
                print(f"Found partition in table: offset={hex(partition_offset)} size={hex(partition_size)}")
    if partition_offset is None:
        print("Couldn't find partition in partition table")
        sys.exit(1)

    out_file = tempfile.NamedTemporaryFile(delete_on_close=False, suffix=".fat.bin")
    out_file.close()

    script_path = os.path.join(esp_idf_path, "components/fatfs/fatfsgen.py")
    subprocess.run([
        script_path,
        "--output", out_file.name,
        "--partition_size", str(partition_size),
        "--fat_count", "1",
        "--long_name_support",
        directory
    ], check=True)

    print("Flashing...")
    subprocess.run(["espflash", "write-bin", str(partition_offset), out_file.name])


if __name__ == "__main__":
    main()
