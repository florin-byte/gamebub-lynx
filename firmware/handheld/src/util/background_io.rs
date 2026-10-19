use std::{
    fs::File,
    io::Read,
    sync::{Condvar, Mutex},
    time::{Duration, Instant},
};

/// Spawn a thread to read chunks from the file in background.
///
/// The callback function will be called for each chunk, until either
/// EOF or an Error.
///
/// Each chunk is at most half of the buffer size.
pub fn iter_chunks(
    mut file: File,
    buffer: &mut [u8],
    mut f: impl FnMut(&[u8]),
) -> Result<Duration, std::io::Error> {
    pub enum ReaderResult<'a> {
        Ok(&'a mut [u8], usize),
        Eof,
        Err(std::io::Error),
    }

    struct State<'a> {
        result: Option<ReaderResult<'a>>,
        free0: Option<&'a mut [u8]>,
        free1: Option<&'a mut [u8]>,
        duration: Duration,
    }

    let (buf0, buf1) = buffer.split_at_mut(buffer.len() / 2);
    let state = Mutex::new(State {
        result: None,
        free0: Some(buf0),
        free1: Some(buf1),
        duration: Duration::ZERO,
    });
    let condvar = Condvar::new();

    std::thread::scope(|scope| {
        scope.spawn(|| {
            let mut duration = Duration::ZERO;

            loop {
                let buf = {
                    // Wait for consumer to take result.
                    let mut state = condvar
                        .wait_while(state.lock().unwrap(), |x| x.result.is_some())
                        .unwrap();
                    // Take a free buffer.
                    let buffer = state.free0.take();
                    state.free0 = state.free1.take();
                    buffer.unwrap()
                };

                // Read into the buffer.
                let read_start = Instant::now();
                let result = file.read(buf);
                duration += read_start.elapsed();

                // Send to consumer
                let (out, exit) = match result {
                    Ok(0) => (ReaderResult::Eof, true),
                    Ok(n) => (ReaderResult::Ok(buf, n), false),
                    Err(err) => (ReaderResult::Err(err), true),
                };
                {
                    let mut state = state.lock().unwrap();
                    state.result = Some(out);
                    state.duration = duration;
                }
                condvar.notify_all();

                if exit {
                    break;
                }
            }
        });

        // Consumer (main thread)
        loop {
            // Wait for a result and take it (sync point)
            let (result, duration) = {
                let mut state = condvar
                    .wait_while(state.lock().unwrap(), |x| x.result.is_none())
                    .unwrap();
                (state.result.take().unwrap(), state.duration)
            };
            condvar.notify_all();

            // Process result.
            let buf = match result {
                ReaderResult::Ok(buf, n) => {
                    f(&buf[0..n]);
                    buf
                }
                ReaderResult::Eof => break Ok(duration),
                ReaderResult::Err(error) => break Err(error),
            };

            // Return the result buffer.
            {
                let mut state = state.lock().unwrap();
                assert!(state.free1.is_none());
                state.free1 = state.free0.take();
                state.free0 = Some(buf);
            }
        }
    })
}
