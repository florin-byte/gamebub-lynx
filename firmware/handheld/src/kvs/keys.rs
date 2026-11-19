use super::KvsKey;

/// Setup / OOBE stage
pub static SETUP_STAGE: KvsKey<u32> = KvsKey::new_with_default("setup-stage", 0);

/// Total uptime, in seconds.
pub static UPTIME: KvsKey<u32> = KvsKey::new_with_default("uptime", 0);

/// The last volume level.
pub static VOLUME: KvsKey<u8> = KvsKey::new_with_default("volume", 128);

/// The last brightness level.
pub static BRIGHTNESS: KvsKey<f32> = KvsKey::new_with_default("brightness", 0.50);

/// Whether dark mode is enabled.
pub static DARK_MODE: KvsKey<bool> = KvsKey::new_with_default("dark-mode", false);

/// Whether to use DMG mode (instead of CGB mode)
pub static GB_IS_DMG: KvsKey<bool> = KvsKey::new_with_default("gb-is-dmg", false);

/// Whether to skip DMG/CGB boot animation
pub static GB_SKIP_BOOT_ANIM: KvsKey<bool> = KvsKey::new_with_default("gb-no-anim", false);

/// DMG color palette
pub static DMG_COLOR_PALETTE: KvsKey<i32> = KvsKey::new_with_default("dmg-colors", 1);

/// CGB color profile
pub static CGB_COLOR_PROFILE: KvsKey<i32> = KvsKey::new_with_default("cgb-colors", 1);

/// Framework-v2 Game Boy button remap. Legacy gb-swap-ab is deliberately not reused.
pub static GB_BUTTON_REMAP: KvsKey<bool> = KvsKey::new_with_default("gb-btn-remap", false);

/// Whether to skip GBA boot animation.
pub static GBA_SKIP_BOOT_ANIM: KvsKey<bool> = KvsKey::new_with_default("gba-no-anim", false);

/// GBA color profile
pub static GBA_COLOR_PROFILE: KvsKey<i32> = KvsKey::new_with_default("gba-colors", 1);

/// Whether to enable Game Boy Player functionality
pub static GBA_ENABLE_GBP: KvsKey<bool> = KvsKey::new_with_default("gba-enable-gbp", true);

/// Framework-v2 GBA button remap. Legacy gba-swap-ab is deliberately not reused.
pub static GBA_BUTTON_REMAP: KvsKey<bool> = KvsKey::new_with_default("gba-btn-remap", false);

/// Startup action.
pub static STARTUP_ACTION: KvsKey<i32> = KvsKey::new_with_default("startup-action", 0);

/// Last firmware version
pub static LAST_FIRMWARE_VERSION: KvsKey<String> = KvsKey::new("last-fw-version");

/// Warn if no GBA bios is provided
pub static GBA_BIOS_WARNING: KvsKey<bool> = KvsKey::new_with_default("gba-warn-bios", true);

/// Last core that was run
pub static LAST_CORE: KvsKey<String> = KvsKey::new("last-core");

/// Whether docked controllers should have A/B and X/Y swapped.
pub static DOCK_SWAP_ABXY: KvsKey<bool> = KvsKey::new_with_default("dock-swap-abxy", false);

/// Time zone: index into `util::localtime::UTC_OFFSET_CHOICES`.
pub static UTC_OFFSET: KvsKey<i32> =
    KvsKey::new_with_default("utc-offset", crate::util::localtime::UTC_OFFSET_DEFAULT);

/// Daylight saving rule: index into `util::localtime::DST_CHOICES`.
pub static DST_RULE: KvsKey<i32> = KvsKey::new_with_default("dst-rule", 0);

/// UI color theme: index into the themes in `ui::state::apply_theme`.
pub static UI_THEME: KvsKey<i32> = KvsKey::new_with_default("ui-theme", 0);

/// Whether times are shown in 24-hour format.
pub static TIME_24H: KvsKey<bool> = KvsKey::new_with_default("time-24h", false);

/// Wireless identity and explicitly chosen partner (MAC and last known NAME).
pub static WL_NAME: KvsKey<String> = KvsKey::new("wl-name");
pub static WL_PEER: KvsKey<Option<([u8; 6], String)>> = KvsKey::new_with_default("wl-peer", None);
/// GB/GBC wireless link at 12 Mbps instead of 1 Mbps (link page setting).
pub static WL_FAST_GB: KvsKey<bool> = KvsKey::new_with_default("wl-fast-gb", false);
/// GB/GBC wireless link by two cores per console and frame-locked buttons (GB-145), off by default.
pub static WL_SYNC_GB: KvsKey<bool> = KvsKey::new_with_default("wl-sync-gb", false);

/// GB-162: a GB/GBC (GBA) game asks "Play / Dual Core Link" when it is chosen. Off by default.
pub static WL_LINK_GB: KvsKey<bool> = KvsKey::new_with_default("wl-link-gb", false);
pub static WL_LINK_GBA: KvsKey<bool> = KvsKey::new_with_default("wl-link-gba", false);
/// Whether to keep built-in screen on when docked.
pub static DOCK_USE_BUILTIN_SCREEN: KvsKey<bool> = KvsKey::new_with_default("dock-screen", false);

/// Whether power-off is actually deep sleep.
pub static DEEP_SLEEP: KvsKey<bool> = KvsKey::new_with_default("deep-sleep", false);

pub fn flush_all() {
    WL_NAME.flush();
    WL_LINK_GB.flush();
    WL_LINK_GBA.flush();
    WL_PEER.flush();
    WL_FAST_GB.flush();
    WL_SYNC_GB.flush();
    SETUP_STAGE.flush();
    UPTIME.flush();
    VOLUME.flush();
    BRIGHTNESS.flush();
    DARK_MODE.flush();
    GB_IS_DMG.flush();
    GB_SKIP_BOOT_ANIM.flush();
    DMG_COLOR_PALETTE.flush();
    CGB_COLOR_PROFILE.flush();
    GBA_SKIP_BOOT_ANIM.flush();
    GBA_COLOR_PROFILE.flush();
    GBA_ENABLE_GBP.flush();
    STARTUP_ACTION.flush();
    LAST_FIRMWARE_VERSION.flush();
    GBA_BIOS_WARNING.flush();
    LAST_CORE.flush();
    DOCK_SWAP_ABXY.flush();
    GB_BUTTON_REMAP.flush();
    GBA_BUTTON_REMAP.flush();
    UTC_OFFSET.flush();
    DST_RULE.flush();
    TIME_24H.flush();
    UI_THEME.flush();
    DOCK_USE_BUILTIN_SCREEN.flush();
    DEEP_SLEEP.flush();
}
