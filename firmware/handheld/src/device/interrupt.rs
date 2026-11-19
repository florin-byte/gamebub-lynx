use std::{num::NonZeroU32, sync::Arc};

use esp_idf_svc::{
    hal::{
        gpio::{InputMode, InterruptType, Pin, PinDriver},
        task::notification::{Notification, Notifier},
    },
    sys::EspError,
};

use crate::worker;
use crate::{device::drivers::fpga, ui};

use super::Device;

const FLAG_MCU_IRQ: NonZeroU32 = NonZeroU32::new(1).unwrap();
const FLAG_HOME: NonZeroU32 = NonZeroU32::new(2).unwrap();
const FLAG_POWER: NonZeroU32 = NonZeroU32::new(4).unwrap();
const FLAG_VOL_UP: NonZeroU32 = NonZeroU32::new(8).unwrap();
const FLAG_VOL_DOWN: NonZeroU32 = NonZeroU32::new(16).unwrap();
const FLAG_VBUS_PGOOD: NonZeroU32 = NonZeroU32::new(32).unwrap();
const FLAG_BUTTONS: u32 =
    FLAG_HOME.get() | FLAG_POWER.get() | FLAG_VOL_UP.get() | FLAG_VOL_DOWN.get();

fn setup_gpio_interrupt(
    pin: &mut PinDriver<'_, impl Pin, impl InputMode>,
    interrupt_type: InterruptType,
    notifier: Arc<Notifier>,
    flags: NonZeroU32,
) -> Result<(), EspError> {
    // SAFETY: only ISR-safe FreeRTOS functions will be called (task notify).
    unsafe {
        pin.subscribe(move || {
            notifier.notify_and_yield(flags);
        })?;
    }
    pin.set_interrupt_type(interrupt_type)?;
    pin.enable_interrupt()?;
    Ok(())
}

