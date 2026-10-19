use std::fmt::Display;

const REG_HARDWARE_VERSION: u32 = 0;
const REG_SERIAL_NUMBER: u32 = 2;

/// Read an efuse register from EFUSE_BLK_USER_DATA
fn read_efuse(index: u32) -> u32 {
    assert!(index < 8);
    unsafe {
        esp_idf_svc::sys::esp_efuse_read_reg(
            esp_idf_svc::sys::esp_efuse_block_t_EFUSE_BLK_USER_DATA,
            index,
        )
    }
}

#[derive(Copy, Clone)]
pub struct SerialNumber(pub u32);

impl Display for SerialNumber {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{:08X}", self.0)
    }
}

#[derive(Clone, Copy, Debug)]
#[allow(unused)]
pub struct HardwareVersion {
    pub product: u8,
    pub major: u8,
    pub minor: u8,
    pub variant: u8,
}

impl HardwareVersion {
    pub fn as_u32(self) -> u32 {
        (self.variant as u32)
            | ((self.minor as u32) << 8)
            | ((self.major as u32) << 16)
            | ((self.product as u32) << 24)
    }
}

impl Display for HardwareVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(
            f,
            "{}.{}.{}.{}",
            self.product, self.major, self.minor, self.variant
        )
    }
}

pub fn get_serial_number() -> SerialNumber {
    SerialNumber(read_efuse(REG_SERIAL_NUMBER))
}

pub fn get_hardware_version() -> HardwareVersion {
    let value = read_efuse(REG_HARDWARE_VERSION);
    HardwareVersion {
        product: ((value >> 24) & 0xFF) as u8,
        major: ((value >> 16) & 0xFF) as u8,
        minor: ((value >> 8) & 0xFF) as u8,
        variant: ((value >> 0) & 0xFF) as u8,
    }
}
