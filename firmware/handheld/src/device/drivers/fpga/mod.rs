#![allow(dead_code)]

use std::{
    io::Read,
    time::{Duration, Instant},
};

use embedded_hal::{
    digital::{InputPin, OutputPin},
    spi::SpiDevice,
};
use esp_idf_svc::hal::{
    spi::{config::LineWidth, Operation, SpiDriver, SpiSharedDeviceDriver, SpiSoftCsDeviceDriver},
    units::Hertz,
};
use thiserror::Error;

pub const REG_INFO_FRAMEWORK_VER: u32 = 0xF100_0000;
pub const REG_INFO_SYSCLK_HZ: u32 = 0xF100_0004;
pub const REG_INFO_VIDEO_DIM: u32 = 0xF100_0100;
pub const REG_INFO_VIDEO_DEPTH: u32 = 0xF100_0104;
pub const REG_INFO_INPUT_CAPS: u32 = 0xF100_0108;

pub const REG_CTRL_IRQ_ENABLE: u32 = 0xF100_1000;
pub const REG_CTRL_IRQ_PENDING: u32 = 0xF100_1004;
pub const REG_CTRL_DOCK: u32 = 0xF100_100C;
pub const REG_CTRL_FOCUS: u32 = 0xF100_1010;
pub const REG_CTRL_VIBRATE: u32 = 0xF100_1014;
/// Framework v2: bit 0 darkens video; never use this register for Swap A/B.
pub const REG_CTRL_VIDEO_FILTER: u32 = 0xF100_1018;
/// Framework v2 button-remap entries 8..11 (Y, X, B, A), one source index per byte.
pub const REG_CTRL_BUTTON_REMAP_AB: u32 = 0xF100_1208;
pub const BUTTON_REMAP_AB_OFF: u32 = 0x0B0A_0908;
pub const BUTTON_REMAP_AB_ON: u32 = 0x0A0B_0908;

pub const fn button_remap_ab_value(swapped: bool) -> u32 {
    if swapped {
        BUTTON_REMAP_AB_ON
    } else {
        BUTTON_REMAP_AB_OFF
    }
}

pub const fn button_remap_ab_enabled(value: u32) -> bool {
    value == BUTTON_REMAP_AB_ON
}

pub const REG_CTRL_CMD_HOST: u32 = 0xF100_1100;
pub const REG_CTRL_CMD_CORE: u32 = 0xF100_1104;

pub const REG_CTRL_INPUT_SLOT_BASE: u32 = 0xF100_1400;

pub const REG_STATUS_BUTTON: u32 = 0xF100_2000;
pub const REG_STATUS_CART_SWITCH: u32 = 0xF100_2004;

pub const REG_CMD_HOST_BASE: u32 = 0xF000_0000;
pub const REG_CMD_CORE_BASE: u32 = 0xF000_1000;

/// The FPGA (due to the spi implementation) can read at a speed that's some
/// fraction of the SPI domain clock speed. At 200 MHz SPI receiver clock,
/// 16 MHz is a safe speed.
pub const MAX_SPI_READ_CLOCK: Hertz = Hertz(10_000_000);

#[cfg(not(feature = "fast-link-spi"))]
pub type SpiDataDriver<'a> =
    SpiSoftCsDeviceDriver<'a, SpiSharedDeviceDriver<'a, &'a SpiDriver<'a>>, &'a SpiDriver<'a>>;

#[cfg(feature = "fast-link-spi")]
use std::sync::{Arc, atomic::{AtomicU8, Ordering}};
#[cfg(feature = "fast-link-spi")]
use esp_idf_svc::sys;

#[cfg(feature = "fast-link-spi")]
type SharedSpi<'a> = SpiSharedDeviceDriver<'a, &'a SpiDriver<'a>>;

/// One link register access in a batch (shared with the C link code as GbLinkOp).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct LinkOp {
    pub address: u32,
    pub value: u32,
    pub write: u32,
}

