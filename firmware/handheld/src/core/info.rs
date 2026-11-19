use arrayvec::{ArrayString, ArrayVec};
use serde::Deserialize;
use slint::SharedString;
use std::{
    fs::File,
    io::{BufReader, ErrorKind},
    path::{Path, PathBuf},
};

use crate::device::drivers::fpga;

pub const DIR_CORES: &str = "/sdcard/cores/";

const MAX_CORE_BITSTREAMS: usize = 16;
const MAX_CORE_FILES: usize = 16;
const MAX_CORE_SETTINGS: usize = 16;
const FRAMEWORK_ADDR_START: u32 = 0xF000_0000;
const MIN_TRANSFER_SPEED_KBS: u32 = 1_000;
const MAX_TRANSFER_SPEED_KBS: u32 = 1_000_000;

#[derive(Deserialize)]
pub struct CoreListEntry {
    pub id: ArrayString<32>,
    pub name: ArrayString<32>,
    pub author: ArrayString<32>,
}

pub struct CoreInfo {
    pub id: ArrayString<32>,
    #[expect(unused)]
    pub name: ArrayString<32>,
    #[expect(unused)]
    pub author: ArrayString<32>,
    pub is_built_in: bool,
    pub core_dir: PathBuf,
    pub files: Vec<CoreFile>,
    pub settings: Vec<CoreSetting>,
    pub bitstream: PathBuf,
    pub uses_cartridge: CoreCartridgeMode,
    /// The bitstream has framework-v2 button-remap registers: add a "Swap A/B" list
    /// to the core's menu (see `add_swap_ab_setting`). Legacy hardware.swap_ab is ignored.
    pub button_remap: bool,
    /// Cheats chosen in the core's menu, from the game's `.cht` (see `core/cheats.rs`).
    pub cheats: Option<CoreCheats>,
}

/// The `cheats` object of core.json: the core takes cheat codes in MiSTer's layout (16 bytes each: flags with
/// bit 0 = compare, address, compare value, replacement value; 32-bit little-endian words) written from `address`
/// up, after a write of 1 to the register `clear`.
#[derive(Clone, Deserialize)]
pub struct CoreCheats {
    /// How the codes in the `.cht` file are written.
    pub format: CheatFormat,
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub address: u32,
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub clear: u32,
    /// The core's capacity (MiSTer's CODES: 32).
    #[serde(default = "default_max_codes")]
    pub max_codes: u32,
    /// The system's folder under `/sdcard/cheats/` (libretro-database's `cht/<system>`, e.g. "Sega - Game Gear"),
    /// where `<rom name>.cht` is looked for when the ROM has no `.cht` next to it.
    #[serde(default)]
    pub folder: Option<String>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CheatFormat {
    /// NES: Game Genie (6 / 8 letters), raw `AAAA:VV` and `AAAA?CC:VV`.
    Nes,
    /// Master System / Game Gear: Game Genie (`XXX-YYY`, `XXX-YYY-ZZZ`), Pro Action Replay (`00AA-AAVV`) and
    /// raw `AAAA:VV` (Genesis Plus GX's decode_cheat).
    Sms,
    /// Super Nintendo: Pro Action Replay (`AAAAAAVV`), Game Genie (`XXXX-XXXX`) and raw `AAAAAA:VV` (snes9x).
    Snes,
    /// Mega Drive: Game Genie (`XXXX-XXXX`) and Pro Action Replay (`AAAAAA:DDDD`, `AAAAAA:DD`) (Genesis Plus GX).
    Md,
    /// A format this firmware does not know: the core loads without cheats (a core.json written for a newer
    /// firmware must not make the core disappear from the list).
    #[serde(other)]
    Unknown,
}

fn default_max_codes() -> u32 {
    32
}

#[derive(Default, Copy, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CoreCartridgeMode {
    #[default]
    No,
    Yes,
    IfSelected,
}

