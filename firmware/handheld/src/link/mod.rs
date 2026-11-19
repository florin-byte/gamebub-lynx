//! Wireless link transport with an asynchronous lifecycle and status notifications.
use esp_idf_svc::sys;
use std::ffi::{CStr, CString};
use std::sync::Once;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(feature = "link-diag")]
use std::sync::atomic::AtomicU32;

static CORE_RUNNING: AtomicBool = AtomicBool::new(false);
#[cfg(feature = "link-diag")]
static GBA_RUNNING: AtomicBool = AtomicBool::new(false);

static NO_MEMORY: AtomicBool = AtomicBool::new(false);
static CONNECTED_BEFORE: AtomicBool = AtomicBool::new(false);

// Serialize the C owner lifecycle. Status reads use the C critical-section snapshot.
static OWNER: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Off,
    Searching,
    Connected,
}

#[derive(Debug)]
pub struct Status {
    pub state: State,
    pub page_state: u32,
    pub partner: String,
    pub channel: u32,
    pub rx: u32,
    pub tx: u32,
    pub rtt_ms: u32,
    pub rx_drop: u32,
    pub tx_fail: u32,
    pub malformed: u32,
    pub pongs: u32,
}

// Lifecycle work and polling share the existing Worker stack. No Link thread,
// channel, detached pthread, or per-session Rust buffers remain after stop.
static ACTIVE: AtomicBool = AtomicBool::new(false);
static CONNECTED: AtomicBool = AtomicBool::new(false);
#[cfg(feature = "link-diag")]
static LAST_REPORT_MS: AtomicU32 = AtomicU32::new(0);
#[cfg(feature = "link-diag")]
static LAST_FPGA_REPORT_MS: AtomicU32 = AtomicU32::new(0);

// Only the diagnostic variant records these fixed-size access windows. No
// logging or timing mutex is held while Device is locked.
#[cfg(feature = "link-diag")]
#[derive(Clone, Copy)]
struct AccessTiming {
    n: u32,
    wait_total: u64,
    wait_max: u32,
    io_total: u64,
    io_max: u32,
}
#[cfg(feature = "link-diag")]
impl AccessTiming {
    const ZERO: Self = Self { n: 0, wait_total: 0, wait_max: 0, io_total: 0, io_max: 0 };
}
#[cfg(feature = "link-diag")]
static ACCESS_TIMING: Mutex<[AccessTiming; 3]> = Mutex::new([AccessTiming::ZERO; 3]);

