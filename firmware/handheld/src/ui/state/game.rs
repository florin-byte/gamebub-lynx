use std::{cell::RefCell, rc::Rc, time::Duration};

use super::super::slint::Backend;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::{
    device::Device,
    ui::slint::{CoreSettingType, ScreenId},
    worker,
};

use super::UiState;

/// A cell of the Cheats page.
pub struct CheatsPageCell {
    pub id: u16,
    pub text: String,
    pub on: bool,
    /// One of a list: only one can be on.
    pub radio: bool,
}

/// A row of the Cheats page: a section header, or cells.
pub struct CheatsPageRow {
    pub header: Option<String>,
    pub cells: Vec<CheatsPageCell>,
}

/// The Cheats page: its rows, the bar text of each cell (by id), the number of cheats on.
pub struct CheatsPage {
    pub rows: Vec<CheatsPageRow>,
    pub details: Vec<String>,
    /// For each cell id: its row and the cells above, below, left and right (itself at an edge).
    pub nav: Vec<[u16; 5]>,
    pub on_count: u32,
}

pub struct CoreSettingUiItem {
    pub id: u16,
    pub label: SharedString,
    pub setting_type: CoreSettingType,
    pub value: u32,
    pub choices: Vec<SharedString>,
}

impl UiState {
    /// Set up the "Game" screen.
    pub(super) fn setup_game(&mut self, state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let choices = Rc::new(RefCell::new(Vec::<crate::link::Partner>::new()));
        let choices_ = choices.clone();
        let weak = root.as_weak();
        backend.on_link_partners_load(move || {
            let Some(root) = weak.upgrade() else { return };
            let list = crate::link::partners();
            let mut labels = vec![SharedString::from("Any — pair automatically")];
            labels.extend(list.iter().map(|p| {
                let suffix = if p.seen { "" } else { " — not seen" };
                SharedString::from(format!("{} ({:02X}{:02X}){}", p.name, p.mac[4], p.mac[5], suffix))
            }));
            *choices_.borrow_mut() = list;
            root.global::<Backend>().set_link_partners(ModelRc::from(Rc::new(VecModel::from(labels))));
        });
        backend.on_link_partner_choose(move |index| {
            if index == 0 { crate::link::set_partner(None); }
            else if let Some(peer) = choices.borrow().get((index - 1) as usize) {
                crate::link::set_partner(Some(peer.mac));
            }
        });

        let weak = root.as_weak();
        backend.on_link_fast_gb_toggle(move || {
            let enabled = !crate::link::fast_gb();
            crate::link::set_fast_gb(enabled);
            if let Some(root) = weak.upgrade() {
                root.global::<Backend>().set_link_fast_gb(enabled);
            }
        });
        backend.set_link_fast_gb(crate::link::fast_gb());

        backend.on_link_toggle(|| {
            if crate::link::status().state == crate::link::State::Off {
                crate::link::request_start();
            } else {
                crate::link::request_stop();
            }
        });
        let weak = root.as_weak();
        // What the waiting room shows now (the properties are set only when it changes), the
        // frame count of the last tick and for how many ticks it has stood still.
        let room = RefCell::new((RoomView::default(), 0u32, 0u32, false));
        // A in the waiting room: play together, or, when only the saves differ, play without them.
        backend.on_link_room_accept(|| {
            use crate::link::{SessionPhase, SessionWhy};
            let session = crate::link::session();
            if session.phase == SessionPhase::Refused && session.why == SessionWhy::Save {
                worker::send(worker::Message::SyncBlankSave);
            } else {
                crate::link::session_accept();
            }
        });
        self.link_timer.start(slint::TimerMode::Repeated, Duration::from_millis(500), move || {
            let Some(root) = weak.upgrade() else { return };
            let backend = root.global::<Backend>();
            // GB-162: the waiting room of a two-core link game.
            {
                use crate::link::SessionPhase as Phase;
                let session = crate::link::session();
                let mut shown = room.borrow_mut();
                if session.phase == Phase::Running && session.frames == shown.1 {
                    shown.2 += 1;
                } else {
                    shown.2 = 0;
                }
                shown.1 = session.frames;
                let diverged = session.phase == Phase::Diverged;
                if diverged && !shown.3 {
                    // The game goes on, so no window over it.
                    crate::ui::send(crate::ui::Message::Notification(
                        crate::ui::Notification::new_long("Dual Core Link out of sync".to_string())));
                }
                shown.3 = diverged;
                let partner = match session.phase {
                    Phase::Off | Phase::Diverged => String::new(),
                    _ => crate::link::status().partner,
                };
                let view = RoomView::new(&session, &partner, &crate::link::game_title(), shown.2 >= 2);
                if view != shown.0 {
                    backend.set_link_room_status(view.status.as_str().into());
                    backend.set_link_room_lines(ModelRc::from(Rc::new(VecModel::from(
                        view.lines.iter().map(|l| SharedString::from(l.as_str())).collect::<Vec<_>>()))));
                    backend.set_link_room_hint(view.hint.into());
                    backend.set_link_room_can_exit(view.can_exit);
                    backend.set_link_room_can_accept(view.can_accept);
                    backend.set_link_room_open(!view.status.is_empty());
                    shown.0 = view;
                }
            }
            if !backend.get_link_page_open() { return; }
            let status = crate::link::status();
            let (text, detail) = match status.page_state {
                1 => ("Searching...".to_string(), format!("Channel: {}", status.channel)),
                2 => (format!("Connected to \"{}\"", status.partner), format!("Channel: {}", status.channel)),
                3 => ("Partner lost".to_string(), format!("Waiting for \"{}\"...", status.partner)),
                4 => ("Not enough memory".to_string(), String::new()),
                _ => ("Off".to_string(), String::new()),
            };
            backend.set_link_status(text.into());
            backend.set_link_detail(detail.into());
            backend.set_link_active(status.state != crate::link::State::Off);
            backend.set_link_fast_gb(crate::link::fast_gb());
        });

        backend.on_game_set_focused(move |focused| {
            worker::send(worker::Message::CoreFocusChanged(focused));
        });

        backend.on_game_reset(move || {
            worker::send(worker::Message::ResetCore);
        });

        let state_ = state.clone();
        backend.on_game_settings_load(move || {
            // Clear existing settings
            let state = state_.borrow_mut();
            let root: crate::ui::slint::MainWindow = state.root.unwrap();
            let backend = root.global::<Backend>();
            backend.set_core_settings(ModelRc::default());

            // Fetch new settings
            worker::send(worker::Message::CoreSettingsLoad);
        });
        backend.on_cheats_load(move || worker::send(worker::Message::CheatsLoad));
        backend.on_cheat_activate(move |id| worker::send(worker::Message::CheatActivate(id as u16)));
        backend.on_cheats_all_off(move || worker::send(worker::Message::CheatsAllOff));
        backend.on_cheats_page_open(move |open| worker::send(worker::Message::CheatsPageOpen(open)));

        backend.on_game_settings_set(move |id, value| {
            worker::send(worker::Message::CoreSettingChanged {
                id: id as u16,
                value: value as u32,
            });
        });

        let state_ = state.clone();
        backend.on_game_exit(move || {
            // Release the Cheats model before constructing the next screen.
            state_.borrow_mut().game_cheats_available(false);
            // GB-163: the next screen needs the memory the game and the radio hold, so wait until
            // the worker has saved and stopped the radio (it loads the boot bitstream right
            // after, which also avoids a screen flash). A stuck worker must not hold the UI.
            let (released, wait) = std::sync::mpsc::channel();
            worker::send(worker::Message::ExitCore(Some(released)));
            if wait.recv_timeout(Duration::from_secs(3)).is_err() {
                log::error!("Exit core: memory not released after 3 s, going on");
            }
            // Go back to the menu
            let (root, game_cartridge) = {
                let state = state_.borrow_mut();
                (state.root.unwrap(), state.game_cartridge)
            };
            if game_cartridge {
                root.invoke_set_screen(ScreenId::MainMenu);
            } else {
                root.invoke_set_screen(ScreenId::Cores);
            }
        });
    }