impl Device<'_> {
    /// Setup interrupts on the Device interrupt sources:
    ///
    /// * Volume up, volume down, home, and power buttons
    /// * Shared MCU_IRQ line
    pub(super) fn setup_interrupts() {
        // Setup interrupt handler thread.
        std::thread::Builder::new()
            .name("Interrupt".to_string())
            .stack_size(4 * 1024)
            .spawn(|| {
                // Above the UI (10), below the link task (12): FPGA IRQs (link
                // events) are acknowledged without waiting for a UI time slice.
                unsafe { esp_idf_svc::sys::vTaskPrioritySet(std::ptr::null_mut(), 11) };
                let notification = Notification::new();

                {
                    let device = &mut Device::get().lock().unwrap();
                    setup_gpio_interrupt(
                        &mut device.button_home,
                        InterruptType::AnyEdge,
                        notification.notifier(),
                        FLAG_HOME,
                    )
                    .unwrap();
                    setup_gpio_interrupt(
                        &mut device.button_power,
                        InterruptType::AnyEdge,
                        notification.notifier(),
                        FLAG_POWER,
                    )
                    .unwrap();
                    setup_gpio_interrupt(
                        &mut device.button_vol_up,
                        InterruptType::AnyEdge,
                        notification.notifier(),
                        FLAG_VOL_UP,
                    )
                    .unwrap();
                    setup_gpio_interrupt(
                        &mut device.button_vol_down,
                        InterruptType::AnyEdge,
                        notification.notifier(),
                        FLAG_VOL_DOWN,
                    )
                    .unwrap();
                    setup_gpio_interrupt(
                        &mut device.pin_irq,
                        InterruptType::LowLevel,
                        notification.notifier(),
                        FLAG_MCU_IRQ,
                    )
                    .unwrap();
                    setup_gpio_interrupt(
                        &mut device.pin_vbus_pgood,
                        InterruptType::AnyEdge,
                        notification.notifier(),
                        FLAG_VBUS_PGOOD,
                    )
                    .unwrap();
                }

                #[allow(unused)]
                let mut prev_hdmi_detected: Option<bool> = None;
                #[allow(unused)]
                let mut prev_vbus_pgood: Option<bool> = None;
                let mut last_i2c_check: i64 = 0;
                #[allow(unused)]
                let mut last_expander_check: i64 = 0;

                loop {
                    let flags = match notification.wait(esp_idf_svc::hal::delay::BLOCK) {
                        Some(flags) => flags.get(),
                        _ => continue,
                    };

                    // Acknowledge the FPGA first, with no slow I2C work under
                    // the same guard. The radio task needs Device for SPI too.
                    let fpga_irq = if (flags & FLAG_MCU_IRQ.get()) != 0 {
                        let mut device = Device::lock();
                        let pending = device.fpga.read_u32(fpga::REG_CTRL_IRQ_PENDING).unwrap();
                        if pending != 0 {
                            device.fpga.write_u32(fpga::REG_CTRL_IRQ_PENDING, pending).unwrap();
                        }
                        pending
                    } else {
                        0
                    };
                    if (fpga_irq & fpga::Irq::CoreRequest.as_flag()) != 0 {
                        // Device is unlocked before waking the higher-priority
                        // CWL task. Yield before reacquiring it for other work.
                        crate::link::core_request();
                        std::thread::yield_now();
                    }

                    let mut device = Device::lock();

                    if (flags & FLAG_HOME.get()) != 0 {
                        let _ = device.button_home.enable_interrupt();
                    }
                    if (flags & FLAG_POWER.get()) != 0 {
                        let _ = device.button_power.enable_interrupt();
                    }
                    if (flags & FLAG_VOL_UP.get()) != 0 {
                        let _ = device.button_vol_up.enable_interrupt();
                    }
                    if (flags & FLAG_VOL_DOWN.get()) != 0 {
                        let _ = device.button_vol_down.enable_interrupt();
                    }
                    if (flags & FLAG_VBUS_PGOOD.get()) != 0 {
                        let _ = device.pin_vbus_pgood.enable_interrupt();
                        // Show the charging state without waiting for the next power poll.
                        worker::send(worker::Message::PowerRefresh);
                    }
                    let mut poll_buttons = (flags & FLAG_BUTTONS) != 0;

                    // Rev 1 and 2, must read I/O expander to clear IRQ. Rev 1 also
                    // needs it for HDMI detect. GB-116: on rev 2 the I2C read held
                    // Device for every FPGA IRQ while the link task waited for SPI;
                    // skip it when only the FPGA asserted the shared line (line
                    // high after the ack), as for the fuel gauge/DAC below.
                    #[cfg(all(feature = "has_io_expander", feature = "rev1"))]
                    #[allow(unused)]
                    let io_expander = device.io_expander.get_pins().unwrap();
                    #[cfg(all(feature = "has_io_expander", not(feature = "rev1")))]
                    {
                        let now = unsafe { esp_idf_svc::sys::esp_timer_get_time() };
                        let only_fpga = fpga_irq != 0 && !device.pin_irq.is_low()
                            && now - last_expander_check < 250_000;
                        if !only_fpga {
                            last_expander_check = now;
                            let _ = device.io_expander.get_pins();
                        }
                    }

                    // Handle dock monitoring
                    cfg_if::cfg_if! {
                        if #[cfg(feature = "rev1")] {
                            // Docking is based on HDMI hot plug
                            let hdmi_detected = device.parse_hdmi_detect(io_expander).unwrap();
                            if prev_hdmi_detected != Some(hdmi_detected) {
                                prev_hdmi_detected = Some(hdmi_detected);

                                if hdmi_detected {
                                    worker::send(worker::Message::DockBegin { serial: 0, hardware: 0, firmware: 0 });
                                } else {
                                    worker::send(worker::Message::DockEnd);
                                }
                            }
                        } else {
                            // On VBUS pgood falling, force undock
                            let vbus_pgood = device.get_vbus_pgood();
                            if prev_vbus_pgood != Some(vbus_pgood) {
                                prev_vbus_pgood = Some(vbus_pgood);
                                if !vbus_pgood {
                                    worker::send(worker::Message::DockEnd);
                                }
                            }
                        }
                    }

                    poll_buttons |= (fpga_irq & fpga::Irq::Button.as_flag()) != 0;
                    let input_state = if poll_buttons {
                        Some(device.get_input_state().unwrap())
                    } else {
                        None
                    };
                    drop(device);
                    if let Some(input_state) = input_state {
                        ui::send(ui::Message::InputState(input_state));
                    }

                    if (flags & FLAG_MCU_IRQ.get()) != 0 {
                        let worker_irq = fpga_irq & !fpga::Irq::CoreRequest.as_flag();
                        if worker_irq != 0 {
                            worker::send(worker::Message::FpgaIrq(worker_irq));
                        }
                        // The I2C checks below hold Device for ~1 ms and delay the
                        // link task on every FPGA IRQ. Skip them when the shared
                        // line went high after the FPGA ack, i.e. no other source
                        // asserts it. Fuel gauge alerts are level and the DAC
                        // status (0x2C) is sticky, so a 250 ms recheck loses nothing.
                        let now = unsafe { esp_idf_svc::sys::esp_timer_get_time() };
                        let line_low = Device::lock().pin_irq.is_low();
                        if fpga_irq != 0 && !line_low && now - last_i2c_check < 250_000 {
                            let _ = Device::lock().pin_irq.enable_interrupt();
                            continue;
                        }
                        last_i2c_check = now;
                        // All shared-line sources are still serviced. Separate
                        // guards let pending FPGA accesses run between devices.
                        #[cfg(feature = "has_max17048")]
                        {
                            let mut device = Device::lock();
                            if let Ok(fuel_irq) = device.fuel_gauge.query_alerts() {
                                let _ = fuel_irq;
                            }
                        }
                        std::thread::yield_now();
                        let headphones = {
                            let mut device = Device::lock();
                            match device.dac.get_interrupt_status() {
                                Ok(irq) if irq.headset_detected =>
                                    Some(device.dac.get_headphones_detected().unwrap()),
                                _ => None,
                            }
                        };
                        if let Some(detected) = headphones {
                            worker::send(worker::Message::HeadphoneState(detected));
                        }
                        // Keep the low-level IRQ disabled until expander, FPGA,
                        // buttons, fuel gauge and DAC have all been serviced.
                        let _ = Device::lock().pin_irq.enable_interrupt();
                    }
                }
            })
            .unwrap();
    }
}