fn link_device_access<T>(
    _kind: usize,
    access: impl FnOnce(&mut crate::device::Device<'static>) -> T,
) -> T {
    #[cfg(feature = "link-diag")]
    let start = unsafe { sys::esp_timer_get_time() } as u32;
    let mut device = crate::device::Device::lock();
    #[cfg(feature = "link-diag")]
    let acquired = unsafe { sys::esp_timer_get_time() } as u32;
    let result = access(&mut device);
    #[cfg(feature = "link-diag")]
    let finish = unsafe { sys::esp_timer_get_time() } as u32;
    drop(device);
    #[cfg(feature = "link-diag")]
    {
        let mut timings = ACCESS_TIMING.lock().unwrap_or_else(|e| e.into_inner());
        let timing = &mut timings[_kind];
        let wait = acquired.wrapping_sub(start);
        let io = finish.wrapping_sub(acquired);
        timing.n += 1;
        timing.wait_total += wait as u64;
        timing.wait_max = timing.wait_max.max(wait);
        timing.io_total += io as u64;
        timing.io_max = timing.io_max.max(io);
    }
    result
}

#[cfg(feature = "link-diag")]
fn report_access_timing() {
    let timings = {
        let mut timings = ACCESS_TIMING.lock().unwrap_or_else(|e| e.into_inner());
        std::mem::replace(&mut *timings, [AccessTiming::ZERO; 3])
    };
    for (kind, timing) in ["read", "event_pop/batch", "write"].iter().zip(timings) {
        log::info!("link: spi_us kind={} n={} wait_total={} wait_max={} io_total={} io_max={}",
            kind, timing.n, timing.wait_total, timing.wait_max, timing.io_total, timing.io_max);
    }
}

/// A read-only LX candidate snapshot, including the remembered partner first.
#[derive(Clone)]
pub struct Partner {
    pub mac: [u8; 6],
    pub name: String,
    pub seen: bool,
}

pub fn own_name() -> String {
    crate::kvs::keys::WL_NAME.get().unwrap_or_else(|| {
        let mut mac = [0u8; 6];
        unsafe { sys::esp_efuse_mac_get_default(mac.as_mut_ptr()) };
        let name = format!("GAMEBUB {:02X}{:02X}", mac[4], mac[5]);
        crate::kvs::keys::WL_NAME.set(&name);
        crate::kvs::keys::WL_NAME.flush();
        name
    })
}

fn initialize_preferences() {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let name = CString::new(own_name()).unwrap();
        let saved = crate::kvs::keys::WL_PEER.get().flatten();
        let peer_name = CString::new(saved.as_ref().map_or("", |p| p.1.as_str())).unwrap();
        unsafe {
            sys::gamebub_cwl_set_name(name.as_ptr());
            sys::gamebub_cwl_set_partner(
                saved.as_ref().map_or(std::ptr::null(), |p| p.0.as_ptr()), peer_name.as_ptr());
            sys::gamebub_cwl_set_fast_gb(fast_gb());
        }
    });
}

/// Whether GB/GBC game traffic is sent at 12 Mbps (link page setting, off by default).
pub fn fast_gb() -> bool {
    crate::kvs::keys::WL_FAST_GB.get().unwrap_or(false)
}

/// Switch the GB/GBC radio rate; applies to the running link at once and is persisted.
pub fn set_fast_gb(enabled: bool) {
    initialize_preferences();
    crate::kvs::keys::WL_FAST_GB.set(&enabled);
    crate::kvs::keys::WL_FAST_GB.flush();
    unsafe { sys::gamebub_cwl_set_fast_gb(enabled) };
}

/// GB-162: whether choosing a game of this system asks "Play / Dual Core Link" (Settings, the
/// core's page; off by default).
pub fn link_play_enabled(gba: bool) -> bool {
    let key = if gba { &crate::kvs::keys::WL_LINK_GBA } else { &crate::kvs::keys::WL_LINK_GB };
    // The checkbox only exists while the two-core bitstream is on the card.
    key.get().unwrap_or(false) && dual_core_bitstream(gba).is_some()
}

/// GB-162: the bitstream with the second core is too large for the console's own storage, so
/// it is a file of its own in system/ on the card. None: not on the card, no Dual Core Link.
pub fn dual_core_bitstream(gba: bool) -> Option<std::path::PathBuf> {
    let name = if gba { "gba-dual.bit.hs" } else { "gameboy-dual.bit.hs" };
    let path = std::path::Path::new("/sdcard/system/").join(name);
    path.is_file().then_some(path)
}

static LINK_NEXT: AtomicBool = AtomicBool::new(false);

/// The player's answer for the game being started; cleared whenever a core is run.
pub fn request_link_game(link: bool) {
    LINK_NEXT.store(link, Ordering::Relaxed);
}

/// Whether the game being started was chosen with "Dual Core Link". Read when a game starts.
pub fn link_requested() -> bool {
    LINK_NEXT.load(Ordering::Relaxed)
}

/// Frames between a button press and its use on both consoles.
pub const SYNC_DELAY: u32 = 2;