/// One 32-bit quad link access: 8-bit command on one line, quad address, 16
/// dummy cycles for reads, quad data. Inline data avoids DMA alignment/allocation.
#[cfg(feature = "fast-link-spi")]
fn link_transaction_desc(register: u32, value: Option<u32>) -> sys::spi_transaction_ext_t {
    let read = value.is_none();
    let command = SpiCommand::new(FpgaSpiWordSize::Bits32);
    let mut transaction = sys::spi_transaction_ext_t::default();
    transaction.command_bits = 8;
    transaction.address_bits = 32;
    transaction.dummy_bits = if read { 16 } else { 0 };
    transaction.base.flags = sys::SPI_TRANS_VARIABLE_CMD
        | sys::SPI_TRANS_VARIABLE_ADDR | sys::SPI_TRANS_VARIABLE_DUMMY
        | sys::SPI_TRANS_MODE_QIO | sys::SPI_TRANS_MULTILINE_ADDR
        | if read { sys::SPI_TRANS_USE_RXDATA } else { sys::SPI_TRANS_USE_TXDATA };
    // MULTILINE_CMD is deliberately absent: the command uses one data line.
    transaction.base.cmd = (if read { command.as_read_command() }
        else { command.as_write_command() } | ((LineWidth::Quad as u8) << 5)) as u16;
    transaction.base.addr = register as u64;
    if let Some(value) = value {
        transaction.base.length = 32;
        transaction.base.__bindgen_anon_1.tx_data = value.to_le_bytes();
    } else {
        transaction.base.rxlength = 32;
    }
    transaction
}

/// Keep access to the shared-device lock alongside the existing soft-CS driver.
#[cfg(feature = "fast-link-spi")]
pub struct SpiDataDriver<'a> {
    legacy: SpiSoftCsDeviceDriver<'a, Arc<SharedSpi<'a>>, &'a SpiDriver<'a>>,
    shared: Arc<SharedSpi<'a>>,
    cs: i32,
}

// SAFETY: SharedSpi is Send, and both Arc clones are private to this owner and
// move together. No clone escapes or is used on another thread; all operations
// require &mut self. This does not make SharedSpi (or this wrapper) Sync.
#[cfg(feature = "fast-link-spi")]
unsafe impl Send for SpiDataDriver<'_> {}

#[cfg(feature = "fast-link-spi")]
impl<'a> SpiDataDriver<'a> {
    pub fn new(
        shared: SharedSpi<'a>,
        cs_pin: impl esp_idf_svc::hal::peripheral::Peripheral<
            P = impl esp_idf_svc::hal::gpio::OutputPin,
        > + 'a,
        cs: i32,
    ) -> Result<Self, sys::EspError> {
        let shared = Arc::new(shared);
        let legacy = SpiSoftCsDeviceDriver::new(
            shared.clone(), cs_pin, esp_idf_svc::hal::gpio::Level::High,
        )?;
        Ok(Self { legacy, shared, cs })
    }

    fn transaction(&mut self, operations: &mut [Operation]) -> Result<(), esp_idf_svc::sys::EspError> {
        self.legacy.transaction(operations)
    }

