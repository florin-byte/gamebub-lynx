use esp_idf_svc::hal::units::Hertz;

use crate::device::{drivers::fpga, Device};

// From mGBA
pub static PALETTES: &[DmgPalette] = &[
    // Grayscale
    DmgPalette::from_simple([0x0000, 0x294A, 0x56B5, 0x7FFF]),
    // DMG Green"
    DmgPalette::from_simple([0x0CA1, 0x1504, 0x25A6, 0x4689]),
    // GB Pocket"
    DmgPalette::from_simple([0x0CA4, 0x258A, 0x4270, 0x52D4]),
];

#[derive(Copy, Clone)]
pub struct DmgPalette {
    obj0: [DmgPaletteColor; 4],
    obj1: [DmgPaletteColor; 4],
    bg: [DmgPaletteColor; 4],
    window: [DmgPaletteColor; 4],
    off: DmgPaletteColor,
}

impl DmgPalette {
    pub const fn from_simple(simple: [u16; 4]) -> Self {
        let simple = [
            DmgPaletteColor(simple[0]),
            DmgPaletteColor(simple[1]),
            DmgPaletteColor(simple[2]),
            DmgPaletteColor(simple[3]),
        ];
        DmgPalette {
            obj0: simple,
            obj1: simple,
            bg: simple,
            window: simple,
            off: simple[3],
        }
    }

    pub fn load(&self, device: &mut Device) -> Result<(), super::GameboyError> {
        fn copy(dest: &mut [u8], source: &[DmgPaletteColor; 4]) {
            for (i, entry) in source.iter().enumerate() {
                let bytes = entry.0.to_le_bytes();
                dest[i * 2 + 0] = bytes[0];
                dest[i * 2 + 1] = bytes[1];
            }
        }
        let mut data = [0u8; 4 * 4 * 2];
        copy(&mut data[0..8], &self.obj0);
        copy(&mut data[8..16], &self.obj1);
        copy(&mut data[16..24], &self.bg);
        copy(&mut data[24..32], &self.window);

        let address = super::DMG_PALETTE_BASE;
        let command = fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits16);
        let max_clock = Hertz(10_000_000);
        device
            .fpga
            .spi_write(Some(max_clock), command, address, &data)?;
        device
            .fpga
            .write_u32(super::REG_DMG_PALETTE_OFF, self.off.0 as u32)?;
        Ok(())
    }
}

#[derive(Copy, Clone)]
#[repr(transparent)]
pub struct DmgPaletteColor(u16);

impl DmgPaletteColor {
    #[allow(unused)]
    pub fn from_rgb888(rgb: u32) -> Self {
        let r = ((rgb >> 16) & 0xFF) >> 3;
        let g = ((rgb >> 8) & 0xFF) >> 3;
        let b = ((rgb >> 0) & 0xFF) >> 3;
        Self(((r << 10) | (g << 5) | b) as u16)
    }
}
