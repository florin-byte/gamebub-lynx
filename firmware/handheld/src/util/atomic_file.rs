//! Atomic replacement of files on the SD card.
//!
//! Data is written to `<path>.tmp` first and renamed over the destination only once it is
//! complete, so an interrupted write (power loss, card removed, I/O error) leaves the previous
//! version of the file intact instead of a truncated one.

use std::fs::{self, File};
use std::io::{self, Read, Write};
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

/// The path the previous version of `dst` is kept at by [`finalize_keeping_previous`].
pub fn backup_path(dst: &Path) -> PathBuf {
    let mut s = dst.as_os_str().to_owned();
    s.push(".bak");
    PathBuf::from(s)
}

/// Whether the two files have the same contents (false if either cannot be read).
fn same_contents(a: &Path, b: &Path) -> bool {
    let (Ok(mut fa), Ok(mut fb)) = (File::open(a), File::open(b)) else {
        return false;
    };
    match (fa.metadata(), fb.metadata()) {
        (Ok(ma), Ok(mb)) if ma.len() == mb.len() => {}
        _ => return false,
    }
    let mut ba = [0u8; 512];
    let mut bb = [0u8; 512];
    loop {
        let n = match fa.read(&mut ba) {
            Ok(n) => n,
            Err(_) => return false,
        };
        if n == 0 {
            return true;
        }
        if fb.read_exact(&mut bb[..n]).is_err() || ba[..n] != bb[..n] {
            return false;
        }
    }
}

/// [`finalize`], keeping the previous version of `dst` as `<dst>.bak` when the new contents differ from it (one
/// generation; an older `.bak` is replaced). When they are the same, `tmp` is removed and `dst` is left as it is.
///
/// For battery saves: the version written before the last change stays on the card, so a save overwritten by
/// mistake (e.g. a save state from before the last in-game save, loaded and then exited) can be recovered by hand.
pub fn finalize_keeping_previous(mut file: File, tmp: &Path, dst: &Path) -> io::Result<()> {
    file.flush()?;
    if let Err(e) = file.sync_all() {
        log::warn!("sync_all failed for {}: {}", tmp.display(), e);
    }
    drop(file);
    if !dst.exists() {
        return fs::rename(tmp, dst);
    }
    if same_contents(tmp, dst) {
        log::info!("{} unchanged, not rewritten", dst.display());
        let _ = fs::remove_file(tmp);
        return Ok(());
    }
    let bak = backup_path(dst);
    let _ = fs::remove_file(&bak);
    if let Err(e) = fs::rename(dst, &bak) {
        // Without the backup, replace the file as before.
        log::warn!("could not keep {} as {}: {}", dst.display(), bak.display(), e);
        let _ = fs::remove_file(dst);
    } else {
        log::info!("previous {} kept as {}", dst.display(), bak.display());
    }
    // FAT cannot rename over an existing file; `dst` is gone at this point either way.
    fs::rename(tmp, dst)
}
