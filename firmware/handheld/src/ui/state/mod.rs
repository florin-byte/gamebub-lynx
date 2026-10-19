use std::{cell::RefCell, collections::VecDeque, path::PathBuf, rc::Rc, time::Duration};

use slint::{ComponentHandle, Global, Timer, TimerMode, Weak};

use crate::kvs;
use crate::{core::CoreManager, device::Device};

use super::slint::{
    Backend, MainWindow, ScreenId, SettingDatetime, SettingEntry, SettingType, SettingValue,
};

mod cores;
mod game;
mod main_menu;
pub mod notifications;
mod settings;
mod setup;
mod tools;

pub use game::CoreSettingUiItem;

pub struct UiState {
    root: Weak<MainWindow>,

    notification_timer: Timer,
    notification_queue: VecDeque<notifications::Notification>,
    notification_active: bool,

    game_cartridge: bool,
    settings: settings::SettingsState,
    core_file_select_directory: PathBuf,
    core_file_select_filename: String,
    core_file_select_timer: Timer,
    clock_timer: Timer,
}

impl UiState {
    pub fn new(root: &MainWindow, device: &mut Device) -> Rc<RefCell<Self>> {
        let state: UiState = UiState {
            root: root.as_weak(),
            notification_timer: Timer::default(),
            notification_queue: VecDeque::new(),
            notification_active: false,
            game_cartridge: false,
            settings: settings::SettingsState::default(),
            core_file_select_directory: PathBuf::new(),
            core_file_select_filename: String::new(),
            core_file_select_timer: Timer::default(),
            clock_timer: Timer::default(),
        };
        let state = Rc::new(RefCell::new(state));
        state.borrow_mut().setup(state.clone(), device);
        state
    }

    pub fn update_battery_level(&mut self, level: f32, is_charging: bool) {
        let level = level.round() as i32;
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_battery_level(level);
        backend.set_battery_charging(is_charging);
    }

