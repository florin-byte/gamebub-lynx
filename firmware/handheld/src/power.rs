use std::{
    sync::{LazyLock, Mutex, MutexGuard},
    time::Duration,
};

use esp_idf_svc::timer::{EspTaskTimerService, EspTimer};

use crate::{
    device::Device,
    led::{self, LedBehavior},
    ui,
};

static POWER_MANAGER: LazyLock<Mutex<PowerManager>> =
    LazyLock::new(|| Mutex::new(PowerManager::new()));

const MONITOR_INTERVAL: Duration = Duration::from_secs(30);
const CUTOFF_VOLTAGE: f32 = 3.3;

pub struct PowerManager {
    monitor_timer: Option<EspTimer<'static>>,
}

impl PowerManager {
    fn new() -> Self {
        PowerManager {
            monitor_timer: None,
        }
    }

    pub fn lock() -> MutexGuard<'static, Self> {
        POWER_MANAGER.lock().unwrap()
    }

    /// Poll the power state now (e.g. after USB power was connected or removed).
    pub fn refresh(&mut self, device: &mut Device) {
        self.update(device);
    }

    fn update(&mut self, device: &mut Device) {
        let battery_level = device.fuel_gauge.get_battery_level().ok();
        let vbus = device.get_vbus_pgood();
        let battery_voltage = device.fuel_gauge.get_battery_voltage().ok();

        if let Some(battery_voltage) = battery_voltage {
            if battery_voltage <= CUTOFF_VOLTAGE && !vbus {
                log::warn!("Battery critically low, powering off");

                led::LedController::set_behavior(LedBehavior::BATTERY_CRITICAL);
                crate::core::CoreManager::lock().prepare_for_power_off();
                device.power_off();
            }
        }

        ui::send(ui::Message::BatteryStatus {
            level: battery_level.unwrap_or(0.),
            // No charging without USB power: qualify the charger status pin with VBUS so
            // the indicator clears as soon as the cable is removed.
            is_charging: vbus && device.get_battery_is_charging(),
        });
    }

    /// Start periodically polling power state.
    pub fn start(device: &mut Device) {
        let mut manager = PowerManager::lock();
        assert!(manager.monitor_timer.is_none());
        let timer_service = EspTaskTimerService::new().unwrap();
        let timer = timer_service
            .timer(move || {
                PowerManager::lock().update(&mut Device::lock());
            })
            .unwrap();
        timer.every(MONITOR_INTERVAL).unwrap();
        manager.monitor_timer = Some(timer);
        manager.update(device);
    }
}
