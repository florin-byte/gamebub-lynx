//! Cheats chosen on the in-game Cheats page, from the game's RetroArch cheat file (the format of
//! libretro-database's `cht/` files): `<rom>.cht` next to the ROM, or `/sdcard/cheats/<folder>/<rom>.cht` (core.json
//! `cheats.folder`, the database's folder for the system). The page shows the cheats in 3 columns, in sections:
//! first the cheats that share nothing, then the lists (cheats that change exactly the same addresses and whose
//! descriptions start alike, such as "Start on China / Japan / ...": only one can be on), then the cheats whose
//! descriptions start with the same words ("Infinite: Lives / Rings / Time"). Other cheats on the same addresses,
//! of which one at a time can be on, stay single cheats. A change rewrites the core's whole code list at once
//! (core.json `cheats`), so it applies while the game runs; a cheat that only acts at the start of a game needs a
//! reset. The choices are kept in the file's `cheatN_enable` lines, written back when the game is left.

use std::{
    collections::BTreeMap,
    fs::File,
    io::Write as _,
    path::{Path, PathBuf},
};

use super::{CheatFormat, CoreCheats};
use crate::{device::Device, ui::CheatsPage, util::atomic_file};

/// Where `cheats.folder` is.
const DIR_CHEATS: &str = "/sdcard/cheats/";
/// Cells per row of the Cheats page.
pub const COLUMNS: usize = 3;

/// A cheat file larger than this is not read (DD3's has 55 cheats in 5 KB).
const MAX_FILE_BYTES: u64 = 256 * 1024;

#[derive(Clone, Copy, PartialEq)]
struct Code {
    address: u32,
    value: u32,
    compare: Option<u32>,
    /// One byte of a 16-bit bus (MiSTer's Genesis CODES: flag bit 1); the core's address order picks the byte.
    byte: bool,
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

/// What a cell of the Cheats page switches.
#[derive(Clone, Copy, PartialEq, Debug)]
enum Item {
    /// A single cheat (index into `GameCheats::cheats`).
    Toggle(usize),
    /// A choice of a list (index into `GameCheats::entries`, index of the choice).
    Choice(usize, usize),
}

/// A cell of the Cheats page.
struct Cell {
    item: Item,
    /// The text in the cell: the description, without the section's words.
    text: String,
    /// The text in the page's bar when the cell is selected.
    detail: String,
}

/// A row of the Cheats page: a section header, or up to `COLUMNS` cells (indexes into `GameCheats::cells`).
enum Row {
    Header(String),
    Cells { cells: Vec<usize>, radio: bool },
}

pub struct GameCheats {
    path: PathBuf,
    config: CoreCheats,
    cheats: Vec<Cheat>,
    entries: Vec<Entry>,
    cells: Vec<Cell>,
    rows: Vec<Row>,
    dirty: bool,
}

impl GameCheats {
    /// The game's cheat file: `<rom>.cht` next to the ROM, else `/sdcard/cheats/<folder>/<rom name>.cht`.
    fn find_file(rom: &Path, config: &CoreCheats) -> Option<PathBuf> {
        let next_to_rom = rom.with_extension("cht");
        if next_to_rom.is_file() {
            return Some(next_to_rom);
        }
        let folder = config.folder.as_deref()?;
        let name = rom.file_stem()?;
        let path = Path::new(DIR_CHEATS).join(folder).join(name).with_extension("cht");
        path.is_file().then_some(path)
    }

