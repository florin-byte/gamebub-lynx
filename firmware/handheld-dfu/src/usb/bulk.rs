use embassy_usb::driver::{Endpoint, EndpointIn, EndpointOut};
use embassy_usb_synopsys_otg::{Endpoint as EspUsbEndpoint, In, Out};

use crate::protocol::{self, CommandStatus, Protocol};

pub struct Bulk {
    protocol: &'static mut Protocol,
    ep_out: EspUsbEndpoint<'static, Out>,
    ep_in: EspUsbEndpoint<'static, In>,
}

impl Bulk {
    pub fn new(
        protocol: &'static mut Protocol,
        ep_out: EspUsbEndpoint<'static, Out>,
        ep_in: EspUsbEndpoint<'static, In>,
    ) -> Self {
        Self {
            protocol,
            ep_out,
            ep_in,
        }
    }

    pub async fn run(&mut self) -> ! {
        loop {
            self.ep_out.wait_enabled().await;

            // Read packet header.
            let mut header = [0u8; 32];
            match self.ep_out.read(&mut header).await {
                Ok(32) => {}
                Ok(len) => {
                    log::warn!("Bad header packet len={len}");
                    continue;
                }
                Err(_) => continue,
            }
            let header = protocol::CommandHeader::from(header);
            if header.magic != protocol::CommandHeader::MAGIC {
                // Wrong magic, ignore.
                continue;
            }

            log::info!("Command: id={}", header.cmd_id);

            let result = self
                .protocol
                .handle(
                    header,
                    async |e| {
                        self.ep_out
                            .read(e)
                            .await
                            .map_err(|_| CommandStatus::UnknownError)
                    },
                    async |e| {
                        self.ep_in
                            .write(e)
                            .await
                            .map_err(|_| CommandStatus::UnknownError)
                    },
                )
                .await;

            if let Err(e) = result {
                // TODO: stall endpoint
                log::warn!("Command error: {:?}", e);
                continue;
            }

            // Successful transfer, send or receive ack ZLP.
            let ack_result = if header.is_in() {
                self.ep_out.read(&mut []).await.map(|_| ())
            } else {
                self.ep_in.write(&[]).await
            };

            if let Err(e) = ack_result {
                log::warn!("Ack error: {:?}", e);
                continue;
            }
        }
    }
}
