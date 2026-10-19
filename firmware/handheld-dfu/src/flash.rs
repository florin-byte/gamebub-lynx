use embassy_sync::blocking_mutex::CriticalSectionMutex;
use embedded_storage::nor_flash::{NorFlash, ReadNorFlash};
use esp_storage::{FlashStorage, FlashStorageError};

/// Locked wrapper around flash storage.
pub struct Flash {
    inner: CriticalSectionMutex<FlashStorage<'static>>,
}

impl Flash {
    pub fn new(periph: esp_hal::peripherals::FLASH<'static>) -> Self {
        let inner = FlashStorage::new(periph);
        Flash {
            inner: CriticalSectionMutex::new(inner),
        }
    }

    pub fn read(&self, offset: u32, bytes: &mut [u8]) -> Result<(), FlashStorageError> {
        unsafe { self.inner.lock_mut(|f| f.read(offset, bytes)) }
    }

    pub fn write(&self, offset: u32, bytes: &[u8]) -> Result<(), FlashStorageError> {
        unsafe { self.inner.lock_mut(|f| f.write(offset, bytes)) }
    }

    pub fn erase(&self, from: u32, to: u32) -> Result<(), FlashStorageError> {
        unsafe { self.inner.lock_mut(|f| f.erase(from, to)) }
    }

    pub fn capacity(&self) -> usize {
        self.inner.lock(|f| f.capacity())
    }
}