/// Arm or disarm the frame-locked exchange for the game being started (Worker, before CORE_RUN).
pub(crate) fn set_sync(enabled: bool, gba: bool, rom_id: u32, save_crc: u32) {
    SYNC_ARMED.store(enabled, Ordering::Relaxed);
    SAVE_BLANK.store(false, Ordering::Relaxed);
    let title = CString::new(game_title()).unwrap_or_default();
    unsafe { sys::gamebub_cwl_set_sync(enabled, gba, SYNC_DELAY, rom_id, save_crc, title.as_ptr()) };
    // GB-162: a link game looks for the partner by itself; nobody has to switch the radio on.
    if enabled && !ACTIVE.load(Ordering::Relaxed) {
        request_start();
    }
}

static SAVE_BLANK: AtomicBool = AtomicBool::new(false);

/// Both cores of the armed link game now hold an empty save with this id (Worker).
pub(crate) fn set_sync_save(save_crc: u32) {
    SAVE_BLANK.store(true, Ordering::Relaxed);
    unsafe { sys::gamebub_cwl_sync_set_save(save_crc) };
}

static GAME_TITLE: Mutex<String> = Mutex::new(String::new());

/// The game being loaded, as the partner's screen will call it: the file name without
/// extension and without its (region) and [dump] tags, 24 printable ASCII characters at most.
pub(crate) fn set_game_title(path: &std::path::Path) {
    let stem = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let mut title = String::new();
    let mut depth = 0u32;
    for c in stem.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = depth.saturating_sub(1),
            ' '..='~' if depth == 0 => title.push(c),
            _ if depth == 0 => title.push('?'),
            _ => {}
        }
    }
    let title: String = title.trim().chars().take(24).collect();
    *GAME_TITLE.lock().unwrap_or_else(|e| e.into_inner()) = title.trim().to_string();
}

pub fn game_title() -> String {
    GAME_TITLE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// The player chose to play with the partner the waiting room shows.
pub fn session_accept() {
    unsafe { sys::gamebub_cwl_sync_accept() };
}

/// The game being started cannot run as two cores although the link page asks for it: it
/// plays through the byte link. `short` fits a notification.
pub(crate) fn sync_unavailable(reason: &str, short: &str) {
    log::info!("sync: off, {}", reason);
    crate::ui::send(crate::ui::Message::Notification(
        crate::ui::Notification::new_short(format!("Wireless link only: {}", short))));
    // The player asked for a link game: the byte link needs the radio just the same.
    if !ACTIVE.load(Ordering::Relaxed) {
        request_start();
    }
}

static SYNC_ARMED: AtomicBool = AtomicBool::new(false);

/// GB-162: the two-core link session as the waiting room shows it (GbSyncPhase in
/// gamebub_cwl_sync.h, plus RadioOff: a game is armed and the radio is not running).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionPhase {
    Off,
    RadioOff,
    /// The radio could not start: not enough memory.
    NoMemory,
    /// The partner is in the same game; this player has not accepted yet.
    Found,
    /// This player accepted, the partner's has not.
    Accepted,
    Searching,
    Waiting,
    Refused,
    Running,
    Lost,
    Diverged,
    Failed,
}

/// Why the two consoles are not playing (GbSyncWhy in gamebub_cwl_sync.h).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionWhy {
    None,
    Silent,
    Firmware,
    System,
    Game,
    Save,
    Settings,
    Core,
    Internal,
    /// The partner left the running game.
    Left,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Session {
    pub phase: SessionPhase,
    pub why: SessionWhy,
    /// 1 or 2 once the game runs, else 0.
    pub player: u8,
    pub frames: u32,
    pub partner_accepted: bool,
    /// The game the partner is in, when it said so.
    pub partner_title: String,
    /// This player chose to play without the saves.
    pub blank_save: bool,
}