#[derive(Clone, Deserialize)]
pub struct CoreFile {
    pub id: u16,
    pub label: ArrayString<16>,

    /// If set, the file will be loaded from this path relative to the asset path.
    #[serde(default)]
    pub filename: Option<ArrayString<32>>,

    /// List of file extensions (optional).
    #[serde(default)]
    pub extensions: ArrayVec<ArrayString<8>, 4>,

    /// If true, the core will still run if the file is not loaded.
    #[serde(default = "default_true")]
    pub optional: bool,
    /// If true, file is treated as read-only, won't be saved at core end.
    #[serde(default)]
    pub read_only: bool,
    /// If true, the file path is selected by the user (filtered by extensions).
    #[serde(default)]
    pub user_selected: bool,
    /// If true, dependent on the file with ID 0 (and the path is determined based on that path + this extension).
    #[serde(default)]
    pub dependent_on_0: bool,
    /// If true, if the file is not loaded, the region will still be initialized with 0xFFs.
    #[serde(default)]
    pub initialize: bool,
    /// If true, the file is not automatically loaded at startup.
    #[serde(default)]
    pub on_demand: bool,

    /// The address to load the file to.
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub address: u32,
    /// Maximum size of the file.
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub max_size: u32,
    /// Exact size of the file.
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub exact_size: u32,
    /// Maximum read/write speed when loading/saving the file (in KB/s). Default 5000 KB/sec
    #[serde(default = "default_transfer_speed")]
    pub max_transfer_speed: u32,

    /// Word size during transfer
    /// TODO: remove this, make all transfers 32-bit
    #[serde(skip)]
    #[serde(default = "default_transfer_word_size")]
    pub transfer_word_size: fpga::FpgaSpiWordSize,
}

#[derive(Deserialize)]
pub struct CoreSetting {
    pub id: u16,
    /// User-visible label
    pub label: SharedString,
    /// Address of the setting in the core
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub address: u32,
    /// Mask used when setting the value
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub mask: u32,
    /// Default value
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub default: u32,
    /// Per-type information
    #[serde(flatten)]
    pub inner: CoreSettingType,
}

#[derive(Deserialize)]
#[serde(tag = "type")]
#[serde(rename_all = "lowercase")]
pub enum CoreSettingType {
    Action {
        #[serde(deserialize_with = "deserialize_hex_u32")]
        value: u32,
        /// If set, the action transfers this on-demand file instead of writing `value` to the core: it
        /// saves the file from the core, or loads it into the core if `load` is true. The slot is the one
        /// selected by the list setting with the same `file_slots` (slot 0 without one).
        #[serde(default)]
        file: Option<u16>,
        #[serde(default)]
        load: bool,
    },
    Checkbox {
        #[serde(deserialize_with = "deserialize_hex_u32")]
        value: u32,
    },
    List {
        items: ArrayVec<CoreSettingListItem, 8>,
        /// If set, the list selects a slot of this on-demand file instead of a value in the core: the item
        /// values are slot numbers, and slot N is stored next to file 0 with the extension "ssN" (as the
        /// Game Boy save states). Item labels get " *" when the slot holds a file.
        #[serde(default)]
        file_slots: Option<u16>,
    },
}

impl CoreSetting {
    /// A list that selects the slot of an on-demand file (see `CoreSettingType::List::file_slots`).
    pub fn is_file_slots(&self) -> bool {
        matches!(self.inner, CoreSettingType::List { file_slots: Some(_), .. })
    }
}

#[derive(Deserialize)]
pub struct CoreSettingListItem {
    pub label: SharedString,
    #[serde(deserialize_with = "deserialize_hex_u32")]
    pub value: u32,
}

impl CoreSetting {
    pub fn is_valid_value(&self, val: u32) -> bool {
        match &self.inner {
            CoreSettingType::Action { .. } => val == 0,
            CoreSettingType::Checkbox { value } => *value != 0 && (val == 0 || val == *value),
            CoreSettingType::List { items, .. } => items.iter().any(|item| item.value == val),
        }
    }
}