    /// Several link accesses under one shared-device lock and one bus acquisition.
    /// CS still frames each access. Returns how many completed, in order.
    fn link_batch(&mut self, ops: &mut [LinkOp]) -> usize {
        let cs = self.cs;
        self.shared.lock(|device| {
            let handle = device.device();
            // SAFETY: as in link_transaction; the bus stays ours for the whole batch.
            if unsafe { sys::spi_device_acquire_bus(handle, u32::MAX) } != 0 {
                return 0;
            }
            struct Release { handle: sys::spi_device_handle_t, cs: i32 }
            impl Drop for Release {
                fn drop(&mut self) {
                    unsafe {
                        sys::gpio_set_level(self.cs as _, 1);
                        sys::spi_device_release_bus(self.handle);
                    }
                }
            }
            let _release = Release { handle, cs };
            let mut done = 0;
            for op in ops.iter_mut() {
                let value = if op.write != 0 { Some(op.value) } else { None };
                let mut transaction = link_transaction_desc(op.address, value);
                // SAFETY: same pin and serialization as link_transaction.
                if unsafe { sys::gpio_set_level(cs as _, 0) } != 0 { break; }
                // SAFETY: inline buffers live until this synchronous call returns.
                let failed = unsafe { sys::spi_device_polling_transmit(handle, &mut transaction.base) } != 0;
                unsafe { sys::gpio_set_level(cs as _, 1) };
                if failed { break; }
                if value.is_none() {
                    op.value = u32::from_le_bytes(unsafe { transaction.base.__bindgen_anon_2.rx_data });
                }
                done += 1;
            }
            done
        })
    }

    fn link_transaction(&mut self, register: u32, value: Option<u32>) -> Result<u32, Error> {
        let read = value.is_none();
        let mut transaction = link_transaction_desc(register, value);

        self.shared.lock(|device| {
            let handle = device.device();
            // SAFETY: the shared-device lock excludes HAL operations on this handle.
            // The IDF bus lock also excludes the other devices/clock rates on the bus.
            if unsafe { sys::spi_device_acquire_bus(handle, u32::MAX) } != 0 {
                return Err(Error::SpiError);
            }
            struct Release {
                handle: sys::spi_device_handle_t,
                cs: i32,
            }
            impl Drop for Release {
                fn drop(&mut self) {
                    // Always release CS before allowing another device onto the bus.
                    unsafe {
                        sys::gpio_set_level(self.cs as _, 1);
                        sys::spi_device_release_bus(self.handle);
                    }
                }
            }
            let _release = Release { handle, cs: self.cs };
            // SAFETY: this is the same output pin owned by legacy; Device and bus
            // serialization prevent simultaneous use. The guard restores idle CS.
            if unsafe { sys::gpio_set_level(self.cs as _, 0) } != 0 {
                return Err(Error::PinError);
            }
            // SAFETY: ext embeds base first; inline buffers and ext live until this
            // synchronous polling call completes. No interrupt transaction is pending.
            if unsafe { sys::spi_device_polling_transmit(handle, &mut transaction.base) } != 0 {
                return Err(Error::SpiError);
            }
            Ok(if read {
                u32::from_le_bytes(unsafe { transaction.base.__bindgen_anon_2.rx_data })
            } else { 0 })
        })
    }
}

mod xilinx;

#[derive(Debug, Error)]
#[allow(clippy::enum_variant_names)]
pub enum Error {
    #[error("gpio error")]
    PinError,
    #[error("error programming fpga")]
    ProgramError,
    #[error("error reading bitstream")]
    BitstreamError,
    #[error("incompatible bitstream")]
    IncompatibleBitstream,
    #[error("spi error")]
    SpiError,
}

