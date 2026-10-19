#![allow(dead_code)]

use embedded_hal::i2c::I2c;
use enum_map::{enum_map, Enum, EnumMap};
use thiserror::Error;

const ADDRESS: u8 = 0x36;
const REG_VCELL: u8 = 0x02;
const REG_SOC: u8 = 0x04;
const REG_MODE: u8 = 0x06;
const REG_VERSION: u8 = 0x08;
const REG_HIBRT: u8 = 0x0A;
const REG_CONFIG: u8 = 0x0C;
const REG_VALRT: u8 = 0x14;
const REG_CRATE: u8 = 0x16;
const REG_VRESET_ID: u8 = 0x18;
const REG_STATUS: u8 = 0x1A;
const REG_TABLE: u8 = 0x40;
const REG_CMD: u8 = 0xFE;

#[derive(Debug, Error)]
pub enum Error {
    #[error("i2c error")]
    I2cError,
}

#[derive(Debug, Copy, Clone, Enum)]
pub enum Alert {
    Reset,
    VoltageHigh,
    VoltageLow,
    VoltageReset,
    ChargeLow,
    ChargeChange,
}

pub struct MAX17048<I2C: I2c> {
    i2c: I2C,

    /// Whether the SOC change alert is enabled.
    alert_soc_change: bool,
}

impl<I2C> MAX17048<I2C>
where
    I2C: I2c,
{
    pub fn new(i2c: I2C) -> Self {
        MAX17048 {
            i2c,
            alert_soc_change: false,
        }
    }

    /// Get the battery state of charge, in percent from 0 to 100.
    pub fn get_battery_level(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(REG_SOC)?;
        Ok(((raw as f32) / 256.0).clamp(0.0, 100.0))
    }

    /// Get the voltage of the battery, in volts.
    pub fn get_battery_voltage(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(REG_VCELL)?;
        Ok(((raw as f32) * 78.125) / 1_000_000.0)
    }

    /// Get the charge or discharge rate of the battery, in %/hour.
    pub fn get_battery_charge_rate(&mut self) -> Result<f32, Error> {
        let raw = self.read_reg(REG_CRATE)? as i16;
        Ok((raw as f32) * 0.208)
    }

    /// Get the battery current (unsupported, always returns NaN)
    pub fn get_battery_current(&mut self) -> Result<f32, Error> {
        Ok(f32::NAN)
    }

    /// Enable or disable the 1% state-of-charge change alert.
    pub fn set_alert_soc_change(&mut self, enabled: bool) -> Result<(), Error> {
        self.alert_soc_change = enabled;
        self.update_reg_config()
    }

    /// Query the status of alerts, clearing them.
    pub fn query_alerts(&mut self) -> Result<EnumMap<Alert, bool>, Error> {
        // Query alerts (first) and clear.
        let status = self.read_reg(REG_STATUS)?;
        self.write_reg(REG_STATUS, 0)?;
        // Acknowledge alert, clearing nALRT signal.
        self.update_reg_config()?;

        Ok(enum_map! {
            Alert::Reset => (status & (1 << 8)) != 0,
            Alert::VoltageHigh => (status & (1 << 9)) != 0,
            Alert::VoltageLow => (status & (1 << 10)) != 0,
            Alert::VoltageReset => (status & (1 << 11)) != 0,
            Alert::ChargeLow => (status & (1 << 12)) != 0,
            Alert::ChargeChange => (status & (1 << 13)) != 0,
        })
    }

    fn update_reg_config(&mut self) -> Result<(), Error> {
        let mut data: u16 = 0x971C; // Default value, also clears ALRT.
        if self.alert_soc_change {
            data |= 0x40;
        }
        self.write_reg(REG_CONFIG, data)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u16, Error> {
        let mut data = [0u8; 2];
        self.i2c
            .write_read(ADDRESS, &[reg], &mut data)
            .map_err(|_| Error::I2cError)?;
        Ok(u16::from_be_bytes(data))
    }

    fn write_reg(&mut self, reg: u8, data: u16) -> Result<(), Error> {
        let data = data.to_be_bytes();
        self.i2c
            .write(ADDRESS, &[reg, data[0], data[1]])
            .map_err(|_| Error::I2cError)
    }
}