pub fn session() -> Session {
    let mut session = Session { phase: SessionPhase::Off, why: SessionWhy::None, player: 0, frames: 0,
        partner_accepted: false, partner_title: String::new(), blank_save: false };
    if !SYNC_ARMED.load(Ordering::Relaxed) {
        return session;
    }
    let mut raw = sys::gamebub_cwl_session_t::default();
    unsafe { sys::gamebub_cwl_session(&mut raw) };
    session.phase = match raw.phase {
        1 => SessionPhase::Searching,
        2 => SessionPhase::Waiting,
        3 => SessionPhase::Refused,
        4 => SessionPhase::Running,
        5 => SessionPhase::Lost,
        6 => SessionPhase::Diverged,
        7 => SessionPhase::Failed,
        8 => SessionPhase::Found,
        9 => SessionPhase::Accepted,
        // Armed, and the radio task has not taken it up (yet).
        _ if NO_MEMORY.load(Ordering::Relaxed) => SessionPhase::NoMemory,
        _ if !ACTIVE.load(Ordering::Relaxed) => SessionPhase::RadioOff,
        _ => SessionPhase::Searching,
    };
    session.why = match raw.why {
        1 => SessionWhy::Silent,
        2 => SessionWhy::Firmware,
        3 => SessionWhy::System,
        4 => SessionWhy::Game,
        5 => SessionWhy::Save,
        6 => SessionWhy::Settings,
        7 => SessionWhy::Core,
        8 => SessionWhy::Internal,
        9 => SessionWhy::Left,
        _ => SessionWhy::None,
    };
    session.blank_save = SAVE_BLANK.load(Ordering::Relaxed);
    session.player = raw.player as u8;
    session.frames = raw.frames;
    session.partner_accepted = raw.partner_accepted != 0;
    // The C snapshot always terminates the fixed-size title.
    session.partner_title = unsafe { CStr::from_ptr(raw.partner_title.as_ptr()) }.to_string_lossy().into_owned();
    session
}

pub fn partners() -> Vec<Partner> {
    initialize_preferences();
    let mut raw = [sys::gamebub_cwl_partner_t::default(); 6];
    let count = unsafe { sys::gamebub_cwl_partners(raw.as_mut_ptr()) } as usize;
    raw[..count.min(raw.len())].iter().map(|p| Partner {
        mac: p.mac,
        name: unsafe { CStr::from_ptr(p.name.as_ptr()) }.to_string_lossy().into_owned(),
        seen: p.seen,
    }).collect()
}

/// Pin a MAC (or return to automatic pairing) and persist it immediately.
pub fn set_partner(mac: Option<[u8; 6]>) {
    let saved = mac.map(|mac| {
        let name = partners().into_iter().find(|p| p.mac == mac).map(|p| p.name)
            .unwrap_or_else(|| format!("{:02X}{:02X}", mac[4], mac[5]));
        (mac, name)
    });
    initialize_preferences();
    crate::kvs::keys::WL_PEER.set(&saved);
    crate::kvs::keys::WL_PEER.flush();
    let name = CString::new(saved.as_ref().map_or("", |p| p.1.as_str())).unwrap();
    unsafe { sys::gamebub_cwl_set_partner(
        saved.as_ref().map_or(std::ptr::null(), |p| p.0.as_ptr()), name.as_ptr()); }
    CONNECTED_BEFORE.store(false, Ordering::Relaxed);
    CONNECTED.store(false, Ordering::Relaxed);
}

pub fn partner_label() -> String {
    crate::kvs::keys::WL_PEER.get().flatten().map_or_else(
        || "Any".to_string(), |(mac, name)| format!("{} ({:02X}{:02X})", name, mac[4], mac[5]))
}

/// Both menu actions and hotkey file requests reach the same transfer guard.
pub fn states_allowed() -> bool {
    unsafe { sys::gamebub_cwl_states_allowed(if ACTIVE.load(Ordering::Relaxed) { 1 } else { 0 }) }
}

pub fn request_start() {
    crate::worker::send(crate::worker::Message::LinkStart);
}

pub fn request_stop() {
    crate::worker::send(crate::worker::Message::LinkStop);
}

pub fn log_memory(point: &'static str) {
    crate::worker::send(crate::worker::Message::LinkMemory(point));
}