    pub fn game_cheats_available(&mut self, available: bool) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_cheats_available(available);
        if !available {
            backend.set_cheat_cells(ModelRc::default());
            backend.set_cheat_details(ModelRc::default());
            backend.set_cheats_on(0);
        }
    }

    pub fn game_cheats_page(&mut self, page: CheatsPage) {
        use crate::ui::slint::CheatCell;

        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        if !backend.get_cheats_available() {
            return;
        }

        fn display_text(mut text: String, limit: usize) -> SharedString {
            let mut chars = text.char_indices();
            if let Some((byte, _)) = chars.nth(limit - 1) {
                if chars.next().is_some() {
                    text.truncate(byte);
                    text.push('…');
                }
            }
            text.into()
        }

        let mut cells = Vec::with_capacity(page.nav.len());
        for row in page.rows.into_iter().filter(|row| row.header.is_none()) {
            cells.extend(row.cells.into_iter().map(|cell| CheatCell {
                id: cell.id as i32,
                text: display_text(cell.text, 11),
                on: cell.on,
                radio: cell.radio,
            }));
        }
        let details: Vec<SharedString> = page
            .details
            .into_iter()
            .map(|text| display_text(text, 56))
            .collect();

        backend.set_cheat_cells(ModelRc::from(Rc::new(VecModel::from(cells))));
        backend.set_cheat_details(ModelRc::from(Rc::new(VecModel::from(details))));
        backend.set_cheats_on(page.on_count as i32);
    }

    pub fn game_settings_list(&mut self, list: Vec<CoreSettingUiItem>) {
        let list = ModelRc::from(Rc::new(VecModel::from(
            list.into_iter()
                .map(|item| crate::ui::slint::CoreSetting {
                    id: item.id as i32,
                    label: item.label,
                    r#type: item.setting_type,
                    value: item.value as i32,
                    choices: ModelRc::from(Rc::new(VecModel::from(item.choices))),
                })
                .collect::<Vec<_>>(),
        )));

        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_core_settings(list);
    }
}

