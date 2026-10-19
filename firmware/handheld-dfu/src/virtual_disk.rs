use alloc::vec;
use ghostfat::GhostFat;
use usbd_scsi::BlockDevice as _;

use crate::usb_class::msc::BlockDevice;
use crate::{flash::Flash, uf2};

pub const DISK_BLOCK_SIZE: u32 = 512;
pub const DISK_SIZE: u32 = 64 * 1024 * 1024;
pub const FLASH_BLOCK_SIZE: u32 = 256;
pub const FLASH_SIZE: u32 = 16 * 1024 * 1024;
pub const FLASH_SECTOR_SIZE: u32 = 64 * 1024;

pub const FAMILY_ID: u32 = 0xC47E5767; // ESP32-S3
pub const EXT_DEVICE_TYPE: u32 = 0xC8A729;

const FLASH_BASE_ADDR: u32 = 0;
/// Flash below this offset is write-protected.
const FLASH_PROTECT_OFFSET: u32 = 0x8_0000;

struct Transfer {
    blocks_transferred: u32,
    total_blocks: u32,
    complete: bool,
}

pub struct Uf2VirtualDisk {
    flash: &'static Flash,

    fat: GhostFat<'static>,

    /// Hardware version of the device
    hw_version: crate::info::HardwareVersion,

    /// Bitmap for written blocks.
    block_map: [u8; (FLASH_SIZE / FLASH_BLOCK_SIZE / u8::BITS) as usize],

    /// Bitmap for erased sectors.
    sector_map: [u8; (FLASH_SIZE / FLASH_SECTOR_SIZE / u8::BITS) as usize],

    /// Current transfer
    transfer: Option<Transfer>,
}

fn get_bit(map: &[u8], bit: usize) -> bool {
    ((map[bit / 8] >> (bit % 8)) & 1) != 0
}

fn set_bit(map: &mut [u8], bit: usize) {
    map[bit / 8] |= 1 << (bit % 8);
}

impl Uf2VirtualDisk {
    pub const BLOCK_SIZE: u32 = DISK_BLOCK_SIZE;

    pub fn new(flash: &'static Flash) -> Self {
        let serial = crate::info::SerialNumber::get();
        let hw = crate::info::HardwareVersion::get();
        let fw = env!("CARGO_PKG_VERSION");
        let info = alloc::format!(
            "Game Bub DFU {fw}\nModel: Game Bub Handheld\nBoard-ID: Game_Bub-Handheld-{hw}\nHardware Version: {hw}\nSerial: {serial}"
        );
        let info = info.leak().as_bytes();

        let files = vec![
            ghostfat::File::<512>::new(
                "INFO_UF2.txt",
                info,
            ).unwrap(),
            ghostfat::File::<512>::new(
                "INDEX.HTM",
                b"<html><head><meta http-equiv=\"refresh\" content=\"0;URL='https://docs.gamebub.net/?from=handheld-dfu'\"/></head><body>Redirecting to the <a href='https://docs.gamebub.net/'>Game Bub Documentation</a></body></html>",
            ).unwrap(),
        ];
        let mut config = ghostfat::Config::default();
        config.volume_label = "GAME BUB";
        config.num_blocks = DISK_SIZE / DISK_BLOCK_SIZE;
        let fat = ghostfat::GhostFat::new(files.leak(), config);

        Self {
            flash,
            fat,
            hw_version: hw,
            block_map: [0u8; _],
            sector_map: [0u8; _],
            transfer: None,
        }
    }

    fn begin_transfer(&mut self, total_blocks: u32) {
        log::info!("Beginning UF2 load blocks={total_blocks}");
        self.transfer = Some(Transfer {
            blocks_transferred: 0,
            total_blocks,
            complete: false,
        });
        self.block_map.fill(0);
        self.sector_map.fill(0);
    }

    fn handle_uf2_write(&mut self, data: &[u8; 512]) {
        if self.transfer.as_ref().map(|t| t.complete).unwrap_or(false) {
            // Already rebooting, ignore.
            return;
        }

        // Validate block.
        let block = match uf2::Uf2Block::from_bytes(data) {
            Ok(b) => b,
            Err(_) => {
                // Ignore non-UF2 block.
                return;
            }
        };
        if block.family_id() != FAMILY_ID {
            // Ignore blocks that aren't targeted here.
            return;
        }
        if block.payload().len() != FLASH_BLOCK_SIZE as usize {
            return;
        }
        if block.address() < FLASH_BASE_ADDR || block.address() >= (FLASH_BASE_ADDR + FLASH_SIZE) {
            return;
        }
        if block.address() % FLASH_BLOCK_SIZE != 0 {
            return;
        }
        if let Some(tag) = block.get_extension_tag(EXT_DEVICE_TYPE) {
            if tag.len() != 8 {
                return;
            }
            if &tag[0..4] != b"bub!" {
                return;
            }
            if self.hw_version.as_u32() != 0
                && self.hw_version.major() != 0
                && self.hw_version.major() != 255
            {
                if tag[6] != self.hw_version.major() || tag[7] != self.hw_version.product() {
                    return;
                }
            }
        }

        // Possibly begin a new transfer.
        if let Some(transfer) = self.transfer.as_mut() {
            if transfer.total_blocks != block.total_blocks() {
                self.begin_transfer(block.total_blocks());
            }
        } else {
            // TODO validate address
            // TODO validate number of blocks
            self.begin_transfer(block.total_blocks());
        }

        let transfer = self.transfer.as_mut().unwrap();
        if block.block_number() >= transfer.total_blocks {
            log::warn!("Invalid block number");
            return;
        }

        // Check if the block has been transferred already.
        if get_bit(&self.block_map, block.block_number() as usize) {
            return;
        }

        let flash_address = block.address() - FLASH_BASE_ADDR;
        if flash_address < FLASH_PROTECT_OFFSET {
            // Do not write over write-protected areas.
            log::warn!("Refusing write protected block");
            return;
        }

        // Erase the sector if needed.
        let sector_index = flash_address / FLASH_SECTOR_SIZE;
        if !get_bit(&self.sector_map, sector_index as usize) {
            let result = self.flash.erase(
                sector_index * FLASH_SECTOR_SIZE,
                (sector_index + 1) * FLASH_SECTOR_SIZE,
            );
            if let Err(e) = result {
                log::error!("Flash erase error {:?}", e);
                return;
            }
            set_bit(&mut self.sector_map, sector_index as usize);
        }

        // Write the payload.
        let result = self.flash.write(block.address(), block.payload());
        if let Err(e) = result {
            log::error!("Flash write error {:?}", e);
            return;
        }
        set_bit(&mut self.block_map, block.block_number() as usize);

        transfer.blocks_transferred += 1;
        if transfer.blocks_transferred == transfer.total_blocks {
            log::info!("UF2 complete, rebooting");
            transfer.complete = true;
            crate::reboot::reboot();
        }
    }
}

impl BlockDevice for Uf2VirtualDisk {
    type Error = usbd_scsi::BlockDeviceError;

    fn block_size(&self) -> u32 {
        DISK_BLOCK_SIZE
    }

    fn block_count(&self) -> u32 {
        self.fat.max_lba() + 1
    }

    fn read_block(&mut self, lba: u32, buf: &mut [u8]) -> Result<(), Self::Error> {
        self.fat.read_block(lba, buf)
    }

    fn write_block(&mut self, _lba: u32, data: &[u8]) -> Result<(), Self::Error> {
        match data.try_into() {
            Ok(x) => {
                self.handle_uf2_write(x);
                Ok(())
            }
            Err(_) => Ok(()),
        }
    }

    fn flush(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}
