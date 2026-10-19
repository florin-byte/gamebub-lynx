use super::GameboyError;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum MbcType {
    None = 0,
    Mbc1 = 1,
    Mbc2 = 2,
    Mbc3 = 3,
    Mbc5 = 4,
    Mbc7 = 5,
}

#[derive(Debug, Clone)]
#[allow(unused)]
pub struct RomHeader {
    pub mbc: MbcType,
    pub rom_size: u32,
    pub ram_size: u32,

    pub has_ram: bool,
    pub has_battery: bool,
    pub has_rtc: bool,
    pub has_rumble: bool,
    pub has_sensor: bool,
    /// MBC1M multicart (detected from the ROM contents, not the header).
    pub multicart: bool,
}

impl RomHeader {
    pub fn parse(header: [u8; 0x150]) -> Result<RomHeader, GameboyError> {
        macro_rules! cart_type {
            ($mbc:expr, $($field:ident),*) => {
                {
                    $(
                        $field = true;
                    )*
                    $mbc
                }
            }
        }

        let cartridge_type = header[0x147];
        let mut has_ram = false;
        let mut has_battery = false;
        let mut has_rtc = false;
        let mut has_rumble = false;
        let mut has_sensor = false;
        let mbc = match cartridge_type {
            0x00 => cart_type!(MbcType::None,),
            0x01 => cart_type!(MbcType::Mbc1,),
            0x02 => cart_type!(MbcType::Mbc1, has_ram),
            0x03 => cart_type!(MbcType::Mbc1, has_ram, has_battery),
            0x05 => cart_type!(MbcType::Mbc2, has_ram),
            0x06 => cart_type!(MbcType::Mbc2, has_ram, has_battery),
            0x08 => cart_type!(MbcType::None, has_ram),
            0x09 => cart_type!(MbcType::None, has_ram, has_battery),
            0x0F => cart_type!(MbcType::Mbc3, has_rtc, has_battery),
            0x10 => cart_type!(MbcType::Mbc3, has_rtc, has_ram, has_battery),
            0x11 => cart_type!(MbcType::Mbc3,),
            0x12 => cart_type!(MbcType::Mbc3, has_ram),
            0x13 => cart_type!(MbcType::Mbc3, has_ram, has_battery),
            0x19 => cart_type!(MbcType::Mbc5,),
            0x1A => cart_type!(MbcType::Mbc5, has_ram),
            0x1B => cart_type!(MbcType::Mbc5, has_ram, has_battery),
            0x1C => cart_type!(MbcType::Mbc5, has_rumble),
            0x1D => cart_type!(MbcType::Mbc5, has_rumble, has_ram),
            0x1E => cart_type!(MbcType::Mbc5, has_rumble, has_ram, has_battery),
            0x22 => cart_type!(MbcType::Mbc7, has_sensor, has_ram), // EEPROM, accelerometer
            _ => return Err(GameboyError::UnsupportedCartridgeType(cartridge_type)),
        };

        let rom_size = 32 * 1024 * (1 << header[0x148]);
        let ram_size = match header[0x149] {
            _ if mbc == MbcType::Mbc2 => 512,
            _ if mbc == MbcType::Mbc7 => 256,
            2 => 8 * 1024,
            3 => 32 * 1024,
            4 => 128 * 1024,
            5 => 64 * 1024,
            _ => 0,
        };

        Ok(RomHeader {
            mbc,
            rom_size,
            ram_size,
            has_ram,
            has_battery,
            has_rtc,
            has_rumble,
            has_sensor,
            multicart: false,
        })
    }

    pub fn as_emu_cart_config(&self) -> u32 {
        1 | ((self.mbc as u32) << 1)
            | ((self.has_ram as u32) << 4)
            | ((self.has_rtc as u32) << 5)
            | ((self.has_rumble as u32) << 6)
            | ((self.multicart as u32) << 7)
    }
}