    fn setup(&mut self, state: Rc<RefCell<UiState>>, device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let battery_level = device.fuel_gauge.get_battery_level().unwrap_or(0.0);
        let is_charging = device.get_vbus_pgood() && device.get_battery_is_charging();
        self.update_battery_level(battery_level, is_charging);
        backend.set_volume_level(((kvs::keys::VOLUME.get().unwrap() as i32) * 100) / 255);
        backend.set_brightness_level((kvs::keys::BRIGHTNESS.get().unwrap() * 100.0) as i32);
        backend.set_hardware_version(crate::hwinfo::get_hardware_version().to_string().into());
        backend.set_hardware_serial(crate::hwinfo::get_serial_number().to_string().into());
        backend.set_firmware_version(env!("CARGO_PKG_VERSION").into());
        backend.set_sdcard_mounted(std::fs::exists("/sdcard").unwrap_or(false));

        // Status bar clock, the 12/24-hour preference and the theme, refreshed once per second.
        let applied_theme = std::cell::Cell::new(None::<(i32, bool)>);
        let update_clock = {
            let root = self.root.clone();
            move || {
                let Some(root) = root.upgrade() else { return };
                let dark = kvs::keys::DARK_MODE.get().unwrap_or(false);
                let theme = kvs::keys::UI_THEME.get().unwrap_or(0);
                if applied_theme.get() != Some((theme, dark)) {
                    apply_theme(&root, theme, dark);
                    applied_theme.set(Some((theme, dark)));
                }
                let backend = root.global::<Backend>();
                let use_24h = kvs::keys::TIME_24H.get().unwrap_or(false);
                let local = crate::util::localtime::to_local(time::OffsetDateTime::now_utc());
                let text = crate::util::localtime::format_clock(local, use_24h);
                if backend.get_time_24h() != use_24h {
                    backend.set_time_24h(use_24h);
                }
                if backend.get_clock_text().as_str() != text {
                    backend.set_clock_text(text.into());
                }
            }
        };
        update_clock();

        // The FPGA boot design draws the logo animation on a fixed light background (starts
        // 500 ms after boot and lasts 0.66 s, see ui/mod.rs). With any theme other than the
        // light default, show the menu in the theme's colors once the animation is done.
        let themed = kvs::keys::DARK_MODE.get().unwrap_or(false)
            || kvs::keys::UI_THEME.get().unwrap_or(0) != 0;
        if themed {
            let root = self.root.clone();
            Timer::single_shot(Duration::from_millis(1300), move || {
                if let Some(root) = root.upgrade() {
                    root.invoke_end_boot_transparency();
                }
            });
        }
        self.clock_timer
            .start(TimerMode::Repeated, Duration::from_secs(1), update_clock);

        self.setup_setup(&state, device);
        self.setup_main_menu(&state, device);
        self.setup_game(&state, device);
        self.setup_tools(&state, device);
        self.setup_settings(&state, device);
        self.setup_cores(&state, device);

        // Check for first boot
        if kvs::keys::SETUP_STAGE.get().unwrap_or_default() == 0 {
            root.invoke_set_screen(ScreenId::Setup);
        }

        // Firmware update notification.
        {
            let last_fw = kvs::keys::LAST_FIRMWARE_VERSION.get();
            if last_fw.as_deref() != Some(env!("CARGO_PKG_VERSION")) {
                kvs::keys::LAST_FIRMWARE_VERSION.set(&env!("CARGO_PKG_VERSION").to_string());

                if last_fw.is_some() {
                    let notification = notifications::Notification::new_long(format!(
                        "Firmware updated to {}",
                        env!("CARGO_PKG_VERSION")
                    ));
                    self.queue_notification(state.clone(), notification);
                }
            }
        }

        let state_ = state.clone();
        backend.on_screen_enter(move |screen| {
            // Called when a new screen is entered, before the new frame is rendered.
            log::info!("Screen enter: {:?}", screen);
            match screen {
                ScreenId::Settings => state_.borrow_mut().on_settings_enter(),
                _ => {}
            }
        });

        backend.on_volume_changed({
            let state_ = state.clone();
            let timer = Timer::default();
            move |value| {
                let volume = ((value * (u8::MAX as i32)) / 100) as u8;
                Device::lock().dac.set_volume(volume).unwrap();
                // Start the timer to hide the bar.
                let state_ = state_.clone();
                timer.start(
                    TimerMode::SingleShot,
                    Duration::from_millis(1000),
                    move || {
                        kvs::keys::VOLUME.set(&volume);
                        Backend::get(&state_.borrow().root.unwrap()).set_volume_visible(false);
                    },
                )
            }
        });

        backend.on_brightness_changed({
            let state_ = state.clone();
            let timer = Timer::default();
            move |value| {
                let brightness = (value as f32) / 100.0;
                Device::lock().set_brightness(brightness);
                // Start the timer to hide the bar.
                let state_ = state_.clone();
                timer.start(
                    TimerMode::SingleShot,
                    Duration::from_millis(1000),
                    move || {
                        kvs::keys::BRIGHTNESS.set(&brightness);
                        Backend::get(&state_.borrow().root.unwrap()).set_brightness_visible(false);
                    },
                )
            }
        });

        backend.on_power_off(|| {
            CoreManager::lock().prepare_for_power_off();
            Device::lock().power_off();
        });

        backend.on_reboot(|| {
            CoreManager::lock().prepare_for_power_off();
            Device::lock().reboot();
        });

        // Utility function: add datetime with delta
        backend.on_datetime_add(settings::settings_datetime_add);
    }
}

/// Theme names, as shown in Settings (index = `UI_THEME`).
pub const THEME_NAMES: &[&str] = &["Game Bub", "DMG", "GBC", "GBA", "NES"];

/// One set of UI colors, as 0xRRGGBB (see res/ui/theme.slint for what each is used for).
struct ThemeColors {
    bg: u32,
    surface: u32,
    surface_alt: u32,
    fg: u32,
    text: u32,
    text_dim: u32,
    text_secondary: u32,
    border: u32,
    border_dim: u32,
    highlight: u32,
    status_bg: u32,
    /// 0: same as fg
    status_fg: u32,
    icon: u32,
    logo_face: u32,
    logo_shadow: u32,
    level: u32,
    icon_dim: u32,
    track: u32,
}

