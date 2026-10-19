#![allow(unused)]

const BLOCK_SIZE: usize = 512;
const MAGIC_START0: u32 = 0x0A324655;
const MAGIC_START1: u32 = 0x9E5D5157;
const MAGIC_END: u32 = 0x0AB16F30;

mod consts {
    pub const FLAG_NOT_MAIN_FLASH: u32 = 0x00000001;
    pub const FLAG_FILE_CONTAINER: u32 = 0x00001000;
    pub const FLAG_FAMILY_ID_PRESENT: u32 = 0x00002000;
    pub const FLAG_MD5_PRESENT: u32 = 0x00004000;
    pub const FLAG_EXTENSION_TAGS_PRESENT: u32 = 0x00008000;
}
#[allow(unused)]
pub use consts::*;

#[derive(Clone)]
#[repr(C)]
pub struct Uf2Block {
    magic_start0: u32,
    magic_start1: u32,
    flags: u32,
    address: u32,
    payload_len: u32,
    block_number: u32,
    total_blocks: u32,
    family_id: u32,
    payload: [u8; 476],
    magic_end: u32,
}

const _: () = assert!(core::mem::size_of::<Uf2Block>() == BLOCK_SIZE);
const _: () = assert!(cfg!(target_endian = "little"));

impl Uf2Block {
    pub fn from_bytes(data: &[u8; BLOCK_SIZE]) -> Result<&Uf2Block, ()> {
        let ptr: *const Uf2Block = data.as_ptr().cast();
        assert!(ptr.is_aligned());
        let block = unsafe { &*ptr };
        if block.valid() { Ok(block) } else { Err(()) }
    }

    pub fn valid(&self) -> bool {
        self.magic_start0 == MAGIC_START0
            && self.magic_start1 == MAGIC_START1
            && self.magic_end == MAGIC_END
            && self.payload_len <= self.payload.len() as u32
    }

    pub fn payload(&self) -> &[u8] {
        &self.payload[..self.payload_len as usize]
    }

    pub fn address(&self) -> u32 {
        self.address
    }

    pub fn block_number(&self) -> u32 {
        self.block_number
    }

    pub fn total_blocks(&self) -> u32 {
        self.total_blocks
    }

    pub fn flags(&self) -> u32 {
        self.flags
    }

    pub fn family_id(&self) -> u32 {
        self.family_id
    }

    pub fn get_extension_tag(&self, tag: u32) -> Option<&[u8]> {
        if (self.flags & consts::FLAG_EXTENSION_TAGS_PRESENT) == 0 {
            return None;
        }

        let mut data = &self.payload[self.payload_len as usize..];
        while data.len() >= 4 {
            let tag_len = data[0];
            let tag_type = u32::from_le_bytes(data[0..4].try_into().unwrap()) >> 8;
            if tag_len == 0 && tag_type == 0 {
                break;
            }
            if tag_len as usize > data.len() {
                break;
            }
            let payload = &data[4..tag_len as usize];
            if tag_type == tag {
                return Some(payload);
            }
            let advance = ((tag_len as usize) + 3) & (!3);
            data = &data[advance..];
        }

        None
    }
}