impl CoreInfo {
    pub fn get_settings_path(&self) -> PathBuf {
        let mut p = PathBuf::from(super::DIR_SETTINGS);
        p.push(self.id);
        p.add_extension("json");
        p
    }
}

/// Get a list of all available cores.
pub fn list_cores() -> Vec<CoreListEntry> {
    // Start with built-in cores.
    let mut cores = vec![
        CoreListEntry {
            id: "Game-Bub.GB".try_into().unwrap(),
            name: "Game Boy / Game Boy Color".try_into().unwrap(),
            author: "Game Bub".try_into().unwrap(),
        },
        CoreListEntry {
            id: "Game-Bub.GBA".try_into().unwrap(),
            name: "Game Boy Advance".try_into().unwrap(),
            author: "Game Bub".try_into().unwrap(),
        },
    ];

    // Iterate over possible core directories.
    #[cfg(not(feature = "no_beta_cores"))]
    if let Ok(entries) = std::fs::read_dir(DIR_CORES) {
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(_) => {
                    log::warn!("Failed to list core file");
                    continue;
                }
            };

            // Skip non-directories
            if !entry
                .metadata()
                .map(|t| t.file_type().is_dir())
                .unwrap_or(false)
            {
                continue;
            }

            let file_name = entry.file_name();
            let Some(folder_name) = file_name.to_str() else {
                continue;
            };
            if folder_name == "Game-Bub.GB" || folder_name == "Game-Bub.GBA" {
                continue;
            }

            /// Helper struct to extract only high-level info from core
            #[derive(Deserialize)]
            struct MinimalCoreInfo {
                metadata: CoreListEntry,
            }

            let info = match try_read_json::<MinimalCoreInfo>(&entry.path(), "core.json") {
                Ok(Some(info)) => info.metadata,
                Ok(None) => continue,
                Err(e) => {
                    log::warn!("{folder_name}: {e}");
                    continue;
                }
            };
            if info.id.as_str() != folder_name {
                log::warn!(
                    "Core ID '{}' does not match folder '{folder_name}'",
                    info.id
                );
                continue;
            }
            cores.push(info);
        }
    }

    cores.sort_by(|a, b| a.name.cmp(&b.name));
    cores
}

