//! On-demand core files: transferred while the core runs, when the core handler asks for it
//! (save states), instead of at core start and exit.

use std::{
    fs::File,
    io::{Read, Write},
    ops::DerefMut as _,
    path::{Path, PathBuf},
    time::Duration,
};

use esp_idf_svc::hal::units::Hertz;

use super::{command, CoreError, CoreError::*, CoreFile, CoreManager, CoreSettingType, FileRequest, Stage};
use crate::{
    device::{
        drivers::fpga::{SpiCommand, MAX_SPI_READ_CLOCK},
        Device,
    },
    ui,
    util::atomic_file,
};

/// The core may need some time to prepare or apply the file (a save state waits for vblank). The Mega
/// Drive's save-state engine takes about 0.2 s and has a 2.5 s watchdog, and its core gives up on a request
/// after 3 s and answers with an error: wait a little longer than that.
const ON_DEMAND_TIMEOUT: Duration = Duration::from_millis(3500);

impl CoreManager {
    /// Path of file 0 (the ROM), if it was loaded from a file.
    pub(super) fn file_0_path(&self) -> Option<PathBuf> {
        let core = self.core_info.as_ref()?;
        let index = core.files.iter().position(|f| f.id == 0)?;
        self.selected_files.get(index)?.clone()
    }

    /// Runs a file transfer requested by the core handler, and tells the user how it went.
    pub(super) fn run_file_request(&mut self, request: FileRequest) {
        if self.stage != Stage::Running {
            return;
        }
        let info = self
            .core_info
            .as_ref()
            .and_then(|core| core.files.iter().find(|f| f.id == request.id && f.on_demand));
        let info = match info {
            Some(info) => info.clone(),
            None => return,
        };
        let path = match self.file_0_path().filter(|p| !p.as_os_str().is_empty()) {
            Some(rom) => rom.with_extension(&request.extension),
            None => {
                notify(format!("{} needs a ROM file", info.label));
                return;
            }
        };

        let result = if request.load {
            if !path.exists() {
                notify(format!("{} {} is empty", info.label, request.label));
                return;
            }
            self.load_on_demand(&info, &path)
        } else {
            self.save_on_demand(&info, &path)
        };
        match result {
            Ok(()) => {
                let verb = if request.load { "loaded from" } else { "saved to" };
                notify(format!("{} {} {}", info.label, verb, request.label));
            }
            Err(e) => {
                log::error!("{} {}: {}", info.label, path.display(), e);
                let verb = if request.load { "Load" } else { "Save" };
                notify(format!("{} {} failed", verb, info.label.to_lowercase()));
            }
        }
        self.update_setting_labels();
    }

    /// The file request of a JSON file action (`CoreSettingType::Action::file`): the slot is the value of
    /// the list setting that selects the slots of the same file, slot 0 without one.
    pub(super) fn slot_file_request(&self, file: u16, load: bool) -> FileRequest {
        let slot = self
            .core_info
            .as_ref()
            .and_then(|core| {
                core.settings.iter().find(|s| {
                    matches!(s.inner, CoreSettingType::List { file_slots: Some(f), .. } if f == file)
                })
            })
            .map(|setting| {
                self.core_settings
                    .as_ref()
                    .and_then(|c| c.settings.iter().find(|x| x.0 == setting.id))
                    .map_or(setting.default, |x| x.1)
            })
            .unwrap_or(0);
        FileRequest {
            id: file,
            load,
            extension: slot_extension(slot),
            label: format!("slot {}", slot + 1),
        }
    }

    /// Updates setting labels: slot lists of JSON cores (slots in use marked with " *"), then the core
    /// handler's own labels (e.g. the Game Boy save-state slots).
    pub(super) fn update_setting_labels(&mut self) {
        let file_0 = self.file_0_path();
        if let Some(core) = self.core_info.as_mut() {
            let rom = file_0.as_deref().filter(|p| !p.as_os_str().is_empty());
            for setting in core.settings.iter_mut() {
                if let CoreSettingType::List { items, file_slots: Some(_) } = &mut setting.inner {
                    for item in items.iter_mut() {
                        let in_use = rom.map_or(false, |p| p.with_extension(slot_extension(item.value)).exists());
                        item.label = format!("Slot {}{}", item.value + 1, if in_use { " *" } else { "" }).into();
                    }
                }
            }
        }
        if let (Some(core), Some(handler)) = (self.core_info.as_mut(), self.core_handler.get_mut()) {
            handler.update_setting_labels(&mut core.settings, file_0.as_deref());
        }
    }

