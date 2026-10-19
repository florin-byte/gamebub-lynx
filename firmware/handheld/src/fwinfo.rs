use std::fmt::Display;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct FirmwareVersion {
    pub major: u8,
    pub minor: u8,
    pub patch: u8,
    pub pre: u8,
}

impl From<u32> for FirmwareVersion {
    fn from(value: u32) -> Self {
        Self {
            major: ((value >> 24) & 0xFF) as u8,
            minor: ((value >> 16) & 0xFF) as u8,
            patch: ((value >> 8) & 0xFF) as u8,
            pre: ((value >> 0) & 0xFF) as u8,
        }
    }
}

impl Display for FirmwareVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)
    }
}

#[repr(C)]
struct FirmwareMetadata {
    git_commit: [u8; 20],
    version: FirmwareVersion,
}

#[used]
#[link_section = ".rodata_custom_desc"]
static FIRMWARE_METADATA: FirmwareMetadata = FirmwareMetadata {
    git_commit: GIT_COMMIT_BYTES,
    version: FIRMWARE_VERSION,
};

pub const GIT_COMMIT_BYTES: [u8; 20] = {
    const fn parse(hex: u8) -> u8 {
        if hex >= b'0' && hex <= b'9' {
            hex - b'0'
        } else if hex >= b'a' && hex <= b'f' {
            hex - b'a' + 10
        } else {
            panic!();
        }
    }

    let mut hash = [0u8; 20];
    let string = env!("GIT_COMMIT").as_bytes();
    let mut i = 0;
    while i < 20 {
        hash[i] = parse(string[i * 2]) << 4 | parse(string[i * 2 + 1]);
        i += 1;
    }
    hash
};

pub const FIRMWARE_VERSION: FirmwareVersion = {
    const fn parse(data: &[u8]) -> u32 {
        let mut val = 0;
        let mut i = data.len();
        while i > 0 {
            let c = data[i - 1];
            if c < b'0' || c > b'9' {
                panic!();
            }
            val *= 10;
            val += (c - b'0') as u32;
            i -= 1;
        }
        val
    }

    FirmwareVersion {
        major: parse(env!("CARGO_PKG_VERSION_MAJOR").as_bytes()) as u8,
        minor: parse(env!("CARGO_PKG_VERSION_MINOR").as_bytes()) as u8,
        patch: parse(env!("CARGO_PKG_VERSION_PATCH").as_bytes()) as u8,
        pre: 0,
    }
};