/// Get full information for a core.
/// TODO: better error type?
pub fn get_core(id: &str) -> Result<CoreInfo, String> {
    // Handle built-in cores.
    let built_in = match id {
        "Game-Bub.GB" => Some(crate::bitstream::gameboy::Gameboy::get_core_info()),
        "Game-Bub.GBA" => Some(crate::bitstream::gba::Gba::get_core_info()),
        _ => None,
    };
    if let Some(mut core) = built_in {
        add_swap_ab_setting(&mut core);
        return Ok(core);
    }

    #[derive(Deserialize)]
    struct JsonCoreMetadata {
        pub id: ArrayString<32>,
        pub name: ArrayString<32>,
        pub author: ArrayString<32>,
    }

    #[derive(Deserialize)]
    struct JsonCoreBitstream {
        pub target: ArrayString<16>,
        pub filename: ArrayString<32>,
    }

    #[derive(Deserialize, Default)]
    struct JsonCoreHardware {
        #[serde(default)]
        pub cartridge_enable: CoreCartridgeMode,
        #[serde(default)]
        #[expect(unused)]
        pub cartridge_selectable: bool,
        /// The bitstreams have framework-v2 button-remap registers.
        #[serde(default)]
        pub button_remap: bool,
    }

    #[derive(Deserialize)]
    struct JsonCoreInfo {
        metadata: JsonCoreMetadata,
        #[serde(deserialize_with = "deserialize_bounded_vec::<_, _, MAX_CORE_BITSTREAMS>")]
        bitstreams: Vec<JsonCoreBitstream>,
        #[serde(default)]
        hardware: JsonCoreHardware,
        #[serde(default)]
        cheats: Option<CoreCheats>,
    }

    #[derive(Deserialize)]
    struct JsonSettings {
        #[serde(deserialize_with = "deserialize_bounded_vec::<_, _, MAX_CORE_SETTINGS>")]
        settings: Vec<CoreSetting>,
    }

    #[derive(Deserialize)]
    struct JsonFiles {
        #[serde(deserialize_with = "deserialize_bounded_vec::<_, _, MAX_CORE_FILES>")]
        files: Vec<CoreFile>,
    }

    if id.is_empty() || id == "." || id == ".." || id.contains('/') {
        return Err(format!("Invalid core ID '{id}'"));
    }

    let mut core_dir = PathBuf::from(DIR_CORES);
    core_dir.push(id);

    let json_core: JsonCoreInfo = try_read_json(&core_dir, "core.json")?
        .ok_or_else(|| format!("Missing core.json in /cores/{id}"))?;

    if json_core.metadata.id.as_str() != id {
        return Err(format!(
            "core.json: metadata.id '{}' does not match dir '{id}'",
            json_core.metadata.id
        ));
    }
    if json_core.metadata.name.trim().is_empty() || json_core.metadata.author.trim().is_empty() {
        return Err("core.json: metadata name and author cannot be empty".into());
    }

    // Find the right bitstream (TODO: use a visitor that extracts the right one).
    // https://serde.rs/stream-array.html
    let target = get_device_target();
    let bitstream = json_core
        .bitstreams
        .iter()
        .find_map(|b| (b.target.as_str() == target).then_some(b.filename))
        .ok_or_else(|| format!("core.json: no bitstream for target '{target}'"))?;
    if !bitstream.ends_with(".bit") || !validate_subpath(bitstream.as_str()) {
        return Err(format!(
            "core.json: invalid bitstream filename '{bitstream}'"
        ));
    }
    let bitstream_path = core_dir.join(bitstream.as_str());
    if !bitstream_path.is_file() {
        return Err(format!("core.json: bitstream '{bitstream}' not found"));
    }

    let settings = try_read_json::<JsonSettings>(&core_dir, "settings.json")?
        .map(|s| s.settings)
        .unwrap_or_default();
    validate_settings(&settings)?;

    let files = try_read_json::<JsonFiles>(&core_dir, "files.json")?
        .map(|f| f.files)
        .unwrap_or_default();
    validate_files(&files)?;

    let mut core = CoreInfo {
        id: json_core.metadata.id,
        name: json_core.metadata.name,
        author: json_core.metadata.author,
        uses_cartridge: json_core.hardware.cartridge_enable,
        button_remap: json_core.hardware.button_remap,
        is_built_in: false,
        files,
        bitstream: bitstream_path,
        settings,
        core_dir,
        cheats: json_core.cheats,
    };
    add_swap_ab_setting(&mut core);
    Ok(core)
}

/// Add the framework-v2 "Swap A/B" list before Reset, unless JSON already defines the remap.
/// The register is write-only (mask 0); Off must write the identity map, not zero.
/// A new ID and new built-in KVS keys keep legacy checkbox values out of this register.
fn add_swap_ab_setting(core: &mut CoreInfo) {
    if !core.button_remap {
        return;
    }
    // A settings.json made for the stock firmware can have its own entry for the register: keep only that one.
    if core.settings.iter().any(|s| s.address == fpga::REG_CTRL_BUTTON_REMAP_AB) {
        return;
    }
    if core.settings.iter().any(|s| s.id == super::SETTING_SWAP_AB) {
        log::warn!(
            "Core setting id {} is reserved for Swap A/B, not adding it",
            super::SETTING_SWAP_AB
        );
        return;
    }
    let index = core
        .settings
        .iter()
        .rposition(is_reset_action)
        .unwrap_or(core.settings.len());
    core.settings.insert(
        index,
        CoreSetting {
            id: super::SETTING_SWAP_AB,
            label: "Swap A/B".into(),
            address: fpga::REG_CTRL_BUTTON_REMAP_AB,
            mask: 0,
            default: fpga::BUTTON_REMAP_AB_OFF,
            inner: CoreSettingType::List {
                items: [
                    CoreSettingListItem {
                        label: "Off".into(),
                        value: fpga::BUTTON_REMAP_AB_OFF,
                    },
                    CoreSettingListItem {
                        label: "On".into(),
                        value: fpga::BUTTON_REMAP_AB_ON,
                    },
                ]
                .into_iter()
                .collect(),
                file_slots: None,
            },
        },
    );
}

