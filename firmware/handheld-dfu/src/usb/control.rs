use embassy_usb::{
    Handler,
    control::{InResponse, OutResponse, Recipient, Request, RequestType},
    types::InterfaceNumber,
};

use crate::info::{self, FirmwareMetadata};

const REQ_GET_INFO: u8 = 0;
const REQ_REBOOT: u8 = 1;
const REQ_ENABLE_DEBUG: u8 = 2;
const REQ_GET_COMMAND_STATUS: u8 = 0x42;
const REQ_CONFIGURE_WRITE_PROTECT: u8 = 0x50;

pub struct Control {
    interface_number: InterfaceNumber,
    fw_meta: Option<FirmwareMetadata>,
}

impl Handler for Control {
    fn control_out(
        &mut self,
        req: embassy_usb::control::Request,
        _data: &[u8],
    ) -> Option<embassy_usb::control::OutResponse> {
        self.check_request(&req)?;
        match req.request {
            REQ_REBOOT => {
                match req.value {
                    1 => crate::reboot::reboot(),
                    2 => crate::reboot::reboot_dfu(),
                    _ => return Some(OutResponse::Rejected),
                }
                Some(OutResponse::Accepted)
            }
            REQ_ENABLE_DEBUG => {
                crate::reboot::enable_debug();
                Some(OutResponse::Accepted)
            }
            REQ_CONFIGURE_WRITE_PROTECT => {
                let enabled = req.value != 0xBAAD;
                crate::protocol::set_flash_write_protect(enabled);
                Some(OutResponse::Accepted)
            }
            // TODO: handle INTERFACE_RESET?
            _ => Some(OutResponse::Rejected),
        }
    }

    fn control_in<'a>(
        &'a mut self,
        req: embassy_usb::control::Request,
        buf: &'a mut [u8],
    ) -> Option<embassy_usb::control::InResponse<'a>> {
        self.check_request(&req)?;
        match req.request {
            REQ_GET_INFO => {
                assert!(buf.len() >= 24);
                buf[0..4].copy_from_slice(&0u32.to_le_bytes());
                buf[4..8].copy_from_slice(&info::SerialNumber::get().0.to_le_bytes());
                buf[8..12].copy_from_slice(&info::HardwareVersion::get().as_u32().to_le_bytes());
                buf[12..24].fill(0);
                if let Some(meta) = self.fw_meta.as_ref() {
                    buf[12] = meta.version_pre;
                    buf[13] = meta.version_patch;
                    buf[14] = meta.version_minor;
                    buf[15] = meta.version_major;
                    buf[16..24].copy_from_slice(&meta.commit_hash[0..8]);
                }
                Some(InResponse::Accepted(&buf[0..24]))
            }
            REQ_GET_COMMAND_STATUS => {
                assert!(buf.len() >= 16);
                let status = crate::protocol::get_command_state();
                buf[0..4].copy_from_slice(&status.token.to_le_bytes());
                buf[4..8].copy_from_slice(&(status.status as u32).to_le_bytes());
                buf[8] = status.command_id;
                buf[9] = status.in_progress as u8;
                for x in &mut buf[10..16] {
                    *x = 0;
                }
                Some(InResponse::Accepted(&buf[0..16]))
            }
            _ => Some(InResponse::Rejected),
        }
    }
}

impl Control {
    pub fn new(interface_number: InterfaceNumber, fw_meta: Option<FirmwareMetadata>) -> Self {
        Self {
            interface_number,
            fw_meta,
        }
    }

    fn check_request(&self, req: &Request) -> Option<()> {
        let required = (
            RequestType::Vendor,
            Recipient::Interface,
            self.interface_number.0 as u16,
        );
        let actual = (req.request_type, req.recipient, req.index);
        (actual == required).then_some(())
    }
}