/// GB-162: what the waiting room of a link game shows. The first line says who this console is
/// connected to, the lines below what the player can do about it, in the player's words.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct RoomView {
    /// Empty: no window.
    status: String,
    lines: Vec<String>,
    hint: &'static str,
    can_exit: bool,
    can_accept: bool,
}

impl RoomView {
    fn new(session: &crate::link::Session, partner: &str, title: &str, stalled: bool) -> RoomView {
        use crate::link::{SessionPhase as Phase, SessionWhy as Why};
        // Until a console's own name arrives it is listed under a default one; a console that
        // talks about a link game is a Game Bub.
        let speaks = !matches!(session.phase, Phase::Searching | Phase::Waiting | Phase::RadioOff | Phase::NoMemory)
            && session.why != Why::Silent;
        let partner = match partner.strip_prefix("CHROMATIC ") {
            Some(id) if speaks => format!("GAMEBUB {}", id),
            _ => partner.to_string(),
        };
        let other = if partner.is_empty() { "the other console".to_string() } else { partner.clone() };
        let connected = format!("Connected to {}", other);
        let title = if title.is_empty() { "this game" } else { title };
        let started = session.player != 0;
        let lines = |l: &[&str]| l.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        const EXIT: &str = "B: Exit";
        let (status, lines, hint, can_exit, can_accept) = match (session.phase, session.why) {
            (Phase::Off, _) | (Phase::Diverged, _) => (String::new(), vec![], "", false, false),
            (Phase::NoMemory, _) => ("Not enough memory for Wireless link".to_string(),
                lines(&["Restart the console and", "start the game again."]), EXIT, true, false),
            (Phase::RadioOff, _) => ("Starting wireless link...".to_string(), vec![], EXIT, true, false),
            (Phase::Searching, _) => ("Looking for another console".to_string(),
                lines(&["On the other console start:", title]), EXIT, true, false),
            (Phase::Waiting, _) => (connected, lines(&["Checking its game..."]), EXIT, true, false),
            (Phase::Refused, Why::Silent) => (connected,
                lines(&["It is not in Dual Core Link yet.", "Start there too:", title]), EXIT, true, false),
            // The GB/GBC mode is part of the game's identity, and the name leaves out the ROM
            // revision: the same name refused as another game is one of these two.
            (Phase::Refused, Why::Game) if session.partner_title == title => (connected,
                lines(&["Same game, but not identical.", "Check GB mode on both", "and use the same ROM file."]),
                EXIT, true, false),
            (Phase::Refused, Why::Game) | (Phase::Refused, Why::System) => (connected,
                lines(&["It is playing:",
                    if session.partner_title.is_empty() { "another game" } else { session.partner_title.as_str() },
                    "You need the same game."]), EXIT, true, false),
            // Nothing of a Dual Core Link game is written to the card, so the two can play with
            // an empty save in place of their own. Both have to choose it.
            (Phase::Refused, Why::Save) if session.blank_save => (connected,
                lines(&["Playing without saves.", "Waiting for the other", "console to choose the same."]), EXIT, true, false),
            (Phase::Refused, Why::Save) => (connected,
                lines(&["Your save files are different.", "Play without saves?", "The saves on the card stay."]),
                "A: Play · B: Exit", true, true),
            (Phase::Refused, Why::Firmware) => (connected,
                lines(&["It has another system version.", "Update both consoles."]), EXIT, true, false),
            (Phase::Refused, _) => (connected, lines(&["Dual Core Link settings differ."]), EXIT, true, false),
            (Phase::Found, _) => (format!("Found {}", other),
                if session.partner_accepted { lines(&["It wants to play:", title, "Play together?"]) }
                else { lines(&[title, "Play together?"]) },
                "A: Play together · B: Exit", true, true),
            (Phase::Accepted, _) => (connected, lines(&["Waiting for it to accept..."]), EXIT, true, false),
            (Phase::Running, _) if session.frames < 2 =>
                ("Starting the game...".to_string(), vec![], "", false, false),
            (Phase::Running, _) if stalled => (format!("Waiting for {}...", other), vec![], "Home: Menu", false, false),
            (Phase::Running, _) => (String::new(), vec![], "", false, false),
            (Phase::Lost, _) => (if partner.is_empty() { "Connection lost".to_string() }
                    else { format!("Connection to {} lost", partner) },
                lines(&["Trying to reconnect..."]), if started { "Home: Menu" } else { EXIT }, !started, false),
            (Phase::Failed, Why::Core) => ("Dual Core Link is not available".to_string(),
                lines(&["for this game system."]), EXIT, true, false),
            (Phase::Failed, Why::Internal) | (Phase::Failed, Why::Settings) => ("Dual Core Link error".to_string(),
                lines(&["Start the game again."]), EXIT, true, false),
            (Phase::Failed, _) => (format!("{} left the game", other),
                lines(&["The Dual Core Link game is over."]), EXIT, true, false),
        };
        RoomView { status, lines, hint, can_exit, can_accept }
    }
}
