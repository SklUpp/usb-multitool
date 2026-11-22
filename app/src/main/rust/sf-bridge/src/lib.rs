//! JNI-agnostic C ABI over SectorForge's `sf-core` imager, loaded by the app's
//! `libusb_native.so`.
//!
//! The Android app cannot hand `sf-core` a file descriptor: its USB mass-storage
//! access goes through `UsbDeviceConnection`, so the device is a Kotlin callback.
//! This crate therefore implements `sf_core::BlockDevice` and `Write + Seek` on
//! top of two C callbacks supplied by the JNI layer:
//!
//! * `read_at(ctx, offset, out, len) -> 0 ok | 1 medium | 3 cancelled | other fatal`
//! * `write_at(ctx, offset, data, len) -> 0 ok | other fatal`
//!
//! Classification stays the app's, not sf-core's: sf-core treats every read error
//! as a bad sector and keeps going, which would turn a disconnect into thousands
//! of "unreadable" sectors. Here a medium error is the only outcome sf-core is
//! allowed to see as a read failure; cancellation and transport failures abort the
//! run by panicking and are caught before the ABI boundary. Panics therefore only
//! unwind Rust frames (sf-core and this crate), never through the C++/JNI frame;
//! both abort paths are raised from callbacks that sf-core invokes synchronously.
//!
//! Checkpoints: sf-core's own mapfile is ddrescue-flavoured and lacks the app's
//! `identity`/`kind`, so the app never persists it. The bridge instead hands the
//! live `MapFile` blocks to the progress callback at the embedder's cadence (see
//! `scripts/patches/sectorforge-progress-map.patch`), and the Kotlin side writes
//! `usb-rescue-map 2` after syncing the image.

use std::ffi::c_void;
use std::io::{self, Seek, SeekFrom, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use sf_core::block_device::BlockDevice;
use sf_core::imager::{Imager, ImagerConfig, Progress};
use sf_core::mapfile::{MapFile, Status};

/// Run finished; the map is authoritative (it may still contain bad sectors).
pub const SF_STATUS_OK: i32 = 0;
/// The embedder cancelled through the progress callback.
pub const SF_STATUS_CANCELLED: i32 = 1;
/// Transport failure, image-sink failure, or an engine error. Nothing after the
/// fault was read or written.
pub const SF_STATUS_FATAL: i32 = 2;
/// Unknown or already-freed handle.
pub const SF_STATUS_BAD_HANDLE: i32 = 3;

/// `read_at` result: the device reported a medium error (bad sector).
pub const SF_READ_MEDIUM: i32 = 1;
/// `read_at` result: the run was cancelled; the bridge aborts it.
pub const SF_READ_CANCELLED: i32 = 3;

const MAX_RUNS: usize = 4;

/// A `(start, size, status)` triple. Used for seeds (input) and blocks (output), so
/// JNI can hand a `jlong[]` straight through without a second copy.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SfTriple {
    pub a: u64,
    pub b: u64,
    pub c: u64,
}

/// Callbacks into the JNI layer. All of them are invoked on the thread that called
/// [`sf_run_execute`]; `ctx` is owned by the embedder.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SfCallbacks {
    pub ctx: *mut c_void,
    pub read_at: unsafe extern "C" fn(*mut c_void, u64, *mut u8, usize) -> i32,
    pub write_at: unsafe extern "C" fn(*mut c_void, u64, *const u8, usize) -> i32,
    /// `(ctx, finished, bad, pending, current_pos, blocks, count) -> 0 continue | 1 cancel`.
    /// `blocks` is null unless `count` bytes of resolved work accumulated since the
    /// previous snapshot (see [`sf_run_start`]'s `checkpoint_bytes`).
    pub progress: unsafe extern "C" fn(*mut c_void, u64, u64, u64, u64, *const SfTriple, usize) -> i32,
}

// The callbacks are only ever invoked from the thread running sf_run_execute, which
// is the JNI thread that owns `ctx`; the raw pointers are not shared across threads.
unsafe impl Send for SfCallbacks {}

/// Why a run is being unwound out of sf-core.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Abort {
    Cancelled,
    Fatal,
}

struct CallbackDevice {
    cb: SfCallbacks,
    size: u64,
    sector: u64,
}

impl BlockDevice for CallbackDevice {
    fn size_bytes(&self) -> u64 {
        self.size
    }

    fn sector_size(&self) -> u64 {
        self.sector
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        let rc = unsafe { (self.cb.read_at)(self.cb.ctx, offset, buf.as_mut_ptr(), buf.len()) };
        match rc {
            0 => Ok(()),
            SF_READ_MEDIUM => Err(io::Error::new(io::ErrorKind::Other, "medium error")),
            SF_READ_CANCELLED => std::panic::panic_any(Abort::Cancelled),
            _ => std::panic::panic_any(Abort::Fatal),
        }
    }
}