pub(crate) fn start() {
    initialize_preferences();
    let _owner = OWNER.lock().unwrap();
    if ACTIVE.load(Ordering::Relaxed) {
        return;
    }
    NO_MEMORY.store(false, Ordering::Relaxed);
    let caps = sys::MALLOC_CAP_INTERNAL | sys::MALLOC_CAP_8BIT;
    let largest = unsafe { sys::heap_caps_get_largest_free_block(caps) };
    let free = unsafe { sys::heap_caps_get_free_size(caps) };
    log_memory_now("link-before-start");
    // The link allocates small pieces (task stacks of 6 KiB, Wi-Fi buffers of
    // ~1.6 KiB) for ~52 KiB in all, so it needs total memory, not one big block
    // (GB-119: 23 KiB largest block with 104 KiB free was refused before).
    let result = if largest < 12288 || free < 65536 {
        log::warn!("link: start refused, largest={} free={}", largest, free);
        Err(sys::EspError::from_infallible::<{ sys::ESP_ERR_NO_MEM }>())
    } else {
        unsafe { sys::gamebub_cwl_set_diagnostics(cfg!(feature = "link-diag")) };
        unsafe { sys::gamebub_cwl_set_benchmark(cfg!(feature = "link-bench")) };
        sys::esp!(unsafe { sys::gamebub_cwl_start() })
    };
    ACTIVE.store(result.is_ok(), Ordering::Relaxed);
    if !cfg!(feature = "link-bench") && result.is_ok() && CORE_RUNNING.load(Ordering::Acquire) {
        let _ = crate::device::Device::lock().fpga.enable_interrupt(
            crate::device::drivers::fpga::Irq::CoreRequest);
    }
    if let Err(error) = result {
        NO_MEMORY.store(error.code() == sys::ESP_ERR_NO_MEM, Ordering::Relaxed);
        log::error!("link: start failed: {}", error);
    }
    log_memory_now("game-after-link-start");
}

// Also called directly from CoreManager on Worker: complete teardown before
// loading another core, rather than enqueueing it behind the current load.
pub(crate) fn stop() {
    let _owner = OWNER.lock().unwrap();
    if let Err(error) = sys::esp!(unsafe { sys::gamebub_cwl_stop() }) {
        log::error!("link: stop failed: {}", error);
    }
    if !cfg!(feature = "link-bench") && CORE_RUNNING.load(Ordering::Acquire) {
        let _ = crate::device::Device::lock().fpga.disable_interrupt(
            crate::device::drivers::fpga::Irq::CoreRequest);
    }
    ACTIVE.store(false, Ordering::Relaxed);
    CONNECTED.store(false, Ordering::Relaxed);
    CONNECTED_BEFORE.store(false, Ordering::Relaxed);
    NO_MEMORY.store(false, Ordering::Relaxed);
    log_memory_now("after-link-stop");
}

