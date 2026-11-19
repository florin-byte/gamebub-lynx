#![allow(static_mut_refs)]

use arrayvec::{ArrayString, ArrayVec};
use std::io::Write as _;
use std::{
    fmt::{Display, Write as _},
    fs::File,
    mem::MaybeUninit,
    panic::PanicHookInfo,
};

const MAGIC_NUMBER: u32 = 0xDEADB011;

#[derive(Debug)]
struct CrashReport {
    magic: u32,
    panic: bool,
    crash: bool,
    backtrace: ArrayVec<usize, 64>,
    message: ArrayString<512>,
}

#[link_section = ".rtc_noinit"]
static mut REPORT: MaybeUninit<CrashReport> = MaybeUninit::uninit();

unsafe extern "C" {
    unsafe fn __real_esp_panic_handler(info: *mut core::ffi::c_void);
    fn gamebub_crash_reason(info: *const core::ffi::c_void) -> *const core::ffi::c_char;
}

#[no_mangle]
pub unsafe extern "C" fn __wrap_esp_panic_handler(info: *mut core::ffi::c_void) {
    let report = REPORT.assume_init_mut();
    report.magic = MAGIC_NUMBER;
    report.crash = true;

    // Reserve room for the reason and task even after a long Rust panic message.
    while report.message.len() > 192 {
        report.message.pop();
    }
    // Fixed-capacity RTC storage: no heap allocation or logger in the panic path.
    let _ = report.message.try_push_str("CRASH: ");
    append_c_string(&mut report.message, gamebub_crash_reason(info));
    let _ = report.message.try_push_str("; current task: ");
    append_c_string(
        &mut report.message,
        esp_idf_svc::sys::pcTaskGetName(core::ptr::null_mut()),
    );
    let _ = report.message.try_push_str("\n");

    let mut pc: u32 = 0;
    let mut sp: u32 = 0;
    let mut next_pc: u32 = 0;

    esp_idf_svc::sys::esp_backtrace_get_start(&mut pc, &mut sp, &mut next_pc);
    let mut frame = esp_idf_svc::sys::esp_backtrace_frame_t {
        pc,
        sp,
        next_pc,
        exc_frame: core::ptr::null_mut(),
    };

    while !report.backtrace.is_full() {
        if !esp_idf_svc::sys::esp_backtrace_get_next_frame(&mut frame) {
            break;
        }
        if frame.pc == 0 {
            break;
        }
        // Xtensa stuff
        let pc = (frame.pc & 0x3FFF_FFFF) | 0x4000_0000;
        report.backtrace.push(pc as usize);
    }

    __real_esp_panic_handler(info);
}

// Bound reads and writes even when the panic message exceeds the RTC buffer.
unsafe fn append_c_string(message: &mut ArrayString<512>, value: *const core::ffi::c_char) {
    if value.is_null() {
        let _ = message.try_push_str("(unknown)");
        return;
    }
    for i in 0..256 {
        let byte = *value.add(i) as u8;
        if byte == 0 {
            break;
        }
        let ch = if byte.is_ascii() { byte as char } else { '?' };
        if message.try_push(ch).is_err() {
            break;
        }
    }
}

fn get_location<'a>(info: &'a PanicHookInfo<'_>) -> &'a dyn Display {
    match info.location() {
        Some(location) => location,
        None => &"(unknown)",
    }
}

/// Setup crash and panic handlers.
pub fn setup() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let report = unsafe { REPORT.assume_init_mut() };
        if report.magic == MAGIC_NUMBER {
            default_hook(info);
            return;
        }
        report.magic = MAGIC_NUMBER;
        report.panic = true;

        let thread = std::thread::current();
        let thread_name = thread.name().unwrap_or_default();
        let _ = writeln!(
            report.message,
            "PANIC: thread '{}' at {}:\n{}",
            thread_name,
            get_location(info),
            info.payload_as_str().unwrap_or_default(),
        );
        // Default hook will print some stuff, then abort(), which calls above panic handler.
        default_hook(info);
    }));
}

fn write_crash(crash: &CrashReport) -> std::io::Result<()> {
    let mut file = File::create("/sdcard/crash-log.txt")?;
    writeln!(file, "Crash Report")?;
    writeln!(file, "  FW: {}", env!("CARGO_PKG_VERSION"))?;
    writeln!(file, "  HW: {}", crate::hwinfo::get_hardware_version())?;
    writeln!(file, "  Serial: {}", crate::hwinfo::get_serial_number())?;
    writeln!(file)?;

    writeln!(file, "Message:\n{}\n", &crash.message)?;

    writeln!(file, "Backtrace:")?;
    for (i, &pc) in crash.backtrace.iter().enumerate() {
        writeln!(file, "  #{i:3}: {pc:08X}")?;
    }

    Ok(())
}

/// Save a previous crash to a file.
pub fn maybe_persist() {
    let crash = unsafe { REPORT.assume_init_mut() };
    if crash.magic == MAGIC_NUMBER {
        match write_crash(crash) {
            Ok(()) => log::info!("Wrote crash log to sdcard"),
            Err(e) => log::warn!("Failed to write crash: {e}"),
        }
    }
    *crash = unsafe { std::mem::zeroed() };
}