struct CallbackSink {
    cb: SfCallbacks,
    pos: u64,
}

impl Write for CallbackSink {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if data.is_empty() {
            return Ok(0);
        }
        let rc = unsafe { (self.cb.write_at)(self.cb.ctx, self.pos, data.as_ptr(), data.len()) };
        if rc != 0 {
            return Err(io::Error::new(io::ErrorKind::Other, "image sink failed"));
        }
        self.pos += data.len() as u64;
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        // Durability belongs to the embedder's checkpoint callback, which syncs the
        // image before the map is written.
        Ok(())
    }
}

impl Seek for CallbackSink {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        let next: i64 = match from {
            SeekFrom::Start(pos) => pos as i64,
            SeekFrom::Current(delta) => self.pos as i64 + delta,
            SeekFrom::End(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "seek from end is not supported by a rescue image sink",
                ))
            }
        };
        if next < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "negative seek"));
        }
        self.pos = next as u64;
        Ok(self.pos)
    }
}

/// Progress state lifted out of [`Run`] so the imager can be borrowed mutably while
/// the callback still owns its accumulator.
struct ProgressSink {
    cb: SfCallbacks,
    checkpoint_bytes: u64,
    last_snapshot: u64,
}

impl ProgressSink {
    fn emit(&mut self, map: &MapFile, progress: Progress) {
        let resolved = progress.bytes_rescued.saturating_add(progress.bytes_bad);
        let include = resolved.saturating_sub(self.last_snapshot) >= self.checkpoint_bytes;
        let snapshot: Vec<SfTriple> = if include {
            map.blocks()
                .iter()
                .map(|block| SfTriple {
                    a: block.pos,
                    b: block.size,
                    c: code(block.status),
                })
                .collect()
        } else {
            Vec::new()
        };
        let (ptr, count) = if include {
            (snapshot.as_ptr(), snapshot.len())
        } else {
            (std::ptr::null(), 0)
        };
        let rc = unsafe {
            (self.cb.progress)(
                self.cb.ctx,
                progress.bytes_rescued,
                progress.bytes_bad,
                progress.bytes_pending,
                progress.current_pos,
                ptr,
                count,
            )
        };
        if include {
            self.last_snapshot = resolved;
        }
        if rc != 0 {
            std::panic::panic_any(Abort::Cancelled);
        }
    }
}

struct Run {
    imager: Imager<CallbackDevice, CallbackSink>,
    progress: ProgressSink,
    retry: bool,
}

static RUNS: Mutex<Vec<Option<Run>>> = Mutex::new(Vec::new());
static EXEC_LOCK: Mutex<()> = Mutex::new(());

fn code(status: Status) -> u64 {
    match status {
        Status::NonTried => 0,
        Status::NonTrimmed => 1,
        Status::NonScraped => 2,
        Status::BadSector => 3,
        Status::Finished => 4,
    }
}

fn from_code(code: u64) -> Status {
    match code {
        1 => Status::NonTrimmed,
        2 => Status::NonScraped,
        3 => Status::BadSector,
        4 => Status::Finished,
        _ => Status::NonTried,
    }
}

fn slot_index(handle: u64) -> Option<usize> {
    if handle == 0 || handle > MAX_RUNS as u64 {
        return None;
    }
    Some(handle as usize - 1)
}

fn take(handle: u64) -> Option<Run> {
    let index = slot_index(handle)?;
    let mut runs = RUNS.lock().ok()?;
    runs.get_mut(index).and_then(Option::take)
}

fn put_back(handle: u64, run: Run) {
    let Some(index) = slot_index(handle) else {
        return;
    };
    if let Ok(mut runs) = RUNS.lock() {
        if runs.len() <= index {
            runs.resize_with(index + 1, || None);
        }
        runs[index] = Some(run);
    }
}

