#![no_std]
#![no_main]
#![deny(
    clippy::mem_forget,
    reason = "mem::forget is generally not safe to do with esp_hal types, especially those \
    holding buffers for the duration of a data transfer."
)]
#![deny(clippy::large_stack_frames)]

use embassy_executor::Spawner;
use esp_hal::clock::CpuClock;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig, Pull};
use esp_hal::interrupt::software::SoftwareInterruptControl;
use esp_hal::otg_fs::Usb;
use esp_hal::timer::timg::TimerGroup;
use log::info;
use static_cell::StaticCell;

use crate::flash::Flash;
use crate::protocol::Protocol;
use crate::virtual_disk::Uf2VirtualDisk;

mod flash;
mod info;
mod led;
mod protocol;
mod reboot;
mod uf2;
mod usb;
mod usb_class;
mod virtual_disk;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    loop {}
}

extern crate alloc;

// Application descriptor for esp-idf bootloader.
esp_bootloader_esp_idf::esp_app_desc!();

static PROTOCOL: StaticCell<Protocol> = StaticCell::new();
static FLASH: StaticCell<Flash> = StaticCell::new();
static VIRTUAL_DISK: StaticCell<Uf2VirtualDisk> = StaticCell::new();

#[allow(
    clippy::large_stack_frames,
    reason = "it's not unusual to allocate larger buffers etc. in main"
)]
#[esp_rtos::main]
async fn main(spawner: Spawner) {
    esp_println::logger::init_logger_from_env();

    let config = esp_hal::Config::default().with_cpu_clock(CpuClock::max());
    let peripherals = esp_hal::init(config);

    esp_alloc::heap_allocator!(#[esp_hal::ram(reclaimed)] size: 73744);

    let sw_int = SoftwareInterruptControl::new(peripherals.SW_INTERRUPT);
    let timg0 = TimerGroup::new(peripherals.TIMG0);
    esp_rtos::start(timg0.timer0, sw_int.software_interrupt0);

    info!("Game Bub DFU");

    let led = Output::new(peripherals.GPIO42, Level::Low, OutputConfig::default());
    spawner.spawn(led::blink_task(led).unwrap());

    let button_dfu = {
        let config = InputConfig::default().with_pull(Pull::Up);
        Input::new(peripherals.GPIO1, config)
    };
    let manual_dfu = button_dfu.is_low();

    spawner.spawn(reboot::task().unwrap());

    let flash = FLASH.init_with(|| Flash::new(peripherals.FLASH));

    // Load app descriptor of the actual firmware image
    let fw_meta = info::read_fw_metadata(flash);

    let protocol = PROTOCOL.init(Protocol::new(flash));
    let virtual_disk = VIRTUAL_DISK.init(Uf2VirtualDisk::new(flash));

    let usb = Usb::new(peripherals.USB0, peripherals.GPIO20, peripherals.GPIO19);
    usb::setup_usb(
        spawner.clone(),
        usb,
        protocol,
        fw_meta,
        if manual_dfu { Some(virtual_disk) } else { None },
    );
}
