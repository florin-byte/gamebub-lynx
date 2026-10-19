use std::ffi::CStr;

use esp_idf_svc::sys::{self as esp_idf_sys, EspError};

const SYSTEM_DATA_PATH: &CStr = c"/system";
const SYSTEM_DATA_LABEL: &CStr = c"system_data";

pub fn mount_system_data() -> Result<(), EspError> {
    // Mount it with VFS.
    let config = esp_idf_sys::esp_vfs_fat_mount_config_t {
        max_files: 4,
        format_if_mount_failed: false,
        allocation_unit_size: 0,
        disk_status_check_enable: false,
        use_one_fat: true,
    };
    let result = unsafe {
        esp_idf_sys::esp_vfs_fat_spiflash_mount_ro(
            SYSTEM_DATA_PATH.as_ptr(),
            SYSTEM_DATA_LABEL.as_ptr(),
            &config,
        )
    };
    let result = EspError::convert(result);
    if let Err(e) = result {
        log::error!("Failed to mount system data: {}", e);
    }
    result
}
