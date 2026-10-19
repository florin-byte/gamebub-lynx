use thiserror::Error;

use embedded_hal::digital::OutputPin;

#[derive(Debug, Error)]
pub enum Error {
    #[error("enable error")]
    EnableError,
}

/// Driver for ST7262 LCD controller (without any control lines connected)
pub struct ST7262<PinEnable: OutputPin> {
    pin_enable: PinEnable,
}

impl<PinEnable> ST7262<PinEnable>
where
    PinEnable: OutputPin,
{
    pub fn new(pin_enable: PinEnable) -> Self {
        ST7262 { pin_enable }
    }

    pub fn init(&mut self) -> Result<(), Error> {
        self.enter_sleep()
    }

    pub fn enter_sleep(&mut self) -> Result<(), Error> {
        self.pin_enable.set_low().map_err(|_| Error::EnableError)?;
        Ok(())
    }

    pub fn exit_sleep(&mut self) -> Result<(), Error> {
        self.pin_enable.set_high().map_err(|_| Error::EnableError)?;
        Ok(())
    }

    /// Set the LCD to be controlled by the FPGA.
    pub fn enable_fpga_control(&mut self) -> Result<(), Error> {
        self.exit_sleep()
    }
}
