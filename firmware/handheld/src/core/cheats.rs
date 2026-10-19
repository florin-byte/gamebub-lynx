//! Cheats chosen in the core's menu, from the game's RetroArch cheat file (`<rom>.cht`, next to the ROM; the
//! format of libretro-database's `cht/` files). Each cheat becomes an entry after the core's own settings: a
//! checkbox, or, for cheats that change exactly the same addresses and whose descriptions start alike
//! (alternatives such as "Start on China / Japan / ..."), one list with "Off" first. Other cheats on the same
//! addresses stay checkboxes, of which one at a time can be on. A change rewrites the core's whole code list at
//! once (core.json `cheats`), so
//! it applies while the game runs; a cheat that only acts at the start of a game needs a reset. The choices are
//! kept in the file's `cheatN_enable` lines, written back when the game is left.

use std::{
    collections::BTreeMap,
    fs::File,
    io::Write as _,
    path::{Path, PathBuf},
};

use slint::SharedString;

use super::{CheatFormat, CoreCheats};
use crate::{device::Device, ui::CoreSettingUiItem, util::atomic_file};

/// Menu ids of the cheat entries: this plus the entry's index (the core's own settings use small ids).
pub const ID_BASE: u16 = 0x8000;
/// A cheat file larger than this is not read (DD3's has 55 cheats in 5 KB).
const MAX_FILE_BYTES: u64 = 256 * 1024;
/// List choices longer than this are shortened, so that the menu keeps room for the entry's label.
const MAX_CHOICE_CHARS: usize = 24;

#[derive(Clone, Copy, PartialEq)]
struct Code {
    address: u32,
    value: u32,
    compare: Option<u32>,
}

struct Cheat {
    desc: String,
    /// Empty when a code of the cheat cannot be decoded (the cheat is not offered).
    codes: Vec<Code>,
    enabled: bool,
}

enum Entry {
    /// A checkbox for one cheat (index into `GameCheats::cheats`).
    Toggle(usize),
    /// A list: "Off", then these cheats.
    Choice { label: String, cheats: Vec<usize> },
}

pub struct GameCheats {
    path: PathBuf,
    config: CoreCheats,
    cheats: Vec<Cheat>,
    entries: Vec<Entry>,
    dirty: bool,
}

impl GameCheats {
    /// Reads `<rom>.cht`; None without a file or without a cheat this core can use.
    pub fn load(rom: &Path, config: &CoreCheats) -> Option<GameCheats> {
        let path = rom.with_extension("cht");
        let size = std::fs::metadata(&path).ok()?.len();
        if size > MAX_FILE_BYTES {
            log::warn!("{} is too large ({} bytes)", path.display(), size);
            return None;
        }
        let text = std::fs::read_to_string(&path).ok()?;
        let cheats: Vec<Cheat> = parse_cht(&text)
            .into_iter()
            .map(|(desc, code, enabled)| {
                let codes = split_codes(&code)
                    .map(|part| decode(config.format, part))
                    .collect::<Option<Vec<_>>>()
                    .unwrap_or_default();
                if codes.is_empty() {
                    log::info!("Cheat {desc:?}: code {code:?} not supported");
                }
                let enabled = enabled && !codes.is_empty();
                Cheat { desc, codes, enabled }
            })
            .collect();
        let mut game = GameCheats {
            path,
            config: config.clone(),
            entries: group(&cheats),
            cheats,
            dirty: false,
        };
        if game.entries.is_empty() {
            return None;
        }
        // At most one cheat on the same addresses can be on: the first one in the file stays.
        for i in 0..game.cheats.len() {
            if game.cheats[i].enabled {
                let same = address_set(&game.cheats[i]);
                for j in i + 1..game.cheats.len() {
                    if game.cheats[j].enabled && address_set(&game.cheats[j]) == same {
                        game.cheats[j].enabled = false;
                    }
                }
            }
        }
        // Never more codes than the core takes: drop the cheats past it.
        let mut count = 0;
        for cheat in game.cheats.iter_mut().filter(|c| c.enabled) {
            count += cheat.codes.len() as u32;
            if count > game.config.max_codes {
                cheat.enabled = false;
            }
        }
        log::info!("{}: {} cheats, {} menu entries", game.path.display(), game.cheats.len(), game.entries.len());
        Some(game)
    }