    /// Reads the game's cheat file; None without a file or without a cheat this core can use.
    pub fn load(rom: &Path, config: &CoreCheats) -> Option<GameCheats> {
        let path = Self::find_file(rom, config)?;
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
        let entries = group(&cheats);
        let (cells, rows) = layout(&cheats, &entries);
        let mut game = GameCheats { path, config: config.clone(), entries, cheats, cells, rows, dirty: false };
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

    fn is_on(&self, item: Item) -> bool {
        match item {
            Item::Toggle(c) => self.cheats[c].enabled,
            Item::Choice(e, k) => match &self.entries[e] {
                Entry::Choice { cheats, .. } => self.cheats[cheats[k]].enabled,
                Entry::Toggle(_) => false,
            },
        }
    }

    /// The Cheats page: its rows (cell ids are indexes into the cells) and each cell's bar text.
    pub fn page(&self) -> CheatsPage {
        let rows = self
            .rows
            .iter()
            .map(|row| match row {
                Row::Header(text) => crate::ui::CheatsPageRow { header: Some(format!("{text}:")), cells: Vec::new() },
                Row::Cells { cells, radio } => crate::ui::CheatsPageRow {
                    header: None,
                    cells: cells
                        .iter()
                        .map(|&i| crate::ui::CheatsPageCell {
                            id: i as u16,
                            text: self.cells[i].text.clone(),
                            on: self.is_on(self.cells[i].item),
                            radio: *radio,
                        })
                        .collect(),
                },
            })
            .collect();
        CheatsPage {
            rows,
            details: self.cells.iter().map(|c| c.detail.clone()).collect(),
            nav: navigation(&self.rows, self.cells.len()),
            on_count: self.cells.iter().filter(|c| self.is_on(c.item)).count() as u32,
        }
    }

    /// The cell `id` of the page was pressed: its cheat on or off (a list's choice: on, the others of the list
    /// off; pressed again: off). Ok with the notification, or Err with the reason it was refused.
    pub fn activate(&mut self, id: u16) -> Result<String, String> {
        let item = self.cells.get(id as usize).map(|c| c.item).ok_or_else(|| format!("Unknown cheat {id}"))?;
        let on = self.is_on(item);
        match item {
            Item::Toggle(c) => {
                let entry = self
                    .entries
                    .iter()
                    .position(|e| matches!(e, Entry::Toggle(x) if *x == c))
                    .ok_or_else(|| format!("Unknown cheat {id}"))?;
                self.changed(entry, !on as u32)
            }
            Item::Choice(e, k) => self.changed(e, if on { 0 } else { k as u32 + 1 }),
        }
    }

    /// All the cheats off.
    pub fn all_off(&mut self) {
        for cheat in self.cheats.iter_mut() {
            cheat.enabled = false;
        }
        self.dirty = true;
        self.send();
    }

    /// An entry changed (`ui_value` 0 / 1 for a single cheat, the choice + 1 or 0 = off for a list): Ok with the
    /// notification, or Err with the reason it was refused (the cheats stay as they were).
    fn changed(&mut self, entry: usize, ui_value: u32) -> Result<String, String> {
        let entry = self.entries.get(entry).ok_or_else(|| format!("Unknown cheat entry {entry}"))?;
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
            let flags = code.compare.is_some() as u32 | (code.byte as u32) << 1;
            let words = [flags, code.address, code.compare.unwrap_or(0), code.value];
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
        CheatFormat::Sms => decode_sms(part),
        CheatFormat::Snes => decode_snes(part),
        CheatFormat::Md => decode_md(part),
        CheatFormat::Unknown => None,
    }
}

fn hex(s: &str) -> Option<u32> {
    u32::from_str_radix(s, 16).ok()
}

/// A NES code: raw `AAAA:VV`, `AAAA?CC:VV`, or Game Genie (6 / 8 letters, FCEUmm's FCEUI_DecodeGG).
fn decode_nes(part: &str) -> Option<Code> {
    let b = part.as_bytes();
    if b.len() == 7 && b[4] == b':' {
        return Some(Code { address: hex(&part[..4])?, value: hex(&part[5..])?, compare: None, byte: false });
    }
    if b.len() == 10 && b[4] == b'?' && b[7] == b':' {
        return Some(Code { address: hex(&part[..4])?, value: hex(&part[8..])?, compare: Some(hex(&part[5..7])?), byte: false });
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
        return Some(Code { address, value: value | (n[5] & 8), compare: None, byte: false });
    }
    let compare = (n[5] & 8) | (n[6] & 7) | (n[6] & 8) << 4 | (n[7] & 7) << 4;
    Some(Code { address, value: value | (n[7] & 8), compare: Some(compare), byte: false })
}

/// A Master System / Game Gear code, as Genesis Plus GX's decode_cheat (libretro/libretro.c) reads them:
/// - Game Genie `DDA-AAA` or `DDA-AAA-RXR`: data `DD`; address from the next three digits, with the inverted last
///   digit as its top nibble; the optional reference value from the first and third digits of the last group,
///   rotated right by 2 and XORed with 0xBA;
/// - Pro Action Replay `00AA-AAVV`: address `AAAA`, value `VV`;
/// - raw `AAAA:VV`.
fn decode_sms(part: &str) -> Option<Code> {
    let b = part.as_bytes();
    let digit = |i: usize| (*b.get(i)? as char).to_digit(16);
    if (b.len() == 7 || b.len() == 11) && b[3] == b'-' {
        let value = digit(0)? << 4 | digit(1)?;
        let address = digit(2)? << 8 | digit(4)? << 4 | digit(5)? | (digit(6)? ^ 0xF) << 12;
        if b.len() == 7 {
            return Some(Code { address, value, compare: None, byte: false });
        }
        if b[7] != b'-' {
            return None;
        }
        let r = digit(8)? << 4 | digit(10)?;
        let compare = ((r >> 2) | (r & 3) << 6) ^ 0xBA;
        return Some(Code { address, value, compare: Some(compare), byte: false });
    }
    if b.len() == 9 && b[4] == b'-' {
        let address = digit(2)? << 12 | digit(3)? << 8 | digit(5)? << 4 | digit(6)?;
        return Some(Code { address, value: digit(7)? << 4 | digit(8)?, compare: None, byte: false });
    }
    if b.len() == 7 && b[4] == b':' {
        return Some(Code { address: hex(&part[..4])?, value: hex(&part[5..])?, compare: None, byte: false });
    }
    None
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

fn all_hex(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|c| c.is_ascii_hexdigit())
}

/// A Super Nintendo code, as snes9x reads them (cheats2.cpp): Pro Action Replay `AAAAAAVV`, Game Genie `XXXX-XXXX`
/// (its letters mapped from "DF4709156BC8A23E" to hex, then the address bits reordered) and raw `AAAAAA:VV`
/// (snes9x's `%x : %x`); snes9x's other text forms (with a compare value) are not read.
fn decode_snes(part: &str) -> Option<Code> {
    let b = part.as_bytes();
    if b.len() == 8 && all_hex(part) {
        let data = u32::from_str_radix(part, 16).ok()?;
        return Some(Code { address: data >> 8, value: data & 0xFF, compare: None, byte: false });
    }
    if b.len() == 9 && b[4] == b'-' && all_hex(&part[..4]) && all_hex(&part[5..]) {
        const GENIE: &[u8] = b"DF4709156BC8A23E";
        let mut data = 0u32;
        for &c in b.iter().filter(|&&c| c != b'-') {
            let n = GENIE.iter().position(|&g| g == c.to_ascii_uppercase())? as u32;
            data = data << 4 | n;
        }
        let a = data & 0xFF_FFFF;
        let address = ((a & 0x003C00) << 10)
            + ((a & 0x00003C) << 14)
            + ((a & 0xF00000) >> 8)
            + ((a & 0x000003) << 10)
            + ((a & 0x00C000) >> 6)
            + ((a & 0x0F0000) >> 12)
            + ((a & 0x0003C0) >> 6);
        return Some(Code { address, value: data >> 24, compare: None, byte: false });
    }
    let (a, v) = part.split_once(':')?;
    if (1..=6).contains(&a.len()) && all_hex(a) && (1..=2).contains(&v.len()) && all_hex(v) {
        return Some(Code { address: hex(a)?, value: hex(v)?, compare: None, byte: false });
    }
    None
}

/// A Mega Drive code, as Genesis Plus GX's decode_cheat (libretro/libretro.c) reads them: Game Genie `XXXX-XXXX`
/// (letters "ABCDEFGHJKLMNPRSTVWXYZ0123456789"), Pro Action Replay `AAAAAA:DDDD` and `AAAAAA:DD`. A Work RAM code
/// (address 0xFF0000 and up) with a value below 0x100 changes one byte, as Genesis Plus GX writes it; the others a
/// 16-bit word.
fn decode_md(part: &str) -> Option<Code> {
    let b = part.as_bytes();
    if b.len() == 9 && b[4] == b'-' {
        const LETTERS: &[u8] = b"ABCDEFGHJKLMNPRSTVWXYZ0123456789";
        let (mut address, mut data) = (0u32, 0u32);
        for (i, &c) in b.iter().filter(|&&c| c != b'-').enumerate() {
            let n = LETTERS.iter().position(|&l| l == c.to_ascii_uppercase())? as u32;
            match i {
                0 => data |= n << 3,
                1 => {
                    data |= n >> 2;
                    address |= (n & 3) << 14;
                }
                2 => address |= n << 9,
                3 => address |= (n & 0xF) << 20 | (n >> 4) << 8,
                4 => {
                    data |= (n & 1) << 12;
                    address |= (n >> 1) << 16;
                }
                5 => data |= (n & 1) << 15 | (n >> 1) << 8,
                6 => {
                    data |= (n >> 3) << 13;
                    address |= (n & 7) << 5;
                }
                _ => address |= n,
            }
        }
        return Some(Code { address, value: data, compare: None, byte: false });
    }
    let (a, v) = part.split_once(':')?;
    if a.len() == 6 && all_hex(a) && (v.len() == 2 || v.len() == 4) && all_hex(v) {
        let (address, value) = (hex(a)?, hex(v)?);
        let byte = address >= 0xFF_0000 && value < 0x100;
        return Some(Code { address, value, compare: None, byte });
    }
    None
}

/// The Cheats page: the single cheats first (without a header), then the lists (one section each, only one on),
/// then the cheats whose descriptions start with the same words (2 or more, or one word of 7 letters or more,
/// such as "Infinite"), one section each, named after those words, with the rest of each description in its cell.
fn layout(cheats: &[Cheat], entries: &[Entry]) -> (Vec<Cell>, Vec<Row>) {
    let words = |c: usize| cheats[c].desc.split_whitespace().collect::<Vec<_>>();
    // A description already shown (any case) is not shown again: libretro's files often have the same cheat in
    // two code formats ("Infinite Lives" / "Infinite lives"). The file keeps every cheat.
    let mut toggles: Vec<usize> = Vec::new();
    for entry in entries {
        if let Entry::Toggle(c) = entry {
            if !toggles.iter().any(|&t| cheats[t].desc.eq_ignore_ascii_case(&cheats[*c].desc)) {
                toggles.push(*c);
            }
        }
    }
    let mut used = vec![false; cheats.len()];
    // (header, members, words of the header)
    let mut groups: Vec<(String, Vec<usize>, usize)> = Vec::new();
    for &t in &toggles {
        if used[t] {
            continue;
        }
        let wt = words(t);
        for k in (1..wt.len()).rev() {
            if k == 1 && wt[0].chars().count() < 7 {
                continue;
            }
            let members: Vec<usize> = toggles
                .iter()
                .copied()
                .filter(|&c| {
                    let w = words(c);
                    !used[c] && w.len() > k && (0..k).all(|i| w[i].eq_ignore_ascii_case(wt[i]))
                })
                .collect();
            if members.len() >= 2 {
                for &m in &members {
                    used[m] = true;
                }
                groups.push((wt[..k].join(" "), members, k));
                break;
            }
        }
    }

    let mut cells: Vec<Cell> = Vec::new();
    let mut rows: Vec<Row> = Vec::new();
    let mut section = |header: Option<String>, items: Vec<(Item, String, String)>, radio: bool| {
        if items.is_empty() {
            return;
        }
        if let Some(header) = header {
            rows.push(Row::Header(header));
        }
        for chunk in items.chunks(COLUMNS) {
            let first = cells.len();
            for (item, text, detail) in chunk {
                cells.push(Cell { item: *item, text: text.clone(), detail: detail.clone() });
            }
            rows.push(Row::Cells { cells: (first..cells.len()).collect(), radio });
        }
    };
    let single = toggles
        .iter()
        .filter(|&&t| !used[t])
        .map(|&t| (Item::Toggle(t), cheats[t].desc.clone(), cheats[t].desc.clone()))
        .collect();
    section(None, single, false);
    for (e, entry) in entries.iter().enumerate() {
        if let Entry::Choice { label, cheats: members } = entry {
            let items = members
                .iter()
                .enumerate()
                .map(|(k, &c)| {
                    let text = choice_text(label, &cheats[c].desc).to_string();
                    let detail = format!("{label}: {text}  (only one)");
                    (Item::Choice(e, k), text, detail)
                })
                .collect();
            section(Some(label.clone()), items, true);
        }
    }
    for (header, members, k) in groups {
        let items = members
            .iter()
            .map(|&m| (Item::Toggle(m), words(m)[k..].join(" "), cheats[m].desc.clone()))
            .collect();
        section(Some(header), items, false);
    }
    (cells, rows)
}

/// For each cell: its row and the cells above, below, left and right (itself at an edge). Up and down go to the
/// nearest row of cells (over headers), to the cell in the same column or the row's last one.
fn navigation(rows: &[Row], cell_count: usize) -> Vec<[u16; 5]> {
    let cell_rows: Vec<(usize, &Vec<usize>)> = rows
        .iter()
        .enumerate()
        .filter_map(|(r, row)| if let Row::Cells { cells, .. } = row { Some((r, cells)) } else { None })
        .collect();
    let mut nav = vec![[0u16; 5]; cell_count];
    for (k, (r, cells)) in cell_rows.iter().enumerate() {
        for (c, &id) in cells.iter().enumerate() {
            let at = |row: &Vec<usize>| row[c.min(row.len() - 1)] as u16;
            let up = if k > 0 { at(cell_rows[k - 1].1) } else { id as u16 };
            let down = cell_rows.get(k + 1).map_or(id as u16, |(_, row)| at(row));
            let left = if c > 0 { cells[c - 1] } else { id } as u16;
            let right = cells.get(c + 1).copied().unwrap_or(id) as u16;
            nav[id] = [*r as u16, up, down, left, right];
        }
    }
    nav
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


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn game_genie() {
        // nesdev's example.
        let c = decode_nes("GOSSIP").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0xD1DD, 0x14, None));
    }

