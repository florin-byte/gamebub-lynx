//! Atomic replacement of files on the SD card.
//!
//! Data is written to `<path>.tmp` first and renamed over the destination only once it is
//! complete, so an interrupted write (power loss, card removed, I/O error) leaves the previous
//! version of the file intact instead of a truncated one.

use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

/// The temporary path used while writing `dst`.
pub fn tmp_path(dst: &Path) -> PathBuf {
    let mut s = dst.as_os_str().to_owned();
    s.push(".tmp");
    PathBuf::from(s)
}

/// Finish an atomic write: flush and sync `file` (opened on `tmp`), then rename `tmp` over `dst`.
///
/// FAT cannot rename over an existing file, so if the rename fails the destination is removed
/// and the rename retried. Between those two steps only `tmp` holds the data.
pub fn finalize(mut file: File, tmp: &Path, dst: &Path) -> io::Result<()> {
    file.flush()?;
    if let Err(e) = file.sync_all() {
        // Best effort: the data has been written, this only forces it out of the FAT cache.
        log::warn!("sync_all failed for {}: {}", tmp.display(), e);
    }
    drop(file);
    match fs::rename(tmp, dst) {
        Ok(()) => Ok(()),
        Err(_) => {
            let _ = fs::remove_file(dst);
            fs::rename(tmp, dst)
        }
    }
}