    /// The menu entries, after the core's own settings.
    pub fn ui_items(&self) -> Vec<CoreSettingUiItem> {
        use crate::ui::slint::CoreSettingType;
        self.entries
            .iter()
            .enumerate()
            .map(|(i, entry)| match entry {
                Entry::Toggle(c) => CoreSettingUiItem {
                    id: ID_BASE + i as u16,
                    label: self.cheats[*c].desc.as_str().into(),
                    setting_type: CoreSettingType::Checkbox,
                    value: self.cheats[*c].enabled as u32,
                    choices: Vec::new(),
                },
                Entry::Choice { label, cheats } => CoreSettingUiItem {
                    id: ID_BASE + i as u16,
                    label: label.as_str().into(),
                    setting_type: CoreSettingType::List,
                    value: cheats.iter().position(|&c| self.cheats[c].enabled).map_or(0, |p| p as u32 + 1),
                    choices: std::iter::once(SharedString::from("Off"))
                        .chain(cheats.iter().map(|&c| shorten(choice_text(label, &self.cheats[c].desc)).into()))
                        .collect(),
                },
            })
            .collect()
    }

    /// A menu entry changed (`id` from `ui_items`, `ui_value` 0 / 1 or the list index): Ok with the notification,
    /// or Err with the reason it was refused (the cheats stay as they were).
    pub fn changed(&mut self, id: u16, ui_value: u32) -> Result<String, String> {
        let entry = id
            .checked_sub(ID_BASE)
            .and_then(|i| self.entries.get(i as usize))
            .ok_or_else(|| format!("Unknown cheat entry {id:#x}"))?;
        let (turned_on, members): (Option<usize>, Vec<usize>) = match entry {
            Entry::Toggle(c) => ((ui_value != 0).then_some(*c), vec![*c]),
            Entry::Choice { cheats, .. } => {
                (ui_value.checked_sub(1).and_then(|k| cheats.get(k as usize).copied()), cheats.clone())
            }
        };
        if let Some(on) = turned_on {
            let same = address_set(&self.cheats[on]);
            if let Some(other) =
                self.cheats.iter().enumerate().find(|(i, c)| *i != on && c.enabled && address_set(c) == same)
                    .filter(|(i, _)| !members.contains(i))
            {
                return Err(format!("Turn off first: {}", other.1.desc).chars().take(48).collect());
            }
        }
        let before: Vec<bool> = self.cheats.iter().map(|c| c.enabled).collect();
        for &c in &members {
            self.cheats[c].enabled = Some(c) == turned_on;
        }
        if self.code_count() > self.config.max_codes {
            for (cheat, was) in self.cheats.iter_mut().zip(before) {
                cheat.enabled = was;
            }
            return Err(format!("Too many cheat codes (max {})", self.config.max_codes));
        }
        self.dirty = true;
        self.send();
        Ok(match turned_on {
            Some(c) => format!("Cheat on: {}", self.cheats[c].desc),
            None => format!("Cheat off: {}", self.cheats[members[0]].desc),
        }
        .chars()
        .take(48)
        .collect())
    }

    fn code_count(&self) -> u32 {
        self.cheats.iter().filter(|c| c.enabled).map(|c| c.codes.len() as u32).sum()
    }

    /// Replaces the core's codes with those of the cheats that are on.
    pub fn send(&self) {
        let mut device = Device::lock();
        let _ = device.fpga.write_u32(self.config.clear, 1);
        let codes = self.cheats.iter().filter(|c| c.enabled).flat_map(|c| c.codes.iter());
        for (i, code) in codes.enumerate() {
            let base = self.config.address + 16 * i as u32;
            let words = [code.compare.is_some() as u32, code.address, code.compare.unwrap_or(0), code.value];
            for (k, word) in words.into_iter().enumerate() {
                let _ = device.fpga.write_u32(base + 4 * k as u32, word);
            }
        }
    }