// Game Boy wireless bridge ABI 2 (core register space).
pub const GBA_LINK_CAPS: u32 = 0x60;
pub const GBA_LINK_CONFIG: u32 = 0x64;
pub const GBA_LINK_STATUS: u32 = 0x68;
pub const GBA_LINK_EVENT: u32 = 0x6c;
pub const GBA_LINK_EVENT_DATA: u32 = 0x70;
pub const GBA_LINK_POP: u32 = 0x74;
pub const GBA_LINK_COMMAND_DATA: u32 = 0x78;
pub const GBA_LINK_COMMAND_ID: u32 = 0x7c;
pub const GBA_LINK_COMMAND: u32 = 0x80;
pub const GBA_LINK_CONTROL: u32 = 0x84;
pub const GBA_LINK_EVENT_CYCLES: u32 = 0x88;
pub const GBA_LINK_COMMAND_CYCLES: u32 = 0x8c;
pub const GBA_LINK_SNAPSHOT: u32 = 0x90;
pub const GBA_LINK_SNAPSHOT_CYCLES: u32 = 0x94;
pub const GBA_LINK_COMMAND_ANCHOR: u32 = 0x98;
/// GB-117: child SEND sampled at BEGIN (read-only; CONFIG read bit 28 says it exists).
pub const GBA_LINK_SAMPLED: u32 = 0x9c;
pub const LINK_CAPS: u32 = 0x40;
pub const LINK_CONFIG: u32 = 0x44;
pub const LINK_STATUS: u32 = 0x48;
pub const LINK_EVENT: u32 = 0x4c;
pub const LINK_EVENT_POP: u32 = 0x50;
pub const LINK_COMMAND: u32 = 0x54;
pub const LINK_CONTROL: u32 = 0x58;
/// GB-145: two GB cores with frame-locked buttons (DualCoreSync; GB core built with GB_DUAL_CORE=1).
pub const LINK_SYNC_CAPS: u32 = 0xa0;
pub const LINK_SYNC_CONTROL: u32 = 0xa4;
pub const LINK_SYNC_RAM_BASE: u32 = 0xa8;
pub const LINK_SYNC_PARTNER: u32 = 0xac;
pub const LINK_SYNC_LOCAL: u32 = 0xb0;
pub const LINK_SYNC_DIGEST_V: u32 = 0xb4;
pub const LINK_SYNC_DIGEST_H: u32 = 0xb8;
pub const LINK_SYNC_POP: u32 = 0xbc;
pub const LINK_SYNC_STATUS: u32 = 0xc0;
pub const LINK_SYNC_WAIT: u32 = 0xc4;
pub const LINK_SYNC_PHASE: u32 = 0xc8;
pub const LINK_SYNC_SERIAL_V: u32 = 0xcc;
pub const LINK_SYNC_SERIAL_H: u32 = 0xd0;
pub const LINK_SYNC_SERIAL_X: u32 = 0xd4;
pub const LINK_SYNC_WRITES: u32 = 0xd8;
pub const LINK_SYNC_REJECTS: u32 = 0xdc;
pub const LINK_SYNC_LAST_REJECT: u32 = 0xe0;
/// Twelve read-only probes, 4 bytes apart (GB-150).
pub const LINK_SYNC_PROBE: u32 = 0xe4;
pub const LINK_SYNC_CAPS_VALUE: u32 = 0x5359_0004;
/// GB-156: the GBA core built with GBA_DUAL_CORE=1 (same registers, ten buttons).
pub const GBA_SYNC_CAPS_VALUE: u32 = 0x5359_4701;

#[derive(Copy, Clone)]
#[repr(u32)]
pub enum Irq {
    ModuleVblank = 0,
    Button = 1,
    SpiRequestOverflow = 2,
    SpiResponseUnderflow = 3,
    CoreRequest = 4,
}

impl Irq {
    pub const fn as_flag(self) -> u32 {
        1 << (self as u32)
    }
}