    fn load_on_demand(&mut self, info: &CoreFile, path: &Path) -> Result<(), CoreError> {
        let label = info.label.to_string();
        let mut file = File::open(path).map_err(|_| CannotOpenFile(label.clone()))?;
        let size = file.metadata().map_err(|_| CannotOpenFile(label.clone()))?.len() as u32;
        if info.exact_size != 0 && size != info.exact_size {
            return Err(FileWrongSize(label, info.exact_size, size));
        }
        if info.max_size != 0 && size > info.max_size {
            return Err(FileTooBig(label, info.max_size, size));
        }

        self.run_core_command(
            &[command::FILE_WRITE_START, info.id as u32],
            &mut [],
            ON_DEMAND_TIMEOUT,
        )?;
        let mut scratch = crate::bitstream::SCRATCH.take().expect("scratch buffer");
        let buf = scratch.deref_mut();
        let max_clock = Some(Hertz(info.max_transfer_speed * 1000 * 2));
        let mut transferred = 0u32;
        while transferred < size {
            let n = ((size - transferred) as usize).min(buf.len());
            file.read_exact(&mut buf[..n])
                .map_err(|_| FailedLoadFile(label.clone(), "Read failed".to_string()))?;
            Device::lock()
                .fpga
                .spi_write(
                    max_clock,
                    SpiCommand::new(info.transfer_word_size),
                    info.address + transferred,
                    &buf[..n],
                )
                .map_err(|_| FailedLoadFile(label.clone(), "Transfer failed".to_string()))?;
            transferred += n as u32;
        }
        self.run_core_command(
            &[command::FILE_WRITE_END, info.id as u32, transferred, 0],
            &mut [],
            ON_DEMAND_TIMEOUT,
        )?;
        log::info!("Loaded {} ({} bytes) from {}", label, transferred, path.display());
        Ok(())
    }

    fn save_on_demand(&mut self, info: &CoreFile, path: &Path) -> Result<(), CoreError> {
        let label = info.label.to_string();
        let mut size = 0u32;
        self.run_core_command(
            &[command::FILE_READ_START, info.id as u32],
            std::slice::from_mut(&mut size),
            ON_DEMAND_TIMEOUT,
        )?;
        if size == 0 {
            // A core without support for this file (e.g. an older bitstream) returns no data.
            let _ = self.run_core_command(
                &[command::FILE_READ_END, info.id as u32],
                &mut [],
                ON_DEMAND_TIMEOUT,
            );
            return Err(FailedSaveFile(label, "Not supported by the core".to_string()));
        }

        // Write to a temporary file and rename it over the old one only when complete.
        let tmp_path = atomic_file::tmp_path(path);
        let result = (|| {
            let mut file = File::create(&tmp_path)
                .map_err(|_| FailedSaveFile(label.clone(), "Open failed".to_string()))?;
            let mut scratch = crate::bitstream::SCRATCH.take().expect("scratch buffer");
            let buf = scratch.deref_mut();
            let max_clock =
                Some(Hertz(info.max_transfer_speed * 1000 * 2).min(MAX_SPI_READ_CLOCK));
            let mut done = 0u32;
            while done < size {
                let n = ((size - done) as usize).min(buf.len());
                let _ = Device::lock().fpga.spi_read(
                    max_clock,
                    SpiCommand::new(info.transfer_word_size),
                    info.address + done,
                    &mut buf[..n],
                );
                file.write_all(&buf[..n])
                    .map_err(|_| FailedSaveFile(label.clone(), "Write failed".to_string()))?;
                done += n as u32;
            }
            atomic_file::finalize(file, &tmp_path, path)
                .map_err(|_| FailedSaveFile(label.clone(), "Replace failed".to_string()))
        })();
        self.run_core_command(&[command::FILE_READ_END, info.id as u32], &mut [], ON_DEMAND_TIMEOUT)?;
        result?;
        log::info!("Saved {} ({} bytes) to {}", label, size, path.display());
        Ok(())
    }
}

fn notify(text: String) {
    ui::send(ui::Message::Notification(ui::Notification::new_short(text)));
}

/// File extension of save slot `slot` (the same as the Game Boy save states).
fn slot_extension(slot: u32) -> String {
    format!("ss{}", slot)
}