    /// Writes the choices back into the file's `cheatN_enable` lines, if they changed.
    pub fn persist(&mut self) -> std::io::Result<()> {
        if !self.dirty {
            return Ok(());
        }
        let text = std::fs::read_to_string(&self.path)?;
        let enabled: Vec<bool> = self.cheats.iter().map(|c| c.enabled).collect();
        let tmp = atomic_file::tmp_path(&self.path);
        let mut file = File::create(&tmp)?;
        file.write_all(set_enable_lines(&text, &enabled).as_bytes())?;
        atomic_file::finalize(file, &tmp, &self.path)?;
        self.dirty = false;
        Ok(())
    }
}

/// `(key, value)` of a `key = value` line, the value without its quotes.
fn key_value(line: &str) -> Option<(&str, &str)> {
    let (key, value) = line.split_once('=')?;
    let value = value.trim();
    Some((key.trim(), value.strip_prefix('"').and_then(|v| v.strip_suffix('"')).unwrap_or(value)))
}

/// `cheatN_<field>` -> (N, field).
fn cheat_key(key: &str) -> Option<(usize, &str)> {
    let (n, field) = key.strip_prefix("cheat")?.split_once('_')?;
    Some((n.parse().ok()?, field))
}

/// (description, code, enabled) of every cheat, in cheat number order.
fn parse_cht(text: &str) -> Vec<(String, String, bool)> {
    let mut cheats: BTreeMap<usize, (String, String, bool)> = BTreeMap::new();
    for line in text.lines() {
        let Some((key, value)) = key_value(line) else { continue };
        let Some((n, field)) = cheat_key(key) else { continue };
        let cheat = cheats.entry(n).or_default();
        match field {
            "desc" => cheat.0 = value.replace("&amp;", "&"),
            "code" => cheat.1 = value.to_string(),
            "enable" => cheat.2 = value.eq_ignore_ascii_case("true"),
            _ => {}
        }
    }
    cheats.into_values().collect()
}

/// The text with each cheat's `cheatN_enable` line set (added after `cheatN_code` where missing).
fn set_enable_lines(text: &str, enabled: &[bool]) -> String {
    let has_enable: Vec<usize> = text
        .lines()
        .filter_map(|l| key_value(l).and_then(|(k, _)| cheat_key(k)).filter(|(_, f)| *f == "enable").map(|(n, _)| n))
        .collect();
    let mut out = String::with_capacity(text.len() + 64);
    for line in text.lines() {
        let parsed = key_value(line).and_then(|(k, _)| cheat_key(k));
        match parsed {
            Some((n, "enable")) if n < enabled.len() => {
                out.push_str(&format!("cheat{n}_enable = {}\n", enabled[n]));
            }
            Some((n, "code")) if n < enabled.len() && !has_enable.contains(&n) => {
                out.push_str(line);
                out.push('\n');
                out.push_str(&format!("cheat{n}_enable = {}\n", enabled[n]));
            }
            _ => {
                out.push_str(line);
                out.push('\n');
            }
        }
    }
    out
}

/// The codes of a cheat, joined with `+` (or `,` `;` `_` ` `, as FCEUmm reads them).
fn split_codes(code: &str) -> impl Iterator<Item = &str> {
    code.split(['+', ',', ';', '_', ' ']).filter(|p| !p.is_empty())
}

fn decode(format: CheatFormat, part: &str) -> Option<Code> {
    match format {
        CheatFormat::Nes => decode_nes(part),
    }
}

fn hex(s: &str) -> Option<u32> {
    u32::from_str_radix(s, 16).ok()
}

