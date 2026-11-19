use std::{
    fs::File,
    path::{Path, PathBuf},
};

use super::CoreSetting;
use crate::bitstream;

/// Core-specific lifecycle callbacks.
///
/// The main purpose is to add core-specific customizations for built-in cores
/// while generic core functionality is being built.
pub trait CoreHandler {
    /// Called after the bitstream is programmed.
    fn on_after_program(&mut self);

    /// Called before loading a file, returns the path override.
    fn get_file_path_override(&mut self, id: u16) -> Option<PathBuf>;

    /// Called before a file is loaded, for any additional stuff.
    fn on_before_file_load(&mut self, id: u16, file: &mut File) -> Result<(), String>;

    /// Called for each chunk of a file loaded
    fn on_during_file_load(&mut self, _id: u16, _data: &[u8]) {}

    /// Called after a file is loaded, for any additional stuff.
    fn on_after_file_load(&mut self, _id: u16) {}

    fn on_before_run(&mut self) -> Result<(), String>;

    fn on_focus_changed(&mut self, has_focus: bool);

    /// Called before saving a file. May override the core's file size.
    fn get_file_size(&mut self, id: u16) -> Option<u32>;

    /// Called after file is written, to write any additional data.
    fn on_after_file_save(&mut self, id: u16, file: &mut File) -> Result<(), String>;

    /// Temporary: as Bitstream trait
    fn as_legacy_bitstream(&mut self) -> &mut dyn bitstream::Bitstream;

    /// Load initial core settings values
    fn load_settings(&mut self) -> Vec<(u16, u32)>;

    /// Called when a core setting value is updated
    fn on_setting_changed(&mut self, id: u16, value: u32);

    /// GB-162, Dual Core Link waiting room: replace the save in both cores with an empty one
    /// (as when there is no save file) and return its id. None: no link game is armed.
    fn sync_blank_save(&mut self) -> Result<Option<u32>, String> {
        Ok(None)
    }

    /// A transfer of an on-demand file requested by the last setting change, if any.
    fn take_file_request(&mut self) -> Option<FileRequest> {
        None
    }

    /// Called when the core starts and after each file request, to update setting labels
    /// (for example to mark the save-state slots in use). `file_0` is the path of file 0.
    fn update_setting_labels(&mut self, _settings: &mut [CoreSetting], _file_0: Option<&Path>) {}
}

/// Transfer of an on-demand file, at the path of file 0 with another extension.
pub struct FileRequest {
    pub id: u16,
    /// True to load the file into the core, false to save it from the core.
    pub load: bool,
    pub extension: String,
    /// Shown to the user, e.g. "slot 1".
    pub label: String,
}

pub(super) enum CoreHandlerImpl {
    None,
    Gameboy(crate::bitstream::gameboy::Gameboy),
    Gba(crate::bitstream::gba::Gba),
}

impl CoreHandlerImpl {
    pub(super) fn get_mut(&mut self) -> Option<&mut dyn CoreHandler> {
        match self {
            CoreHandlerImpl::None => None,
            CoreHandlerImpl::Gameboy(gameboy) => Some(gameboy),
            CoreHandlerImpl::Gba(gba) => Some(gba),
        }
    }
}
