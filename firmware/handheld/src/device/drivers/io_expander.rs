#![allow(dead_code)]

use embedded_hal::i2c::I2c;
use thiserror::Error;

const ADDRESS: u8 = 0x20;

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
}

pub struct TCA9535<I2C: I2c> {
    i2c: I2C,
}

impl<I2C> TCA9535<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        TCA9535 { i2c }
    }

    /// Get the input pins.
    pub fn get_pins(&mut self) -> Result<[bool; 16], Error> {
        // Registers 0 and 1.
        let data = self.read_reg(0)?;
        Ok(u16_to_array(data))
    }

    /// Set the output pins.
    pub fn set_pins(&mut self, data: [bool; 16]) -> Result<(), Error> {
        // Registers 2 and 3.
        let data = array_to_u16(data);
        self.write_reg(2, data)
    }

    /// Set whether the pin polarities are inverted.
    pub fn set_pin_inversion(&mut self, data: [bool; 16]) -> Result<(), Error> {
        // Registers 4 and 5.
        let data = array_to_u16(data);
        self.write_reg(4, data)
    }

    /// Set directions of the ports. true for input, false for output.
    pub fn set_pin_directions(&mut self, data: [bool; 16]) -> Result<(), Error> {
        // Registers 6 and 7.
        let data = array_to_u16(data);
        self.write_reg(6, data)
    }

    fn write_reg(&mut self, reg: u8, data: u16) -> Result<(), Error> {
        let data = data.to_le_bytes();
        self.i2c
            .write(ADDRESS, &[reg, data[0], data[1]])
            .map_err(|_| Error::I2cError)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, Error> {
        let mut data = [0u8; 2];
        self.i2c
            .write_read(ADDRESS, &[reg], &mut data)
            .map_err(|_| Error::I2cError)?;
        Ok(u16::from_le_bytes(data))
    }
}

fn array_to_u16(data: [bool; 16]) -> u16 {
    data.into_iter()
        .enumerate()
        .map(|(i, x)| (x as u16) << i)
        .reduce(|a, b| a | b)
        .unwrap()
}

fn u16_to_array(data: u16) -> [bool; 16] {
    std::array::from_fn(|i| ((data >> i) & 1) == 1)
}
