use embassy_sync::{blocking_mutex::raw::CriticalSectionRawMutex, channel::Channel};
use embassy_time::Timer;

static CHANNEL: Channel<CriticalSectionRawMutex, Action, 1> = Channel::new();

enum Action {
    Reboot,
    RebootDfu,
    EnableDebug,
}

/// Schedule a reboot.
pub fn reboot() {
    let _ = CHANNEL.try_send(Action::Reboot);
}

/// Schedule a reboot to DFU mode.
pub fn reboot_dfu() {
    let _ = CHANNEL.try_send(Action::RebootDfu);
}

/// Schedule a re-enabling of USB Serial/JTAG.
pub fn enable_debug() {
    let _ = CHANNEL.try_send(Action::EnableDebug);
}

fn set_reset_reason_hint(hint: u32) {
    // From esp_reset_reason_set_hint in
    // components/esp_system/port/soc/esp32s3/reset_reason.c
    const RST_REASON_MASK: u32 = 0x7FFF;
    const RST_REASON_BIT: u32 = 0x80000000;
    const RST_REASON_SHIFT: u32 = 16;
    const RTC_RESET_CAUSE_REG: u32 = 0x6000_80C8;
    assert!((hint & RST_REASON_MASK) == hint);
    let val = hint | (hint << RST_REASON_SHIFT) | RST_REASON_BIT;
    unsafe { core::ptr::write_volatile(RTC_RESET_CAUSE_REG as *mut u32, val) };
}

#[embassy_executor::task]
pub async fn task() {
    loop {
        let message = CHANNEL.receive().await;
        Timer::after_millis(100).await;
        match message {
            Action::Reboot => esp_hal::system::software_reset(),
            Action::RebootDfu => {
                const DFU_HINT: u32 = 0x1B01;
                set_reset_reason_hint(DFU_HINT);
                esp_hal::system::software_reset();
            }
            Action::EnableDebug => {
                // Fairly complicated to do, see:
                // https://github.com/espressif/arduino-esp32/blob/master/cores/esp32/esp32-hal-tinyusb.c#L523
                // Maybe there should be a reset + don't enable USB mode?
            }
        }
    }
}