    #[test]
    fn sms_codes() {
        // Game Genie: data 0x18; address F, B, 1 and the inverted D (0x2) as the top nibble.
        let c = decode_sms("18F-B1D").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0x2FB1, 0x18, None));
        // With a reference value: 0x6A rotated right by 2 (0x9A), XOR 0xBA.
        let c = decode_sms("18F-B1D-6EA").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0x2FB1, 0x18, Some(0x20)));
        // Pro Action Replay.
        let c = decode_sms("00D2-AC10").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0xD2AC, 0x10, None));
        // Raw.
        let c = decode_sms("C000:05").unwrap();
        assert_eq!((c.address, c.value, c.compare), (0xC000, 0x05, None));
        assert!(decode_sms("00C4-22XX").is_none());
    }

    #[test]
    fn snes_codes() {
        // Expected values from a port of snes9x's S9xGameGenieToRaw.
        let c = decode_snes("D3E6-E4A4").unwrap();
        assert_eq!((c.address, c.value), (0xC0FB8B, 0x0E));
        let c = decode_snes("7F80CAFF").unwrap();
        assert_eq!((c.address, c.value), (0x7F80CA, 0xFF));
        let c = decode_snes("7E1F88:63").unwrap();
        assert_eq!((c.address, c.value), (0x7E1F88, 0x63));
        assert!(decode_snes("##68-A4AD").is_none());
    }

    #[test]
    fn md_codes() {
        // Expected values from a port of Genesis Plus GX's decode_cheat.
        let c = decode_md("ATGA-AA56").unwrap();
        assert_eq!((c.address, c.value, c.byte), (0x000C7C, 0x6004, false));
        let c = decode_md("FFC016:000A").unwrap();
        assert_eq!((c.address, c.value, c.byte), (0xFFC016, 0x0A, true));
        let c = decode_md("FFA4D3:59").unwrap();
        assert_eq!((c.address, c.value, c.byte), (0xFFA4D3, 0x59, true));
        let c = decode_md("FFC016:1234").unwrap();
        assert_eq!((c.address, c.value, c.byte), (0xFFC016, 0x1234, false));
        assert!(decode_md("FFFA48:0?").is_none());
    }
}