/// A NES code: raw `AAAA:VV`, `AAAA?CC:VV`, or Game Genie (6 / 8 letters, FCEUmm's FCEUI_DecodeGG).
fn decode_nes(part: &str) -> Option<Code> {
    let b = part.as_bytes();
    if b.len() == 7 && b[4] == b':' {
        return Some(Code { address: hex(&part[..4])?, value: hex(&part[5..])?, compare: None });
    }
    if b.len() == 10 && b[4] == b'?' && b[7] == b':' {
        return Some(Code { address: hex(&part[..4])?, value: hex(&part[8..])?, compare: Some(hex(&part[5..7])?) });
    }
    if b.len() != 6 && b.len() != 8 {
        return None;
    }
    const LETTERS: &[u8] = b"APZLGITYEOXUKSVN";
    let n: Vec<u32> = b
        .iter()
        .map(|c| LETTERS.iter().position(|l| *l == c.to_ascii_uppercase()).map(|p| p as u32))
        .collect::<Option<_>>()?;
    let address = 0x8000 | (n[1] & 8) << 4 | (n[2] & 7) << 4 | (n[3] & 7) << 12 | (n[3] & 8) | (n[4] & 7)
        | (n[4] & 8) << 8 | (n[5] & 7) << 8;
    let value = (n[0] & 7) | (n[0] & 8) << 4 | (n[1] & 7) << 4;
    if n.len() == 6 {
        return Some(Code { address, value: value | (n[5] & 8), compare: None });
    }
    let compare = (n[5] & 8) | (n[6] & 7) | (n[6] & 8) << 4 | (n[7] & 7) << 4;
    Some(Code { address, value: value | (n[7] & 8), compare: Some(compare) })
}

/// The menu entries: cheats that change exactly the same set of addresses form one list (in the order of their
/// first cheat), the others are checkboxes. Cheats that cannot be decoded are left out.
/// The addresses a cheat changes, sorted.
fn address_set(cheat: &Cheat) -> Vec<u32> {
    let mut a: Vec<u32> = cheat.codes.iter().map(|x| x.address).collect();
    a.sort_unstable();
    a.dedup();
    a
}

fn group(cheats: &[Cheat]) -> Vec<Entry> {
    let key = address_set;
    let mut entries = Vec::new();
    let mut used = vec![false; cheats.len()];
    for i in 0..cheats.len() {
        if used[i] || cheats[i].codes.is_empty() {
            continue;
        }
        let k = key(&cheats[i]);
        let members: Vec<usize> =
            (i..cheats.len()).filter(|&j| !used[j] && !cheats[j].codes.is_empty() && key(&cheats[j]) == k).collect();
        for &j in &members {
            used[j] = true;
        }
        let descs: Vec<&str> = members.iter().map(|&j| cheats[j].desc.as_str()).collect();
        match list_label(&descs) {
            Some(label) if members.len() > 1 => entries.push(Entry::Choice { label, cheats: members }),
            _ => entries.extend(members.into_iter().map(Entry::Toggle)),
        }
    }
    entries
}

/// The words the descriptions start with (case-insensitive), if they share some and each has more.
fn list_label(descs: &[&str]) -> Option<String> {
    let words: Vec<Vec<&str>> = descs.iter().map(|d| d.split_whitespace().collect()).collect();
    let mut common = Vec::new();
    for (k, word) in words[0].iter().enumerate() {
        if words.iter().all(|w| w.get(k).is_some_and(|x| x.eq_ignore_ascii_case(word))) {
            common.push(*word);
        } else {
            break;
        }
    }
    // Leave at least one word of each description for its choice.
    (!common.is_empty() && words.iter().all(|w| w.len() > common.len())).then(|| common.join(" "))
}

/// A list choice: the description without the list's label when it starts with it.
fn choice_text<'a>(label: &str, desc: &'a str) -> &'a str {
    match desc.get(..label.len()) {
        Some(head) if head.eq_ignore_ascii_case(label) && desc.len() > label.len() => desc[label.len()..].trim(),
        _ => desc,
    }
}

fn shorten(s: &str) -> String {
    if s.chars().count() <= MAX_CHOICE_CHARS {
        s.to_string()
    } else {
        let mut t: String = s.chars().take(MAX_CHOICE_CHARS - 1).collect();
        t.push('…');
        t
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn game_genie() {
        // nesdev's example.
        let c = decode_nes("GOSSIP").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0xD1DD, 0x14, None));
    }
}