/// Creates a run. Returns `0` when the arguments are invalid.
///
/// `seed` holds sector-aligned triples `(lba, sectors, status)` describing an
/// existing app map: `4` (COPIED) becomes Finished, `1` (FAILED from a previous
/// run) becomes NonTrimmed so the retry pass revisits it, `0` is left NonTried.
///
/// `checkpoint_bytes` is how much newly resolved work triggers a block snapshot in
/// the progress callback.
#[no_mangle]
pub extern "C" fn sf_run_start(
    cb: *const SfCallbacks,
    size: u64,
    sector: u64,
    seed: *const SfTriple,
    seed_len: usize,
    initial_chunk: u64,
    skip: u64,
    max_retries: u32,
    reverse: u32,
    checkpoint_bytes: u64,
) -> u64 {
    // A running imager is temporarily removed from RUNS; do not reuse its slot.
    let _serialized = match EXEC_LOCK.lock() {
        Ok(guard) => guard,
        Err(_) => return 0,
    };
    if cb.is_null() || size == 0 || sector < 512 || !sector.is_power_of_two() || size % sector != 0 {
        return 0;
    }
    let cb = unsafe { *cb };
    let mut map = MapFile::new_non_tried(size);
    if !seed.is_null() && seed_len > 0 {
        let seeds = unsafe { std::slice::from_raw_parts(seed, seed_len) };
        for seed in seeds {
            let Some(start) = seed.a.checked_mul(sector) else {
                return 0;
            };
            let Some(len) = seed.b.checked_mul(sector) else {
                return 0;
            };
            if len == 0 || start.checked_add(len).is_none_or(|end| end > size) {
                return 0;
            }
            let status = from_code(seed.c);
            if status != Status::NonTried {
                map.mark(start, len, status);
            }
        }
    }
    let config = ImagerConfig {
        initial_chunk: initial_chunk.max(sector),
        skip_size: skip.max(initial_chunk).max(sector),
        max_retries: max_retries.max(1),
        reverse: reverse != 0,
    };
    let device = CallbackDevice { cb, size, sector };
    let sink = CallbackSink { cb, pos: 0 };
    let imager = Imager::new(device, sink, map, config);
    let run = Run {
        imager,
        progress: ProgressSink {
            cb,
            checkpoint_bytes: checkpoint_bytes.max(1),
            last_snapshot: 0,
        },
        retry: max_retries > 0,
    };
    let mut runs = match RUNS.lock() {
        Ok(runs) => runs,
        Err(_) => return 0,
    };
    if let Some(index) = runs.iter().position(Option::is_none) {
        runs[index] = Some(run);
        return index as u64 + 1;
    }
    if runs.len() < MAX_RUNS {
        runs.push(Some(run));
        return runs.len() as u64;
    }
    0
}

/// Runs the copy pass and, when requested, the retry pass. A zero retry budget
/// intentionally leaves parked errors unresolved for forward-only sinks; the app
/// maps those ranges to FAILED/NON_TRIED instead of seeking backward.
#[no_mangle]
pub extern "C" fn sf_run_execute(handle: u64) -> i32 {
    let _serialized = match EXEC_LOCK.lock() {
        Ok(guard) => guard,
        Err(_) => return SF_STATUS_FATAL,
    };
    let Some(run) = take(handle) else {
        return SF_STATUS_BAD_HANDLE;
    };
    let Run { mut imager, mut progress, retry } = run;

    // sf-core's passes have no cancellation/abort hook; callbacks raise a panic
    // that is caught below, before the C ABI boundary.
    let hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {}));
    let outcome = catch_unwind(AssertUnwindSafe(|| -> io::Result<()> {
        imager.run_copy_pass_with_map(|map, p| progress.emit(map, p))?;
        if retry {
            imager.run_retry_pass_with_map(|map, p| progress.emit(map, p))?;
        }
        Ok(())
    }));
    std::panic::set_hook(hook);

    put_back(handle, Run { imager, progress, retry });

    match outcome {
        Ok(Ok(())) => SF_STATUS_OK,
        Ok(Err(_)) => SF_STATUS_FATAL,
        Err(payload) if payload.downcast_ref::<Abort>() == Some(&Abort::Cancelled) => {
            SF_STATUS_CANCELLED
        }
        Err(_) => SF_STATUS_FATAL,
    }
}

/// Copies the current map blocks into `out` (up to `cap` triples).
/// Returns the number of blocks the map holds; `u64::MAX` for a bad handle.
#[no_mangle]
pub extern "C" fn sf_run_blocks(handle: u64, out: *mut SfTriple, cap: usize) -> u64 {
    let index = match slot_index(handle) {
        Some(index) => index,
        None => return u64::MAX,
    };
    let runs = match RUNS.lock() {
        Ok(runs) => runs,
        Err(_) => return u64::MAX,
    };
    let Some(slot) = runs.get(index) else {
        return u64::MAX;
    };
    let Some(run) = slot else {
        return u64::MAX;
    };
    let blocks = run.imager.map().blocks();
    if !out.is_null() {
        let writable = unsafe { std::slice::from_raw_parts_mut(out, cap.min(blocks.len())) };
        for (target, block) in writable.iter_mut().zip(blocks) {
            *target = SfTriple {
                a: block.pos,
                b: block.size,
                c: code(block.status),
            };
        }
    }
    blocks.len() as u64
}

/// Releases a run. Safe to call with `0` or an unknown handle.
#[no_mangle]
pub extern "C" fn sf_run_free(handle: u64) {
    let _ = take(handle);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_codes_round_trip() {
        for status in [
            Status::NonTried,
            Status::NonTrimmed,
            Status::NonScraped,
            Status::BadSector,
            Status::Finished,
        ] {
            assert_eq!(from_code(code(status)), status);
        }
    }

    #[test]
    fn handles_reject_out_of_range() {
        assert_eq!(slot_index(0), None);
        assert_eq!(slot_index(MAX_RUNS as u64 + 1), None);
        assert_eq!(slot_index(1), Some(0));
    }
}