/// Colors of logo.png.
const LOGO_FACE: u32 = 0x810F97;
const LOGO_SHADOW: u32 = 0x5B0B6A;

/// A theme colored after a console, from a few base colors.
const fn console_theme(
    bg: u32, surface: u32, surface_alt: u32, text: u32, text_dim: u32, border: u32, border_dim: u32,
    highlight: u32, status_bg: u32, level: u32, icon_dim: u32, track: u32,
) -> ThemeColors {
    ThemeColors {
        bg, surface, surface_alt, fg: text, text, text_dim, text_secondary: text_dim, border,
        border_dim, highlight, status_bg, status_fg: 0, icon: text, logo_face: LOGO_FACE,
        logo_shadow: LOGO_SHADOW, level, icon_dim, track,
    }
}

/// (light, dark) colors of each theme in `THEME_NAMES`.
const THEMES: &[(ThemeColors, ThemeColors)] = &[
    // Game Bub: the original UI colors. `fg` 0 = the std-widgets text color (see apply_theme).
    (
        ThemeColors {
            bg: 0xE8E8E8, surface: 0xFFFFFF, surface_alt: 0xEEEEEE, fg: 0, text: 0x000000,
            text_dim: 0x808080, text_secondary: 0x666666, border: 0x000000, border_dim: 0x808080,
            highlight: 0xBBBBBB, status_bg: 0xCCCCCC, status_fg: 0, icon: 0x333333, logo_face: LOGO_FACE,
            logo_shadow: LOGO_SHADOW, level: 0x333333,
            icon_dim: 0xAAAAAA, track: 0xD3D3D3,
        },
        ThemeColors {
            bg: 0x1E1E1E, surface: 0x2D2D2D, surface_alt: 0x262626, fg: 0, text: 0xFFFFFF,
            text_dim: 0x8C8C8C, text_secondary: 0xA6A6A6, border: 0xBFBFBF, border_dim: 0x6E6E6E,
            highlight: 0x4A4A4A, status_bg: 0x333333, status_fg: 0, icon: 0xE0E0E0, logo_face: LOGO_FACE,
            logo_shadow: LOGO_SHADOW, level: 0xE0E0E0,
            icon_dim: 0x777777, track: 0x3A3A3A,
        },
    ),
    // DMG
    (
        console_theme(0x9BBB0E, 0xF0F0D0, 0x8BAB0A, 0x0F380F, 0x306230, 0x0F380F, 0x306230,
            0xCADC6A, 0x8BAB0A, 0x0F380F, 0x306230, 0x9BBB0E),
        console_theme(0x0F380F, 0x306230, 0x1B3C1B, 0x9BBB0E, 0x8BAB0A, 0x9BBB0E, 0x8BAB0A,
            0x306230, 0x1B3C1B, 0x9BBB0E, 0x8BAB0A, 0x1B3C1B),
    ),
    // GBC
    (
        console_theme(0xA8E8F0, 0xFFFFFF, 0x50C8E8, 0x001830, 0x005080, 0x006898, 0x40A0C0,
            0x20C0E0, 0x0090B8, 0xCC0000, 0x005080, 0x50C8E8),
        // The default purple logo is hard to see on this background: cyan, like the theme.
        ThemeColors {
            logo_face: 0x40D8F0,
            logo_shadow: 0x1A7890,
            ..console_theme(0x001C28, 0x003C54, 0x002438, 0x40ECFF, 0x30B0D0, 0x20D8FF, 0x106090,
                0x003C58, 0x002030, 0x20D8FF, 0x106090, 0x002438)
        },
    ),
    // GBA
    (
        console_theme(0xDCC0FF, 0xFFFFFF, 0xB070FF, 0x1A0040, 0x5010A0, 0x5C00AA, 0x8840CC,
            0xA050F0, 0xC8A0FF, 0xE8007A, 0x5010A0, 0xB070FF),
        // The default purple logo is hard to see on this background: lighter colors.
        ThemeColors {
            logo_face: 0xD060F0,
            logo_shadow: 0x8A2AB0,
            ..console_theme(0x0E0020, 0x200058, 0x160038, 0xE060FF, 0xA050E0, 0xCC00FF, 0x7700BB,
                0x360080, 0x180048, 0xCC00FF, 0x7700BB, 0x160038)
        },
    ),
    // NES
    (
        // Light gray body, dark gray stripe for the status bar, red (A/B buttons) selection.
        ThemeColors {
            status_fg: 0xE4E4D4,
            // Red logo with a dark gray shadow, like the NES logo on the console.
            logo_face: 0xD01010,
            logo_shadow: 0x3C3C34,
            ..console_theme(0xC8C8BC, 0xECECE4, 0xB0B0A4, 0x181818, 0x585848, 0x181818, 0x686858,
                0xF0A8A0, 0x3C3C34, 0xE40000, 0x585848, 0xA8A898)
        },
        // Black body, red status bar and dark red selection.
        ThemeColors {
            status_fg: 0xFFFFFF,
            logo_face: 0xE83030,
            logo_shadow: 0x7A1010,
            ..console_theme(0x181818, 0x2C2C24, 0x222218, 0xE4E4D4, 0x888878, 0xE4E4D4, 0x585848,
                0x6A1E1E, 0xB80000, 0xE40000, 0x585848, 0x222218)
        },
    ),
];