pub(crate) fn poll() {
    if !ACTIVE.load(Ordering::Relaxed) {
        return;
    }
    let current = status();
    #[cfg(feature = "link-diag")]
    {
        let now = (unsafe { sys::esp_timer_get_time() } / 1000) as u32;
        let fpga_previous = LAST_FPGA_REPORT_MS.load(Ordering::Relaxed);
        if !cfg!(feature = "link-bench") && now.wrapping_sub(fpga_previous) >= 1000 {
            LAST_FPGA_REPORT_MS.store(now, Ordering::Relaxed);
            let core = CORE_RUNNING.load(Ordering::Acquire);
            if core {
                use crate::device::drivers::fpga;
                let mut device = crate::device::Device::lock();
                let gba = GBA_RUNNING.load(Ordering::Relaxed);
                let config = device.fpga.link_read(if gba { fpga::GBA_LINK_CONFIG } else { fpga::LINK_CONFIG });
                let status = device.fpga.link_read(if gba { fpga::GBA_LINK_STATUS } else { fpga::LINK_STATUS });
                let irq = device.fpga.read_u32(fpga::REG_CTRL_IRQ_ENABLE);
                drop(device);
                match (config, status, irq) {
                    (Ok(config), Ok(status), Ok(irq)) => log::info!(
                        "link: fpga config=0x{:08x} status=0x{:08x} enable_cond partner={} core={} irq={}",
                        config, status, (current.state == State::Connected) as u8,
                        core as u8, ((irq & fpga::Irq::CoreRequest.as_flag()) != 0) as u8),
                    values => log::warn!("link: fpga diagnostic read failed: {:?}", values),
                }
            } else {
                log::info!("link: fpga unavailable enable_cond partner={} core=0 irq=0",
                    (current.state == State::Connected) as u8);
            }
        }
        if now.wrapping_sub(fpga_previous) >= 1000 {
            report_access_timing();
        }
        let previous = LAST_REPORT_MS.load(Ordering::Relaxed);
        if now.wrapping_sub(previous) >= 5000 {
            LAST_REPORT_MS.store(now, Ordering::Relaxed);
            let caps = sys::MALLOC_CAP_INTERNAL | sys::MALLOC_CAP_8BIT;
            log::info!(
                "link: state={:?} ch={} partner=\"{}\" rx={} tx={} rtt_ms={} heap_free={} heap_min={} largest={}",
                current.state, current.channel, current.partner,
                current.rx, current.tx, current.rtt_ms,
                unsafe { sys::heap_caps_get_free_size(caps) },
                unsafe { sys::heap_caps_get_minimum_free_size(caps) },
                unsafe { sys::heap_caps_get_largest_free_block(caps) },
            );
        }
    }
    let connected = current.state == State::Connected;
    if CONNECTED.swap(connected, Ordering::Relaxed) != connected {
        let text = if connected {
            CONNECTED_BEFORE.store(true, Ordering::Relaxed);
            format!("Connected to \"{}\"", current.partner)
        } else {
            "Partner lost".to_string()
        };
        crate::ui::send(crate::ui::Message::Notification(
            crate::ui::Notification::new_short(text)));
    }
}

pub fn status() -> Status {
    let mut raw = sys::gamebub_cwl_status_t::default();
    unsafe { sys::gamebub_cwl_status(&mut raw) };
    Status {
        page_state: unsafe { sys::gamebub_cwl_page_state(raw.state,
            CONNECTED_BEFORE.load(Ordering::Relaxed) as u32,
            NO_MEMORY.load(Ordering::Relaxed) as u32) },
        state: match raw.state {
            1 => State::Searching,
            2 => State::Connected,
            _ => State::Off,
        },
        // The C snapshot always terminates the fixed-size name.
        partner: unsafe { CStr::from_ptr(raw.partner.as_ptr()) }
            .to_string_lossy()
            .into_owned(),
        channel: raw.channel,
        rx: raw.rx,
        tx: raw.tx,
        rtt_ms: raw.rtt_ms,
        rx_drop: raw.rx_drop,
        tx_fail: raw.tx_fail,
        malformed: raw.malformed,
        pongs: raw.pongs,
    }
}

/// Snapshot the internal, byte-addressable heap without allocating a message buffer.
pub(crate) fn log_memory_now(point: &str) {
    let caps = sys::MALLOC_CAP_INTERNAL | sys::MALLOC_CAP_8BIT;
    log::info!(
        "mem: {} free={} min={} largest={}",
        point,
        unsafe { sys::heap_caps_get_free_size(caps) },
        unsafe { sys::heap_caps_get_minimum_free_size(caps) },
        unsafe { sys::heap_caps_get_largest_free_block(caps) },
    );
    // Lowest free stack the UI task (`main`) has had since boot, read from here so `main` does no work.
    let main = unsafe { sys::xTaskGetHandle(c"main".as_ptr()) };
    if !main.is_null() {
        log::info!("stack: main free={} at {}", unsafe { sys::uxTaskGetStackHighWaterMark(main) }, point);
    }
}