/// The core's Reset entry: an action that writes a core register. Every other action in the cores' menus is
/// handled by the firmware (save / load state, at the "not a register" address `0xFFFF_FFFF`) or transfers a
/// file. Matched by kind rather than by label or address: the label differs ("Reset", "Reset Core") and so
/// does the register (`0x2000` for most cores, `0x0` for Mega Drive).
fn is_reset_action(setting: &CoreSetting) -> bool {
    matches!(setting.inner, CoreSettingType::Action { file: None, .. })
        && setting.address != 0xFFFF_FFFF
}

fn validate_subpath(path: &str) -> bool {
    !path.is_empty()
        && !path.contains('\\')
        && path
            .split('/')
            .all(|s| !s.is_empty() && s != "." && s != "..")
}

fn validate_files(files: &[CoreFile]) -> Result<(), String> {
    let file_0 = files.iter().find(|f| f.id == 0);
    for (i, f) in files.iter().enumerate() {
        let id = f.id;
        if files[..i].iter().any(|prev| prev.id == id) {
            return Err(format!("files.json: duplicate file id {id}"));
        }

        let sources =
            (f.filename.is_some() as u32) + (f.user_selected as u32) + (f.dependent_on_0 as u32);
        // Retro Bub: a file transferred by a firmware action (a save-state slot) has no source of its own,
        // its path is the path of file 0 with the slot's extension.
        let firmware_slot_file = f.on_demand && sources == 0;
        if sources != 1 && !firmware_slot_file {
            return Err(format!(
                "files.json [id={id}]:\nmust set exactly one of filename, user_selected, or dependent_on_0"
            ));
        }

        if f.extensions
            .iter()
            .any(|ext| ext.is_empty() || ext.starts_with('.') || ext.contains('/'))
        {
            return Err(format!("files.json [id={id}]:\ninvalid file extension"));
        }
        if f.user_selected && f.extensions.is_empty() {
            return Err(format!(
                "files.json [id={id}]:\nuser_selected requires extensions"
            ));
        }

        if let Some(filename) = f.filename.as_ref() {
            if !validate_subpath(filename.as_str()) {
                return Err(format!(
                    "files.json [id={id}]:\ninvalid filename '{filename}'"
                ));
            }
        }

        if f.dependent_on_0 {
            let valid_f0 = id != 0
                && !f.extensions.is_empty()
                && file_0.is_some_and(|f0| f0.user_selected && (!f0.optional || f.optional));
            if !valid_f0 {
                return Err(format!(
                    "files.json [id={id}]:\ninvalid dependent_on_0 configuration"
                ));
            }
        }

        if f.max_transfer_speed < MIN_TRANSFER_SPEED_KBS
            || f.max_transfer_speed > MAX_TRANSFER_SPEED_KBS
        {
            return Err(format!(
                "files.json [id={id}]:\ninvalid max_transfer_speed {}",
                f.max_transfer_speed
            ));
        }

        if !f.on_demand && (f.address % 4 != 0 || f.address >= FRAMEWORK_ADDR_START) {
            return Err(format!(
                "files.json [id={id}]: invalid address 0x{:08X}",
                f.address
            ));
        }
    }
    Ok(())
}