/// Colors (0xRRGGBB) of the boot logo animation for the theme selected in the settings.
pub struct BootLogoColors {
    pub bg: u32,
    pub face: u32,
    pub shadow: u32,
    /// Brightest color of the glow that sweeps across the face.
    pub glow: u32,
}

pub fn boot_logo_colors() -> BootLogoColors {
    let theme = kvs::keys::UI_THEME.get().unwrap_or(0);
    let dark = kvs::keys::DARK_MODE.get().unwrap_or(false);
    let (light_colors, dark_colors) = THEMES.get(theme as usize).unwrap_or(&THEMES[0]);
    let colors = if dark { dark_colors } else { light_colors };
    BootLogoColors {
        bg: colors.bg,
        face: colors.logo_face,
        shadow: colors.logo_shadow,
        glow: glow_peak(colors.logo_face),
    }
}

/// The face color with its HSB brightness raised by 0.5 (at most 1), as in the original glow.
fn glow_peak(face: u32) -> u32 {
    let channels = [(face >> 16) & 0xFF, (face >> 8) & 0xFF, face & 0xFF];
    let max = channels.iter().copied().max().unwrap_or(0);
    if max == 0 {
        return 0x808080;
    }
    let peak = (max + 128).min(255);
    channels.iter().fold(0, |rgb, &c| (rgb << 8) | ((c * peak + max / 2) / max).min(255))
}

/// Set the UI colors (see res/ui/theme.slint).
fn apply_theme(root: &MainWindow, theme: i32, dark: bool) {
    use super::slint::Theme;
    let (light_colors, dark_colors) = THEMES.get(theme as usize).unwrap_or(&THEMES[0]);
    let colors = if dark { dark_colors } else { light_colors };
    let c = |rgb: u32| slint::Color::from_rgb_u8((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8);
    let t = root.global::<Theme>();
    t.set_bg(c(colors.bg));
    t.set_surface(c(colors.surface));
    t.set_surface_alt(c(colors.surface_alt));
    // fg 0: keep the std-widgets text color (set by set_color_scheme below).
    if colors.fg != 0 {
        t.set_fg(c(colors.fg).into());
        let status_fg = if colors.status_fg != 0 { colors.status_fg } else { colors.fg };
        t.set_status_fg(c(status_fg).into());
    }
    t.set_text(c(colors.text));
    t.set_text_dim(c(colors.text_dim));
    t.set_text_secondary(c(colors.text_secondary));
    t.set_border(c(colors.border));
    t.set_border_dim(c(colors.border_dim));
    t.set_highlight(c(colors.highlight));
    t.set_status_bg(c(colors.status_bg));
    t.set_icon(c(colors.icon));
    t.set_logo_face(c(colors.logo_face));
    t.set_logo_shadow(c(colors.logo_shadow));
    t.set_level(c(colors.level));
    t.set_icon_dim(c(colors.icon_dim));
    t.set_track(c(colors.track));
    root.invoke_set_color_scheme(dark, colors.fg == 0);
}
