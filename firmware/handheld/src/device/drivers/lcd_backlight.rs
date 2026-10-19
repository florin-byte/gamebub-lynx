use esp_idf_svc::hal::{ledc::LedcDriver, units::Hertz};

pub struct PwmConfig {
    /// PWM frequency
    pub frequency: Hertz,
    /// PWM resolution
    pub resolution: esp_idf_svc::hal::ledc::config::Resolution,
    /// The minimum visible duty cycle
    pub min_duty: f32,
    /// LED gamma correction
    pub gamma: f32,
}

/// LCD backlight controller
pub struct PwmBacklight<'a> {
    config: PwmConfig,
    driver: LedcDriver<'a>,
    enabled: bool,
    brightness: f32,
}

impl<'a> PwmBacklight<'a> {
    pub fn new(config: PwmConfig, driver: LedcDriver<'a>) -> Self {
        PwmBacklight {
            config,
            driver,
            enabled: false,
            brightness: 0.0,
        }
    }

    pub fn init(&mut self) {
        self.update();
    }

    /// Set whether the backlight is enabled or disabled.
    ///
    /// Brightness percentage is maintained regardless.
    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        self.update();
    }

    /// Set the brightness level (from 0 to 1)
    pub fn set_brightness(&mut self, brightness: f32) {
        self.brightness = brightness;
        self.update();
    }

    fn update(&mut self) {
        let duty = if self.enabled {
            // Brightness is perceived non-linearly: use a gamma correction for duty cycle.
            // Also, the minimum visible duty cycle (which we want 0.0 to map to)
            // varies depending on the display.
            let max_duty = self.driver.get_max_duty() as f32;
            ((max_duty * (1.0 - self.config.min_duty) * self.brightness.powf(self.config.gamma))
                + (self.config.min_duty * max_duty)) as u32
        } else {
            0
        };
        log::info!(
            "Setting LCD backlight to {} ({} / {})",
            self.brightness,
            duty,
            self.driver.get_max_duty(),
        );
        self.driver.set_duty(duty).unwrap();
    }
}