pub struct Fpga<
    'a,
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    ProgramSpi: SpiDevice,
> {
    pin_done: PinDone,
    pub pin_program_b: PinProgramB,
    pin_init_b: PinInitB,
    /// List of SPI drivers and their clock speed, from largest to smallest.
    data_spi: Vec<(SpiDataDriver<'a>, Hertz)>,
    program_spi: ProgramSpi,

    /// Top-level "system" clock speed, which determines how fast reads
    /// and writes can occur.
    system_clock: Hertz,

    /// Bitfield of enabled interrupts
    interrupts: u32,
    #[cfg(feature = "fast-link-spi")]
    fast_link_spi: AtomicU8,
}

impl<'a, PinDone, PinProgramB, PinInitB, ProgramSpi>
    Fpga<'a, PinDone, PinProgramB, PinInitB, ProgramSpi>
where
    PinDone: InputPin,
    PinProgramB: OutputPin,
    PinInitB: InputPin,
    ProgramSpi: SpiDevice,
{
    pub fn new(
        pin_done: PinDone,
        pin_program_b: PinProgramB,
        pin_init_b: PinInitB,
        data_spi: Vec<(SpiDataDriver<'a>, Hertz)>,
        program_spi: ProgramSpi,
    ) -> Self {
        Fpga {
            pin_done,
            pin_program_b,
            pin_init_b,
            data_spi,
            program_spi,
            system_clock: Hertz(8 * 1024 * 1024),
            interrupts: 0,
            #[cfg(feature = "fast-link-spi")]
            fast_link_spi: AtomicU8::new(0),
        }
    }

    /// Program the FPGA with a new bitstream.
    #[allow(clippy::unnecessary_unwrap)]
    pub fn program(
        &mut self,
        bitstream: &mut dyn Read,
        scratch_buf: &mut [u8],
    ) -> Result<(), Error> {
        #[cfg(feature = "fast-link-spi")]
        self.fast_link_spi.store(0, Ordering::Relaxed);

        let header =
            xilinx::parse_bitstream_header(bitstream).map_err(|_| Error::BitstreamError)?;

        // Check that the bitstream was built for this hardware.
        let hardware_version = crate::hwinfo::get_hardware_version();
        let expected_id = 0xB010_0000 | (hardware_version.major as u32);
        if header.user_id.is_none() || header.user_id == Some(0xFFFF_FFFF) {
            log::info!("Bitstream has no UserID, assuming it is compatible");
        } else if hardware_version.major == 0 || hardware_version.major == 255 {
            log::info!("Hardware version major=0, skipping bitstream compatibility");
        } else if header.user_id != Some(expected_id) {
            log::error!("Incompatible bitstream, ID={:08X}", header.user_id.unwrap());
            return Err(Error::IncompatibleBitstream);
        }

        // After power-on-reset, INIT_B will be low for 10ms to 35ms (T_POR),
        // configuration can only start after this.
        // Poll INIT_B until it goes high.
        let start_time = Instant::now();
        while self.pin_init_b.is_low().map_err(|_| Error::PinError)? {
            if start_time.elapsed() > Duration::from_millis(35) {
                return Err(Error::ProgramError);
            }
            std::thread::sleep(Duration::from_millis(5));
        }

        // Pull PROGRAM_B low, hold it for at least 250ns.
        self.pin_program_b.set_low().map_err(|_| Error::PinError)?;
        std::thread::sleep(Duration::from_millis(1));
        if self.pin_init_b.is_high().map_err(|_| Error::PinError)? {
            return Err(Error::ProgramError);
        }
        self.pin_program_b.set_high().map_err(|_| Error::PinError)?;

        // INIT_B will go high at most 5ms after PROGRAM_B release.
        std::thread::sleep(Duration::from_millis(5));
        if self.pin_init_b.is_low().map_err(|_| Error::PinError)? {
            return Err(Error::ProgramError);
        }

        log::info!("FPGA is in program mode");
        let start_time = Instant::now();

        let mut num_read = 0;
        while num_read < header.length {
            let amount = (header.length - num_read).min(scratch_buf.len());
            let buf = &mut scratch_buf[0..amount];
            bitstream
                .read_exact(buf)
                .map_err(|_| Error::BitstreamError)?;
            num_read += amount;

            self.program_spi
                .write(buf)
                .map_err(|_| Error::ProgramError)?;
        }

        log::info!(
            "Programmed FPGA, done={}, time={}",
            self.pin_done.is_high().map_err(|_| Error::PinError)?,
            start_time.elapsed().as_millis() as u32,
        );

        Ok(())
    }

    pub fn set_system_clock_rate(&mut self, rate: Hertz) {
        self.system_clock = rate;
    }

    pub fn enable_interrupt(&mut self, irq: Irq) -> Result<(), Error> {
        self.interrupts |= irq.as_flag();
        self.write_u32(REG_CTRL_IRQ_ENABLE, self.interrupts)
    }

    pub fn disable_interrupt(&mut self, irq: Irq) -> Result<(), Error> {
        self.interrupts &= !irq.as_flag();
        self.write_u32(REG_CTRL_IRQ_ENABLE, self.interrupts)
    }

    /// Finds a SPI data driver with the maximum clock speed.
    fn spi_transaction(
        &mut self,
        max_clock: Option<Hertz>,
        operations: &mut [Operation],
    ) -> Result<(), Error> {
        let driver = &mut self.data_spi.iter_mut().find(|(_, clock)| match max_clock {
            Some(max_clock) => *clock <= max_clock,
            None => true,
        });
        let driver = match driver {
            Some(driver) => driver,
            None => panic!("No suitable spi for max clock {:?}", max_clock),
        };
        driver
            .0
            .transaction(operations)
            .map_err(|_| Error::SpiError)
    }

    const fn spi_command(
        read: bool,
        word_size: FpgaSpiWordSize,
        byte_swap: bool,
        auto_increment: bool,
    ) -> u8 {
        (read as u8)
            | ((word_size as u8) << 1)
            | ((byte_swap as u8) << 3)
            | ((auto_increment as u8) << 4)
    }

    /// Generic SPI write function.
    pub fn spi_write(
        &mut self,
        max_clock: Option<Hertz>,
        command: SpiCommand,
        address: u32,
        data: &[u8],
    ) -> Result<(), Error> {
        let width = LineWidth::Quad;
        let mut command = command.as_write_command();
        command |= (width as u8) << 5;
        let address = address.to_be_bytes();
        self.spi_transaction(
            max_clock,
            &mut [
                Operation::Write(&[command]),
                Operation::WriteWithWidth(&address, width),
                Operation::WriteWithWidth(data, width),
            ],
        )
    }

    /// Generic SPI read function.
    pub fn spi_read(
        &mut self,
        max_clock: Option<Hertz>,
        command: SpiCommand,
        address: u32,
        buffer: &mut [u8],
    ) -> Result<(), Error> {
        let width = LineWidth::Quad;
        let mut command = command.as_read_command();
        command |= (width as u8) << 5;
        let address = address.to_be_bytes();
        const DUMMY_BYTES: usize = 8;
        let mut dummy = [0u8; DUMMY_BYTES];
        self.spi_transaction(
            max_clock,
            &mut [
                Operation::Write(&[command]),
                Operation::WriteWithWidth(&address, width),
                Operation::ReadWithWidth(&mut dummy, width),
                Operation::ReadWithWidth(buffer, width),
            ],
        )
    }

    pub fn write_u32(&mut self, address: u32, data: u32) -> Result<(), Error> {
        let command = SpiCommand::new(FpgaSpiWordSize::Bits32);
        let data = data.to_le_bytes();
        self.spi_write(None, command, address, &data)
    }

    /// Write an input slot (buttons, sticks, triggers).
    pub fn write_input_slot(&mut self, slot: usize, words: [u32; 3]) -> Result<(), Error> {
        let address = REG_CTRL_INPUT_SLOT_BASE + (slot as u32) * 0x10;
        let command = SpiCommand::new(FpgaSpiWordSize::Bits32);
        self.spi_write(
            None,
            command,
            address,
            words.map(u32::to_le_bytes).as_flattened(),
        )
    }

    pub fn read_u32(&mut self, address: u32) -> Result<u32, Error> {
        let mut data = [0u8; 4];
        let command = SpiCommand::new(FpgaSpiWordSize::Bits32);
        self.spi_read(Some(MAX_SPI_READ_CLOCK), command, address, &mut data)?;
        Ok(u32::from_le_bytes(data))
    }

    #[cfg(feature = "fast-link-spi")]
    fn fast_link_access(&mut self, register: u32, value: Option<u32>) -> Result<u32, Error> {
        // Preserve the legacy clock selection for each direction.
        self.data_spi.iter_mut().find(|(_, clock)| value.is_some() || *clock <= MAX_SPI_READ_CLOCK)
            .ok_or(Error::SpiError)?.0.link_transaction(register, value)
    }

    #[cfg(feature = "fast-link-spi")]
    fn fast_link_enabled(&mut self, register: u32) -> bool {
        if self.fast_link_spi.load(Ordering::Relaxed) == 0 {
            // Both capability registers are read-only, unlike event/pop registers.
            // The sync registers live in the GB core, above the GBA link range.
            let caps = if register >= LINK_SYNC_CAPS { LINK_SYNC_CAPS }
                else if register >= GBA_LINK_CAPS { GBA_LINK_CAPS } else { LINK_CAPS };
            let old = self.read_u32(caps);
            let (enabled, reason) = match old {
                Err(_) => (false, "legacy-read-error"),
                Ok(expected) => match self.fast_link_access(caps, None) {
                    Err(_) => (false, "fast-read-error"),
                    Ok(actual) if actual != expected => (false, "caps-mismatch"),
                    Ok(_) => (true, "caps-match"),
                },
            };
            self.fast_link_spi.store(if enabled { 1 } else { 2 }, Ordering::Relaxed);
            log::info!("fpga: fast link spi={} reason={}", if enabled { "on" } else { "off" }, reason);
        }
        self.fast_link_spi.load(Ordering::Relaxed) == 1
    }

    /// Link registers the C link code may read (checked by host/check_register_contract.py).
    pub const fn link_read_ok(register: u32) -> bool {
        matches!(register, LINK_SYNC_PROBE | LINK_SYNC_CAPS | LINK_SYNC_LOCAL | LINK_SYNC_DIGEST_V | LINK_SYNC_DIGEST_H |
            LINK_SYNC_STATUS | LINK_SYNC_WAIT | LINK_SYNC_SERIAL_V | LINK_SYNC_SERIAL_H | LINK_SYNC_SERIAL_X |
            LINK_SYNC_WRITES | LINK_SYNC_REJECTS | LINK_SYNC_LAST_REJECT |
            LINK_CAPS | LINK_CONFIG | LINK_STATUS | LINK_EVENT | GBA_LINK_CAPS |
            GBA_LINK_CONFIG | GBA_LINK_STATUS | GBA_LINK_EVENT | GBA_LINK_EVENT_DATA |
            GBA_LINK_EVENT_CYCLES | GBA_LINK_SNAPSHOT | GBA_LINK_SNAPSHOT_CYCLES | GBA_LINK_SAMPLED)
            || (register >= LINK_SYNC_PROBE && register < LINK_SYNC_PROBE + 48 && register % 4 == 0)
    }

    /// Link registers the C link code may write (checked by host/check_register_contract.py).
    pub const fn link_write_ok(register: u32) -> bool {
        matches!(register, LINK_SYNC_CONTROL | LINK_SYNC_PARTNER | LINK_SYNC_POP | LINK_SYNC_PHASE |
            LINK_CONFIG | LINK_EVENT_POP | LINK_COMMAND | LINK_CONTROL | GBA_LINK_CONFIG |
            GBA_LINK_POP | GBA_LINK_COMMAND_DATA | GBA_LINK_COMMAND_ID |
            GBA_LINK_COMMAND | GBA_LINK_CONTROL | GBA_LINK_COMMAND_CYCLES |
            GBA_LINK_SNAPSHOT | GBA_LINK_COMMAND_ANCHOR)
    }

    pub fn link_read(&mut self, register: u32) -> Result<u32, Error> {
        if !Self::link_read_ok(register) {
            return Err(Error::SpiError);
        }
        #[cfg(feature = "fast-link-spi")]
        if self.fast_link_enabled(register) {
            let result = self.fast_link_access(register, None);
            if result.is_err() {
                self.fast_link_spi.store(2, Ordering::Relaxed);
            }
            return result;
        }
        self.read_u32(register)
    }

    pub fn link_write(&mut self, register: u32, value: u32) -> Result<(), Error> {
        if !Self::link_write_ok(register) {
            return Err(Error::SpiError);
        }
        #[cfg(feature = "fast-link-spi")]
        if self.fast_link_enabled(register) {
            let result = self.fast_link_access(register, Some(value)).map(|_| ());
            if result.is_err() {
                // Do not retry a possibly completed command/pop write.
                self.fast_link_spi.store(2, Ordering::Relaxed);
            }
            return result;
        }
        self.write_u32(register, value)
    }

    /// Run several link accesses in order. With fast link SPI they share one
    /// bus acquisition on the read-clock device (writes at that clock are a few
    /// microseconds); otherwise they run one by one. Stops at the first refused
    /// or failed access and returns how many completed; a failed write is never
    /// retried, as for a single write.
    pub fn link_batch(&mut self, ops: &mut [LinkOp]) -> usize {
        if ops.iter().any(|op| !(if op.write != 0 { Self::link_write_ok(op.address) }
                                  else { Self::link_read_ok(op.address) })) {
            return 0;
        }
        #[cfg(feature = "fast-link-spi")]
        if let Some(first) = ops.first() {
            if self.fast_link_enabled(first.address) {
                let done = match self.data_spi.iter_mut().find(|(_, clock)| *clock <= MAX_SPI_READ_CLOCK) {
                    Some((device, _)) => device.link_batch(ops),
                    None => 0,
                };
                if done != ops.len() {
                    self.fast_link_spi.store(2, Ordering::Relaxed);
                }
                return done;
            }
        }
        let mut done = 0;
        for op in ops.iter_mut() {
            let ok = if op.write != 0 {
                self.link_write(op.address, op.value).is_ok()
            } else {
                match self.link_read(op.address) { Ok(v) => { op.value = v; true }, Err(_) => false }
            };
            if !ok { break; }
            done += 1;
        }
        done
    }

    /// Write overlay framebuffer.
    pub fn write_overlay(&mut self, offset: u32, data: &[u8]) -> Result<(), Error> {
        let command = SpiCommand::new(FpgaSpiWordSize::Bits16);
        // 16 bits per transfer, 2 cycles per transfer.
        let max_clock = (self.system_clock.0 * 16) / (4 * 2);
        self.spi_write(Some(Hertz(max_clock)), command, 0xF200_0000 | offset, data)
    }

    /// Get the state of the cartridge slot button.
    pub fn get_cartridge_slot_button(&mut self) -> Result<bool, Error> {
        Ok((self.read_u32(REG_STATUS_CART_SWITCH)? & 1) != 0)
    }

    pub fn set_dock_state(&mut self, docked: bool, use_builtin_screen: bool) -> Result<(), Error> {
        let val = (docked as u32) | ((use_builtin_screen as u32) << 1);
        self.write_u32(REG_CTRL_DOCK, val)
    }
}

#[allow(unused)]
#[derive(Copy, Clone)]
pub enum FpgaSpiWordSize {
    Bits8 = 0,
    Bits16 = 1,
    Bits32 = 2,
    Bits64 = 3,
}

#[derive(Copy, Clone)]
pub struct SpiCommand {
    pub word_size: FpgaSpiWordSize,
    pub byte_swap: bool,
    pub increment_address: bool,
}

impl SpiCommand {
    pub fn new(word_size: FpgaSpiWordSize) -> Self {
        SpiCommand {
            word_size,
            byte_swap: true,
            increment_address: true,
        }
    }

    fn as_read_command(self) -> u8 {
        (1u8)
            | ((self.word_size as u8) << 1)
            | ((self.byte_swap as u8) << 3)
            | ((self.increment_address as u8) << 4)
    }

    fn as_write_command(self) -> u8 {
        (0u8)
            | ((self.word_size as u8) << 1)
            | ((self.byte_swap as u8) << 3)
            | ((self.increment_address as u8) << 4)
    }
}