/// The emulator paused or resumed (menu). GBA link: both consoles return to
/// cycle sync while either is paused, so the other game waits (GB-117).
pub(crate) fn set_core_paused(paused: bool) {
    unsafe { sys::gamebub_cwl_set_paused(paused) };
}

/// Set only on Worker, after CORE_RUN or after the radio task has stopped.
pub(crate) fn set_link_running(running: bool, gba: bool) {
    let caps = if running {
        crate::device::Device::lock().fpga.read_u32(
            if gba { crate::device::drivers::fpga::GBA_LINK_CAPS }
            else { crate::device::drivers::fpga::LINK_CAPS }).ok()
    } else { None };
    #[cfg(feature = "link-diag")]
    log::info!("link: fpga caps read={} value=0x{:08x}", caps.is_some(), caps.unwrap_or(0));
    unsafe { sys::gamebub_cwl_set_core(running, caps.is_some(), caps.unwrap_or(0)) };
    if !running {
        set_core_paused(false);
        set_sync(false, false, 0, 0);
    }
    #[cfg(feature = "link-diag")]
    GBA_RUNNING.store(running && gba, Ordering::Relaxed);
    CORE_RUNNING.store(running, Ordering::Release);
}

// Called on the C radio task. Never keep Device locked across radio work or a
// task wait. Core teardown joins that task before programming another FPGA.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gamebub_link_read(address: u32, value: *mut u32) -> bool {
    if !CORE_RUNNING.load(Ordering::Acquire) || value.is_null() { return false; }
    match link_device_access(0, |device| device.fpga.link_read(address)) {
        Ok(word) => { unsafe { *value = word }; true }
        Err(_) => false,
    }
}

// Keep EVENT and POP adjacent under one Device lock; hardware generation is
// checked by the adapter after this pair, exactly as for separate accesses.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gamebub_link_event_pop(value: *mut u32) -> bool {
    if !CORE_RUNNING.load(Ordering::Acquire) || value.is_null() { return false; }
    use crate::device::drivers::fpga::{LINK_EVENT, LINK_EVENT_POP};
    let result = link_device_access(1, |device| {
        let word = device.fpga.link_read(LINK_EVENT)?;
        device.fpga.link_write(LINK_EVENT_POP, 1)?;
        Ok::<u32, crate::device::drivers::fpga::Error>(word)
    });
    match result {
        Ok(word) => { unsafe { *value = word }; true }
        Err(_) => false,
    }
}

// Several link accesses under one Device lock and, with fast link SPI, one bus
// acquisition (GB-114). Returns how many completed, in order; the C side treats
// anything short of `n` as a failed access.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gamebub_link_batch(
    ops: *mut crate::device::drivers::fpga::LinkOp, n: usize,
) -> usize {
    if !CORE_RUNNING.load(Ordering::Acquire) || ops.is_null() || n == 0 { return 0; }
    // SAFETY: the C caller passes an array of n LinkOp that it owns for this call.
    let ops = unsafe { core::slice::from_raw_parts_mut(ops, n) };
    link_device_access(1, |device| device.fpga.link_batch(ops))
}

#[unsafe(no_mangle)]
pub extern "C" fn gamebub_link_write(address: u32, value: u32) -> bool {
    let result = if CORE_RUNNING.load(Ordering::Acquire) {
        link_device_access(2, |device| device.fpga.link_write(address, value)).is_ok()
    } else {
        false
    };
    #[cfg(feature = "link-diag")]
    {
        use crate::device::drivers::fpga::{LINK_CONFIG, LINK_CONTROL};
        if address == LINK_CONFIG || address == LINK_CONTROL {
            log::info!("link: fpga write {}=0x{:02x} result={}",
                if address == LINK_CONFIG { "config" } else { "control" }, value, result);
        }
    }
    result
}

pub(crate) fn core_request() {
    unsafe { sys::gamebub_cwl_notify_bridge() };
}
