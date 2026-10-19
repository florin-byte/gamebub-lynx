use core::fmt::{Display, Formatter};

use esp_bootloader_esp_idf::EspAppDesc;
use esp_hal::efuse;

use crate::flash::Flash;

#[derive(Copy, Clone)]
pub struct SerialNumber(pub u32);

impl SerialNumber {
    pub fn get() -> SerialNumber {
        SerialNumber(get_user_data(2))
    }
}

impl Display for SerialNumber {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{:08X}", self.0)
    }
}

#[derive(Copy, Clone)]
pub struct HardwareVersion(u32);

impl HardwareVersion {
    pub fn get() -> HardwareVersion {
        HardwareVersion(get_user_data(0))
    }

    pub fn as_u32(&self) -> u32 {
        self.0
    }

    pub fn product(self) -> u8 {
        ((self.0 >> 24) & 0xFF) as u8
    }

    pub fn major(self) -> u8 {
        ((self.0 >> 16) & 0xFF) as u8
    }
}

impl Display for HardwareVersion {
    fn fmt(&self, f: &mut alloc::fmt::Formatter) -> alloc::fmt::Result {
        let variant = self.0 & 0xFF;
        let minor = (self.0 >> 8) & 0xFF;
        let major = (self.0 >> 16) & 0xFF;
        let product = (self.0 >> 24) & 0xFF;
        write!(f, "{product}.{major}.{minor}.{variant}")
    }
}

fn get_user_data(index: usize) -> u32 {
    efuse::read_field_le::<[u32; 6]>(efuse::BLOCK_USR_DATA)[index]
}

#[repr(C)]
pub struct FirmwareMetadata {
    pub commit_hash: [u8; 20],
    pub version_major: u8,
    pub version_minor: u8,
    pub version_patch: u8,
    pub version_pre: u8,
}

pub fn read_fw_metadata(flash: &Flash) -> Option<FirmwareMetadata> {
    // TODO: use partition table to get offset
    const OFFSET_IMAGE: u32 = 0x100000;
    const OFFSET_APP_DESC: u32 = OFFSET_IMAGE + 0x20;
    const OFFSET_CUSTOM_DESC: u32 = OFFSET_APP_DESC + 256;

    let mut bytes = [0u8; 256];
    flash.read(OFFSET_APP_DESC, &mut bytes).ok()?;
    let app_desc: EspAppDesc = unsafe { core::mem::transmute(bytes) };
    if app_desc.magic_word() != 0xABCD5432 {
        return None;
    }

    let mut bytes = [0u8; core::mem::size_of::<FirmwareMetadata>()];
    flash.read(OFFSET_CUSTOM_DESC, &mut bytes).ok()?;
    let custom_desc: FirmwareMetadata = unsafe { core::mem::transmute(bytes) };
    Some(custom_desc)
}
