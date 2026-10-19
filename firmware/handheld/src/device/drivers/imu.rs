#![allow(dead_code)]

use thiserror::Error;

use embedded_hal::i2c::I2c;

/// One 'g', in m/s^2.
pub const G_ACCEL: f32 = 9.80665;

const ADDRESS: u8 = 0x6A;

const REG_WHO_AM_I: u8 = 0x0F;
const REG_CTRL1_XL: u8 = 0x10;
const REG_CTRL2_G: u8 = 0x11;
const REG_CTRL3_C: u8 = 0x12;
const REG_OUTX_L_G: u8 = 0x22;
const REG_OUTX_L_XL: u8 = 0x28;

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
    #[error("incorrect chip id")]
    ChipId,
}

pub struct LSM6DS3TRC<I2C> {
    i2c: I2C,
}

impl<I2C> LSM6DS3TRC<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        LSM6DS3TRC { i2c }
    }

    fn reset(&mut self) -> Result<(), Error> {
        // Software reset: write bit 0 of CTRL3_C, wait for it to be reset.
        self.i2c
            .write(ADDRESS, &[REG_CTRL3_C, 0x01])
            .map_err(|_| Error::I2cError)?;
        loop {
            let mut value = 0u8;
            self.i2c
                .write_read(ADDRESS, &[REG_CTRL3_C], std::slice::from_mut(&mut value))
                .map_err(|_| Error::I2cError)?;
            if value & 1 == 0 {
                break Ok(());
            }
        }
    }

    pub fn init(&mut self) -> Result<(), Error> {
        // Check chip ID.
        let mut chip_id = 0u8;
        self.i2c
            .write_read(ADDRESS, &[REG_WHO_AM_I], std::slice::from_mut(&mut chip_id))
            .map_err(|_| Error::I2cError)?;
        if chip_id != 0x6A {
            return Err(Error::ChipId);
        }

        self.reset()?;

        // Bit 6: block data update
        // Bit 4: INT open-drain
        // Bit 2: auto-increment address
        self.i2c
            .write(ADDRESS, &[REG_CTRL3_C, 0x54])
            .map_err(|_| Error::I2cError)?;

        Ok(())
    }

    /// Enable the accelerometer.
    /// Currently hard-coded to 104Hz and +/- 4G range.
    pub fn enable_accel(&mut self) -> Result<(), Error> {
        self.i2c
            .write(ADDRESS, &[REG_CTRL1_XL, 0x48])
            .map_err(|_| Error::I2cError)
    }

    /// Disable the accelerometer.
    pub fn disable_accel(&mut self) -> Result<(), Error> {
        self.i2c
            .write(ADDRESS, &[REG_CTRL1_XL, 0])
            .map_err(|_| Error::I2cError)
    }

    fn convert_accel_sample(&self, data: &[u8]) -> f32 {
        let raw = i16::from_le_bytes((&data[0..2]).try_into().unwrap());
        // Assume +/- 4G
        (raw as f32) * (4.0 / 32768.0)
    }

    /// Enable the gyroscope.
    /// Currently hard-coded to 104Hz and 1000 dps range.
    pub fn enable_gyro(&mut self) -> Result<(), Error> {
        self.i2c
            .write(ADDRESS, &[REG_CTRL2_G, 0x48])
            .map_err(|_| Error::I2cError)
    }

    /// Disable the gyro.
    pub fn disable_gyro(&mut self) -> Result<(), Error> {
        self.i2c
            .write(ADDRESS, &[REG_CTRL2_G, 0])
            .map_err(|_| Error::I2cError)
    }

    fn convert_gyro_sample(&self, data: &[u8]) -> f32 {
        let raw = i16::from_le_bytes((&data[0..2]).try_into().unwrap());
        // Assume +/- 1000dps
        (raw as f32) * (1000.0 / 32768.0)
    }

    /// Read a sample from the accelerometer.
    pub fn read_accel(&mut self) -> Result<AccelerometerSample, Error> {
        let mut data = [0u8; 6];
        self.i2c
            .write_read(ADDRESS, &[REG_OUTX_L_XL], &mut data)
            .map_err(|_| Error::I2cError)?;

        // X, Y, then Z. Each is 16-bit little-endian signed integer.
        // Due to the rotation of the chip, X and Y should be swapped
        Ok(AccelerometerSample {
            y: self.convert_accel_sample(&data[0..2]),
            x: self.convert_accel_sample(&data[2..4]),
            z: self.convert_accel_sample(&data[4..6]),
        })
    }

    /// Read a sample from the gyroscope.
    pub fn read_gyro(&mut self) -> Result<GyroscopeSample, Error> {
        let mut data = [0u8; 6];
        self.i2c
            .write_read(ADDRESS, &[REG_OUTX_L_G], &mut data)
            .map_err(|_| Error::I2cError)?;

        // X, Y, then Z. Each is 16-bit little-endian signed integer.
        Ok(GyroscopeSample {
            x: self.convert_gyro_sample(&data[0..2]),
            y: self.convert_gyro_sample(&data[2..4]),
            z: self.convert_gyro_sample(&data[4..6]),
        })
    }
}

#[derive(Debug, Clone)]
pub struct AccelerometerSample {
    /// X acceleration, in 'g's
    pub x: f32,
    /// Y acceleration, in 'g's
    pub y: f32,
    /// Z acceleration, in 'g's
    pub z: f32,
}

#[derive(Debug, Clone)]
pub struct GyroscopeSample {
    /// X rotation, in degrees / second
    pub x: f32,
    /// Y rotation, in degrees / second
    pub y: f32,
    /// Z rotation, in degrees / second
    pub z: f32,
}
