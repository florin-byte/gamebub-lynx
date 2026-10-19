use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::core::{CoreManager, CoreSetting};
use crate::device::Device;
use crate::ui::CoreSettingUiItem;

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct CoreSettings {
    /// A list of files and their most recently selected paths.
    pub file_paths: Vec<(u16, PathBuf)>,

    /// List of persisted settings.
    pub settings: Vec<(u16, u32)>,
}

impl CoreManager {
    pub fn ui_load_settings(&self) {
        use super::CoreSettingType::*;
        let core = match self.core_info.as_ref() {
            Some(x) => x,
            None => return,
        };

        let list = core
            .settings
            .iter()
            .map(|setting| {
                let id = setting.id;

                // This is the underlying u32 value, which is not the same as the UI value.
                let value = self
                    .core_settings
                    .as_ref()
                    .and_then(|s| s.settings.iter().find(|s| s.0 == id))
                    .map(|(_, value)| *value)
                    .unwrap_or_default();
                let ui_value = match &setting.inner {
                    Action { .. } => 0,
                    Checkbox { .. } => (value != 0) as u32,
                    List { items, .. } => items
                        .iter()
                        .position(|item| item.value == value)
                        .unwrap_or(0) as u32,
                };

                let choices = match &setting.inner {
                    List { items, .. } => items.iter().map(|x| x.label.clone()).collect(),
                    _ => Vec::new(),
                };
                CoreSettingUiItem {
                    id,
                    label: setting.label.clone(),
                    setting_type: match &setting.inner {
                        Action { .. } => crate::ui::slint::CoreSettingType::Action,
                        Checkbox { .. } => crate::ui::slint::CoreSettingType::Checkbox,
                        List { .. } => crate::ui::slint::CoreSettingType::List,
                    },
                    value: ui_value,
                    choices,
                }
            })
            .collect::<Vec<_>>();
        let mut list = list;
        if let Some(cheats) = self.cheats.as_ref() {
            list.extend(cheats.ui_items());
        }
        crate::ui::send(crate::ui::Message::CoreSettingsList(list));
    }

    pub fn setting_changed(&mut self, id: u16, ui_value: u32) {
        use super::CoreSettingType::*;
        if id >= super::cheats::ID_BASE {
            let Some(result) = self.cheats.as_mut().map(|c| c.changed(id, ui_value)) else { return };
            let text = match &result {
                Ok(text) | Err(text) => text.clone(),
            };
            crate::ui::send(crate::ui::Message::Notification(crate::ui::Notification::new_short(text)));
            if result.is_err() {
                // Show the entry's old value again.
                self.ui_load_settings();
            }
            return;
        }
        let core = match self.core_info.as_ref() {
            Some(x) => x,
            None => return,
        };
        let setting = match core.settings.iter().find(|x| x.id == id) {
            Some(x) => x,
            None => return,
        };

        // Save updated value in core settings.
        let value = match &setting.inner {
            Action { value, .. } => *value,
            Checkbox { value } => {
                if ui_value != 0 {
                    *value
                } else {
                    0
                }
            }
            List { items, .. } => items.get(ui_value as usize).map(|x| x.value).unwrap_or(0),
        };
        let list = &mut self.core_settings.as_mut().unwrap().settings;
        if let Some(entry) = list.iter_mut().find(|x| x.0 == id) {
            entry.1 = value;
        } else {
            list.push((id, value));
        }

        log::info!("Core setting {} => {}", setting.label, value);
        // On-demand file actions and slot lists are handled here, not written to the core.
        let file_action = match &setting.inner {
            Action { file: Some(file), load, .. } => Some((*file, *load)),
            _ => None,
        };
        if file_action.is_none() && !setting.is_file_slots() {
            self.setting_send(setting, value);
        }
        if let Some((file, load)) = file_action {
            let request = self.slot_file_request(file, load);
            self.run_file_request(request);
            return;
        }

        let request = self.core_handler.get_mut().and_then(|core_handler| {
            core_handler.on_setting_changed(id, value);
            core_handler.take_file_request()
        });
        if let Some(request) = request {
            self.run_file_request(request);
        }
    }

    /// Send an updated setting to the FPGA.
    pub fn setting_send(&self, setting: &CoreSetting, value: u32) {
        let mut device = Device::lock();
        let base = if setting.mask != 0 {
            device.fpga.read_u32(setting.address).unwrap() & setting.mask
        } else {
            0
        };
        let _ = device.fpga.write_u32(setting.address, base | value);
    }
}