fn validate_settings(settings: &[CoreSetting]) -> Result<(), String> {
    for (i, s) in settings.iter().enumerate() {
        let id = s.id;
        if settings[..i].iter().any(|prev| prev.id == id) {
            return Err(format!("settings.json: duplicate setting id {id}"));
        }
        // Retro Bub: a setting handled by the firmware (a file action or a file slot list) writes no
        // register, and a settings.json made for older stock firmware may carry its own Swap A/B entry.
        let firmware_handled = matches!(
            s.inner,
            CoreSettingType::Action { file: Some(_), .. }
                | CoreSettingType::List { file_slots: Some(_), .. }
        ) || s.address == fpga::REG_CTRL_BUTTON_REMAP_AB;
        if !firmware_handled && (s.address % 4 != 0 || s.address >= FRAMEWORK_ADDR_START) {
            return Err(format!(
                "settings.json [id={id}]:\ninvalid address 0x{:08X}",

                s.address
            ));
        }
        if !s.is_valid_value(s.default) {
            return Err(format!(
                "settings.json [id={id}]:\ninvalid default value 0x{:X}",
                s.default
            ));
        }
    }
    Ok(())
}

fn try_read_json<T: serde::de::DeserializeOwned>(
    core_dir: &Path,
    filename: &str,
) -> Result<Option<T>, String> {
    let file = match File::open(core_dir.join(filename)) {
        Ok(file) => file,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(format!("Failed to open {filename}: {e}")),
    };
    let reader = BufReader::with_capacity(256, file);
    serde_json::from_reader(reader)
        .map(Some)
        .map_err(|e| format!("Invalid {filename}: {e}"))
}

fn get_device_target() -> &'static str {
    #[cfg(feature = "rev1")]
    const TARGET: &str = "gamebub_rev1";
    #[cfg(feature = "rev2")]
    const TARGET: &str = "gamebub_rev2";
    #[cfg(feature = "rev3")]
    const TARGET: &str = "gamebub_rev3";
    #[cfg(feature = "rev4")]
    const TARGET: &str = "gamebub_rev4";

    TARGET
}

/// Serde helper to deserialize a Vec with an upper bound enforced during parsing.
fn deserialize_bounded_vec<'de, D, T, const MAX: usize>(deserializer: D) -> Result<Vec<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    struct Visitor<T, const MAX: usize>(Vec<T>);

    impl<'de, T: Deserialize<'de>, const MAX: usize> serde::de::Visitor<'de> for Visitor<T, MAX> {
        type Value = Vec<T>;

        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
            write!(f, "a list with at most {MAX} items")
        }

        fn visit_seq<A: serde::de::SeqAccess<'de>>(
            mut self,
            mut seq: A,
        ) -> Result<Vec<T>, A::Error> {
            while let Some(elem) = seq.next_element()? {
                if self.0.len() == MAX {
                    return Err(serde::de::Error::custom(format_args!(
                        "too many items (max {MAX})"
                    )));
                }
                self.0.push(elem);
            }
            Ok(self.0)
        }
    }

    deserializer.deserialize_seq(Visitor::<T, MAX>(Vec::new()))
}

/// Serde helper to deserialize a u32, either from a hex string or a number
fn deserialize_hex_u32<'de, D>(deserializer: D) -> Result<u32, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum IntOrStr {
        Int(u32),
        Str(ArrayString<10>),
    }
    match IntOrStr::deserialize(deserializer)? {
        IntOrStr::Int(num) => Ok(num),
        IntOrStr::Str(str) => str
            .strip_prefix("0x")
            .and_then(|s| u32::from_str_radix(s, 16).ok())
            .ok_or_else(|| serde::de::Error::custom("expected int or hex string")),
    }
}

fn default_true() -> bool {
    true
}

fn default_transfer_speed() -> u32 {
    5000
}

fn default_transfer_word_size() -> fpga::FpgaSpiWordSize {
    fpga::FpgaSpiWordSize::Bits32
}
