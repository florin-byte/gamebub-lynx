use std::sync::mpsc;
use std::sync::OnceLock;
use std::time::Duration;
use std::time::Instant;

use embedded_hal::pwm::SetDutyCycle;
use esp_idf_svc::hal::ledc::LedcDriver;

static SENDER: OnceLock<mpsc::Sender<LedBehavior>> = OnceLock::new();

pub type LedDriver = LedcDriver<'static>;

#[derive(Copy, Clone, Debug)]
pub struct LedColor(u8);

impl LedColor {
    pub const fn off() -> Self {
        LedColor(0)
    }

    pub const fn on() -> Self {
        LedColor(255)
    }

    pub fn scale(self, by: f32) -> Self {
        let value = ((self.0 as f32) * by).clamp(0.0, 255.0) as u8;
        LedColor(value)
    }
}

#[allow(unused)]
#[derive(Copy, Clone, Debug)]
pub enum LedPattern {
    Off,
    Solid,
    Blink,
    Breathe,
}

#[derive(Copy, Clone, Debug)]
pub struct LedBehavior {
    pattern: LedPattern,
    color: LedColor,
    period: Duration,
    repeat: Option<u32>,
}

#[allow(unused)]
impl LedBehavior {
    /// Off
    pub const OFF: Self = Self::off();
    /// Loading bitstream: 400ms breathe
    pub const LOADING: Self = Self::breathe(Duration::from_millis(400), None);
    /// Critically low battery: blink 3 times
    pub const BATTERY_CRITICAL: Self = Self::blink(Duration::from_millis(500), Some(3));

    pub const fn off() -> Self {
        LedBehavior {
            pattern: LedPattern::Off,
            color: LedColor::off(),
            period: Duration::ZERO,
            repeat: None,
        }
    }

    pub const fn on() -> Self {
        LedBehavior {
            pattern: LedPattern::Solid,
            color: LedColor::on(),
            period: Duration::ZERO,
            repeat: None,
        }
    }

    pub const fn blink(period: Duration, repeat: Option<u32>) -> Self {
        LedBehavior {
            pattern: LedPattern::Blink,
            color: LedColor::on(),
            period,
            repeat,
        }
    }

    pub const fn breathe(period: Duration, repeat: Option<u32>) -> Self {
        LedBehavior {
            pattern: LedPattern::Breathe,
            color: LedColor::on(),
            period,
            repeat,
        }
    }

    fn continue_loop(&mut self) -> bool {
        match self.repeat {
            Some(x) => {
                if x == 0 {
                    false
                } else {
                    self.repeat = Some(x - 1);
                    true
                }
            }
            None => true,
        }
    }
}

pub struct LedController {
    led: LedDriver,
    receiver: mpsc::Receiver<LedBehavior>,
    behavior: Option<LedBehavior>,
}

impl LedController {
    pub fn start(led: LedDriver) {
        let (sender, receiver) = mpsc::channel::<LedBehavior>();
        SENDER.set(sender).expect("LED already initialized");

        let mut controller = LedController {
            led,
            receiver,
            behavior: None,
        };
        std::thread::Builder::new()
            .name("led".to_string())
            .stack_size(3 * 1024)
            .spawn(move || controller.run())
            .unwrap();
    }

    pub fn set_behavior(behavior: LedBehavior) {
        SENDER.get().unwrap().send(behavior).unwrap();
    }

    fn set_color(&mut self, color: LedColor) {
        self.led
            .set_duty_cycle_fraction(color.0 as u16, u8::MAX as u16)
            .unwrap();
    }

    #[must_use]
    fn sleep(&mut self, duration: Duration) -> Option<()> {
        match self.receiver.recv_timeout(duration) {
            Ok(message) => {
                self.behavior = Some(message);
                None
            }
            Err(_) => {
                // Successful sleep.
                Some(())
            }
        }
    }

    fn do_behavior(&mut self, mut behavior: LedBehavior) -> Option<()> {
        match behavior.pattern {
            LedPattern::Off => self.set_color(LedColor::off()),
            LedPattern::Solid => self.set_color(behavior.color),
            LedPattern::Blink => {
                let delay = Duration::from_millis((behavior.period.as_millis() as u64) / 2);
                while behavior.continue_loop() {
                    self.set_color(behavior.color);
                    self.sleep(delay)?;
                    self.set_color(LedColor::off());
                    self.sleep(delay)?;
                }
            }
            LedPattern::Breathe => {
                const UPDATE_RATE: Duration = Duration::from_millis(50);
                while behavior.continue_loop() {
                    let start = Instant::now();
                    loop {
                        let t = start.elapsed().as_secs_f32() / behavior.period.as_secs_f32();
                        if t >= 1.0 {
                            self.set_color(LedColor::off());
                            break;
                        }
                        let intensity = (-(t * std::f32::consts::TAU).cos() + 1.0) / 2.0;
                        self.set_color(behavior.color.scale(intensity));
                        self.sleep(UPDATE_RATE)?;
                    }
                }
            }
        }
        None
    }

    fn run(&mut self) -> ! {
        // Set higher than background threads.
        unsafe { esp_idf_svc::sys::vTaskPrioritySet(std::ptr::null_mut(), 10) };

        loop {
            let behavior = self.behavior.take();
            let behavior = behavior.unwrap_or_else(|| self.receiver.recv().unwrap());
            self.do_behavior(behavior);
        }
    }
}
