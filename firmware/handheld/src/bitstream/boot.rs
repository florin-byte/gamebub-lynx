use std::{fs::File, io::Read};

use esp_idf_svc::hal::units::Hertz;

use crate::device::{drivers::fpga, Device};

pub const REG_LOGO_BASE: u32 = 0x0000_0000;
pub const REG_LOGO_ANIM: u32 = REG_LOGO_BASE | 0x0;
pub const REG_LOGO_Y: u32 = REG_LOGO_BASE | 0x4;
/// Colors of the logo animation, 0xRRGGBB (boot designs without them ignore the writes):
/// background, logo face, logo shadow, and the brightest color of the glow across the face.
pub const REG_LOGO_BG: u32 = REG_LOGO_BASE | 0x8;
pub const REG_LOGO_FACE: u32 = REG_LOGO_BASE | 0xC;
pub const REG_LOGO_SHADOW: u32 = REG_LOGO_BASE | 0x10;
pub const REG_LOGO_GLOW: u32 = REG_LOGO_BASE | 0x14;

pub const REG_BASE: u32 = 0x0400_0000;
pub const MEM_BASE: u32 = 0x0500_0000;
pub const FIFO_BASE: u32 = 0x0600_0000;

/// Load a file to the internal soft CPU memory.
pub fn load_cpu_memory(
    device: &mut Device,
    address: u32,
    mut file: File,
) -> Result<(), std::io::Error> {
    let mut scratch = super::SCRATCH.take().unwrap();

    let mut address = MEM_BASE + address;
    loop {
        scratch.fill(0);
        let n = file.read(&mut scratch)?;
        if n == 0 {
            return Ok(());
        }

        // Round n up to multiple of 4
        let n = (n + 3) & !3;

        let command = fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits32);
        // 32 bits per transfer, 2 clocks each.
        let max_clock = Hertz(8 * 1024 * 1024) * 32 / (4 * 2);
        device
            .fpga
            .spi_write(Some(max_clock), command, address, &scratch[..n])
            .unwrap();

        address += n as u32;
    }
}
