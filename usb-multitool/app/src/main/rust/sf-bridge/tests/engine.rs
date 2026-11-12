//! Behavioural fault tests for the real SectorForge `sf-core` imager driven through
//! this crate's C ABI — the same code path the Android app uses, minus JNI.
//!
//! Every test asserts the app's rescue contract, not just sf-core's:
//! * a damaged chunk is skipped so the healthy rest of the disk is rescued first;
//! * only the sector that really fails is marked bad (binary split, not a sweep);
//! * a sector that exhausts its retries is zero-filled in the image, so stale
//!   bytes from an earlier run never survive as if they were rescued;
//! * a transport failure aborts without marking anything bad and without touching
//!   the image after the fault;
//! * cancellation stops the run with a partial map;
//! * checkpoint snapshots reach the embedder while the pass is still running.

use std::ffi::c_void;

use sf_bridge::{sf_run_blocks, sf_run_execute, sf_run_free, sf_run_start, SfCallbacks, SfTriple};

const FINISHED: u64 = 4;
const NON_TRIED: u64 = 0;
const BAD: u64 = 3;
const NON_TRIMMED: u64 = 1;

struct Media {
    data: Vec<u8>,
    sector: u64,
    /// Byte ranges reported as medium errors.
    bad: Vec<(u64, u64)>,
    /// Byte offset whose read reports a transport failure.
    transport_at: Option<u64>,
    fail_write: bool,
    /// Byte offset whose write reports a sink failure.
    fail_write_at: Option<u64>,
    /// Cancel once `finished + bad` reaches this many bytes.
    cancel_after: Option<u64>,
    reads: Vec<(u64, usize)>,
    writes: Vec<(u64, usize)>,
    image: Vec<u8>,
    snapshots: Vec<Vec<(u64, u64, u64)>>,
}

impl Media {
    fn new(sectors: u64, sector_size: u64) -> Self {
        let len = (sectors * sector_size) as usize;
        Self {
            data: (0..len).map(|i| (i % 251) as u8).collect(),
            sector: sector_size,
            bad: Vec::new(),
            transport_at: None,
            fail_write: false,
            fail_write_at: None,
            cancel_after: None,
            reads: Vec::new(),
            writes: Vec::new(),
            image: vec![0u8; len],
            snapshots: Vec::new(),
        }
    }
}

unsafe extern "C" fn read_cb(ctx: *mut c_void, offset: u64, out: *mut u8, len: usize) -> i32 {
    let media = &mut *(ctx as *mut Media);
    media.reads.push((offset, len));
    let end = offset + len as u64;
    if let Some(at) = media.transport_at {
        if offset <= at && at < end {
            return 2;
        }
    }
    if media.bad.iter().any(|&(start, stop)| offset < stop && start < end) {
        return 1;
    }
    let source = &media.data[offset as usize..end as usize];
    std::ptr::copy_nonoverlapping(source.as_ptr(), out, len);
    0
}

unsafe extern "C" fn write_cb(ctx: *mut c_void, offset: u64, data: *const u8, len: usize) -> i32 {
    let media = &mut *(ctx as *mut Media);
    if media.fail_write || media.fail_write_at == Some(offset) {
        return 2;
    }
    media.writes.push((offset, len));
    let bytes = std::slice::from_raw_parts(data, len);
    media.image[offset as usize..offset as usize + len].copy_from_slice(bytes);
    0
}

unsafe extern "C" fn progress_cb(
    ctx: *mut c_void,
    finished: u64,
    bad: u64,
    _pending: u64,
    _pos: u64,
    blocks: *const SfTriple,
    count: usize,
) -> i32 {
    let media = &mut *(ctx as *mut Media);
    if !blocks.is_null() && count > 0 {
        let slice = std::slice::from_raw_parts(blocks, count);
        media
            .snapshots
            .push(slice.iter().map(|t| (t.a, t.b, t.c)).collect());
    }
    if let Some(limit) = media.cancel_after {
        if finished + bad >= limit {
            return 1;
        }
    }
    0
}

fn callbacks(media: &mut Media) -> SfCallbacks {
    SfCallbacks {
        ctx: media as *mut Media as *mut c_void,
        read_at: read_cb,
        write_at: write_cb,
        progress: progress_cb,
    }
}

fn blocks_of(handle: u64) -> Vec<(u64, u64, u64)> {
    let mut out = vec![SfTriple { a: 0, b: 0, c: 0 }; 32];
    let needed = sf_run_blocks(handle, out.as_mut_ptr(), out.len());
    assert_ne!(needed, u64::MAX, "bad handle");
    if needed as usize > out.len() {
        out = vec![SfTriple { a: 0, b: 0, c: 0 }; needed as usize];
        assert_eq!(sf_run_blocks(handle, out.as_mut_ptr(), out.len()), needed);
    }
    out.truncate(needed as usize);
    out.iter().map(|t| (t.a, t.b, t.c)).collect()
}

fn run_with(
    media: &mut Media,
    chunk: u64,
    max_retries: u32,
    checkpoint: u64,
) -> (i32, Vec<(u64, u64, u64)>) {
    let size = media.data.len() as u64;
    let sector = media.sector;
    let cb = callbacks(media);
    let handle = sf_run_start(
        &cb,
        size,
        sector,
        std::ptr::null(),
        0,
        chunk,
        chunk,
        max_retries,
        0,
        checkpoint,
    );
    assert_ne!(handle, 0, "run did not start");
    let status = sf_run_execute(handle);
    let blocks = blocks_of(handle);
    sf_run_free(handle);
    (status, blocks)
}

fn run(media: &mut Media, chunk: u64, checkpoint: u64) -> (i32, Vec<(u64, u64, u64)>) {
    run_with(media, chunk, 1, checkpoint)
}

#[test]
fn clean_media_is_copied_in_one_chunk() {
    let mut media = Media::new(8, 512);
    let expected = media.data.clone();

    let (status, blocks) = run(&mut media, 4096, 4096);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![(0, 4096, FINISHED)]);
    assert_eq!(media.image, expected);
    assert_eq!(media.writes.len(), 1);
}

#[test]
fn damaged_chunk_is_deferred_and_only_the_bad_sector_is_marked() {
    let mut media = Media::new(600, 4096);
    media.bad.push((10 * 4096, 11 * 4096));

    let (status, blocks) = run(&mut media, 1024 * 1024, 1024 * 1024);

    assert_eq!(status, 0);
    // The damaged first chunk is parked, so the next read is the *second* chunk.
    assert_eq!(media.reads[0].0, 0);
    assert_eq!(media.reads[1].0, 256 * 4096);
    // A sector sweep of the parked chunk would need >= 256 reads.
    assert!(media.reads.len() < 60, "reads: {}", media.reads.len());

    assert_eq!(blocks, vec![
        (0, 10 * 4096, FINISHED),
        (10 * 4096, 4096, BAD),
        (11 * 4096, 589 * 4096, FINISHED),
    ]);
    assert!(media.image[10 * 4096..11 * 4096].iter().all(|&b| b == 0));
    assert!(media.image[11 * 4096..12 * 4096].iter().any(|&b| b != 0));
}

#[test]
fn transport_failure_aborts_without_marking_bad_sectors() {
    let mut media = Media::new(600, 4096);
    media.transport_at = Some(300 * 4096);

    let (status, blocks) = run(&mut media, 1024 * 1024, 1024 * 1024);

    assert_eq!(status, 2, "transport failure must be a fatal status");
    assert!(blocks.iter().all(|b| b.2 != BAD), "blocks: {blocks:?}");
    assert_eq!(blocks, vec![(0, 256 * 4096, FINISHED), (256 * 4096, 344 * 4096, 0)]);
    assert!(media.writes.iter().all(|(offset, _)| *offset < 256 * 4096));
}

#[test]
fn cancellation_stops_the_run_with_a_partial_map() {
    let mut media = Media::new(600, 4096);
    media.cancel_after = Some(256 * 4096);

    let (status, blocks) = run(&mut media, 1024 * 1024, 1024 * 1024);

    assert_eq!(status, 1, "cancel must be reported as cancelled");
    assert_eq!(blocks[0], (0, 256 * 4096, FINISHED));
    assert!(blocks.iter().any(|b| b.2 == 0), "untouched tail: {blocks:?}");
    assert!(blocks.iter().all(|b| b.2 != BAD));
    assert_eq!(media.reads.len(), 1);
}

#[test]
fn checkpoint_snapshots_reach_the_embedder_mid_pass() {
    let mut media = Media::new(600, 4096);

    let (status, blocks) = run(&mut media, 1024 * 1024, 1024 * 1024);

    assert_eq!(status, 0);
    assert!(!media.snapshots.is_empty());
    assert!(
        media
            .snapshots
            .iter()
            .any(|s| s.iter().filter(|b| b.2 == FINISHED).map(|b| b.1).sum::<u64>() == 1024 * 1024),
        "no snapshot captured the first chunk: {:?}",
        media.snapshots
    );
    assert_eq!(blocks.last().map(|b| b.2), Some(FINISHED));
}

#[test]
fn zero_retries_skips_backward_retry_pass_for_forward_only_sink() {
    let mut media = Media::new(600, 4096);
    media.bad.push((10 * 4096, 11 * 4096));

    let (status, blocks) = run_with(&mut media, 1024 * 1024, 0, 1024 * 1024);

    assert_eq!(status, 0, "forward-only run must not abort on parked medium errors");
    assert_eq!(blocks, vec![
        (0, 256 * 4096, NON_TRIMMED),
        (256 * 4096, 344 * 4096, FINISHED),
    ]);
    assert!(media.writes.iter().all(|&(offset, _)| offset >= 256 * 4096));
    assert!(media.image[..256 * 4096].iter().all(|&byte| byte == 0));
    assert!(media.image[256 * 4096..].iter().any(|&byte| byte != 0));
}

#[test]
fn image_sink_failure_is_fatal_and_marks_nothing_finished() {
    let mut media = Media::new(8, 512);
    media.fail_write = true;

    let (status, blocks) = run(&mut media, 4096, 4096);

    assert_eq!(status, 2);
    assert!(blocks.iter().all(|b| b.2 != FINISHED), "blocks: {blocks:?}");
}

#[test]
fn seeded_map_resumes_and_retries_previous_failures() {
    let mut media = Media::new(4, 512);
    let sector = media.sector;
    let size = media.data.len() as u64;
    let cb = callbacks(&mut media);
    let seeds = [
        SfTriple { a: 0, b: 1, c: FINISHED },
        SfTriple { a: 1, b: 1, c: 1 }, // FAILED in the app map: retried, not trusted
    ];

    let handle = sf_run_start(
        &cb,
        size,
        sector,
        seeds.as_ptr(),
        seeds.len(),
        4096,
        4096,
        1,
        0,
        4096,
    );
    assert_ne!(handle, 0);
    let status = sf_run_execute(handle);
    let blocks = blocks_of(handle);
    sf_run_free(handle);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![(0, size, FINISHED)]);
    // The already-copied sector is never rewritten; the retried one is.
    assert!(media.writes.iter().all(|(offset, _)| *offset != 0));
    assert!(media.writes.iter().any(|(offset, _)| *offset == sector));
}

#[test]
fn start_rejects_misaligned_geometry_and_unknown_handles() {
    let mut media = Media::new(4, 512);
    let cb = callbacks(&mut media);

    assert_eq!(
        sf_run_start(&cb, 1000, 512, std::ptr::null(), 0, 512, 512, 1, 0, 512),
        0,
        "size must be a sector multiple"
    );
    assert_eq!(
        sf_run_start(&cb, 0, 512, std::ptr::null(), 0, 512, 512, 1, 0, 512),
        0,
        "empty device"
    );
    let seed = [SfTriple { a: 3, b: 2, c: FINISHED }];
    assert_eq!(
        sf_run_start(&cb, 2048, 512, seed.as_ptr(), 1, 512, 512, 1, 0, 512),
        0,
        "seed past the end"
    );

    assert_eq!(sf_run_execute(0), 3);
    assert_eq!(sf_run_blocks(0, std::ptr::null_mut(), 0), u64::MAX);
    sf_run_free(0);
}

#[test]
fn contiguous_bad_region_is_retried_one_initial_chunk_at_a_time() {
    let chunk = 4096u64;
    let mut media = Media::new(600, 4096);
    // Everything the first 256 chunks try to read fails, so the copy pass
    // parks them as adjacent NonTrimmed blocks that the map merges into one
    // 1 MiB region — far larger than any single read buffer should be.
    media.bad.push((0, 256 * 4096));

    let (status, blocks) = run(&mut media, chunk, chunk);

    assert_eq!(status, 0);
    // Pre-fix the retry pass sized its first buffer from the merged 1 MiB
    // region; every request must stay within the first-pass chunk instead.
    assert!(
        media.reads.iter().all(|&(_, len)| len as u64 <= chunk),
        "read of {} bytes exceeds the {chunk}-byte chunk",
        media.reads.iter().map(|&(_, len)| len).max().unwrap()
    );
    assert_eq!(blocks, vec![
        (0, 256 * 4096, BAD),
        (256 * 4096, 344 * 4096, FINISHED),
    ]);
    assert!(media.image[..256 * 4096].iter().all(|&b| b == 0));
    // Every parked chunk is zero-filled rather than left with stale bytes.
    assert_eq!(
        media.writes.iter().filter(|&&(offset, _)| offset < 256 * 4096).count(),
        256
    );
}

#[test]
fn retry_requests_never_exceed_the_aligned_initial_chunk() {
    let sector = 512u64;
    let chunk = 5000u64; // aligns down to 4608 on a 512-byte sector
    let mut media = Media::new(32, sector);
    let size = media.data.len() as u64;
    media.bad.push((0, size));

    let (status, blocks) = run(&mut media, chunk, chunk);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![(0, size, BAD)]);
    let requested = media.reads.iter().map(|&(_, len)| len as u64).max().unwrap();
    assert_eq!(requested, 4608, "bound must be the sector-aligned chunk");
    assert!(media.reads.iter().all(|&(_, len)| len as u64 <= 4608));
}

#[test]
fn unreadable_sector_is_zero_filled_in_the_image() {
    let sector = 512u64;
    let mut media = Media::new(8, sector);
    // A previous run left data here; this run cannot read the last sector, so
    // those bytes must not survive as if they had been rescued.
    media.image.fill(0xAB);
    let expected = media.data.clone();
    media.bad.push((7 * sector, 8 * sector));

    let (status, blocks) = run(&mut media, 4096, 4096);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![(0, 7 * sector, FINISHED), (7 * sector, sector, BAD)]);
    assert!(media.image[7 * sector as usize..].iter().all(|&b| b == 0));
    assert_eq!(&media.image[..7 * sector as usize], &expected[..7 * sector as usize]);
    assert!(media.writes.contains(&(7 * sector, sector as usize)));
}

#[test]
fn resume_never_overwrites_previously_copied_data() {
    let sector = 512u64;
    let mut media = Media::new(8, sector);
    media.image.fill(0xAB);
    media.bad.push((6 * sector, 7 * sector));
    let size = media.data.len() as u64;
    let cb = callbacks(&mut media);
    let seeds = [
        SfTriple { a: 0, b: 6, c: FINISHED },
        SfTriple { a: 6, b: 1, c: 1 }, // FAILED last run: retried now
        SfTriple { a: 7, b: 1, c: FINISHED }, // COPIED last run: untouchable
    ];

    let handle = sf_run_start(&cb, size, sector, seeds.as_ptr(), seeds.len(), 4096, 4096, 1, 0, 4096);
    assert_ne!(handle, 0);
    let status = sf_run_execute(handle);
    let blocks = blocks_of(handle);
    sf_run_free(handle);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![
        (0, 6 * sector, FINISHED),
        (6 * sector, sector, BAD),
        (7 * sector, sector, FINISHED),
    ]);
    assert!(media.image[6 * sector as usize..7 * sector as usize].iter().all(|&b| b == 0));
    // The already-copied sector keeps whatever the image held: no read, no write.
    assert!(media.image[7 * sector as usize..].iter().all(|&b| b == 0xAB));
    assert_eq!(media.writes, vec![(6 * sector, sector as usize)]);
    assert!(media.reads.iter().all(|&(offset, _)| offset == 6 * sector));
}

#[test]
fn failed_zero_write_is_fatal_and_leaves_the_sector_unmarked() {
    let sector = 512u64;
    let mut media = Media::new(8, sector);
    media.image.fill(0xAB);
    media.bad.push((7 * sector, 8 * sector));
    media.fail_write_at = Some(7 * sector);

    let (status, blocks) = run(&mut media, 4096, 4096);

    assert_eq!(status, 2, "a failed zero fill must abort the run");
    assert!(blocks.iter().all(|b| b.2 != BAD), "blocks: {blocks:?}");
    // The failed write left the old image bytes in place, and the map never
    // claims the sector is bad.
    assert!(media.image[7 * sector as usize..].iter().all(|&b| b == 0xAB));
    assert!(media.writes.iter().all(|&(offset, _)| offset < 7 * sector));
}

#[test]
fn cancellation_after_a_bad_sector_keeps_the_zero_fill() {
    let sector = 512u64;
    let mut media = Media::new(8, sector);
    media.image.fill(0xAB);
    media.bad.push((6 * sector, 7 * sector));
    // Cancel as soon as the seventh sector resolves, i.e. right after the bad
    // sector was zero-filled and marked.
    media.cancel_after = Some(7 * sector);

    let (status, blocks) = run(&mut media, 4096, 4096);

    assert_eq!(status, 1);
    assert!(blocks.contains(&(6 * sector, sector, BAD)), "blocks: {blocks:?}");
    assert!(media.image[6 * sector as usize..7 * sector as usize].iter().all(|&b| b == 0));
    assert!(media.image[7 * sector as usize..].iter().all(|&b| b == 0xAB));
}

#[test]
fn a_bad_sector_is_retried_before_it_is_zero_filled() {
    let sector = 512u64;
    let mut media = Media::new(8, sector);
    media.image.fill(0xAB);
    media.bad.push((7 * sector, 8 * sector));

    let (status, blocks) = run_with(&mut media, 4096, 2, 4096);

    assert_eq!(status, 0);
    assert_eq!(blocks, vec![(0, 7 * sector, FINISHED), (7 * sector, sector, BAD)]);
    let attempts = media
        .reads
        .iter()
        .filter(|&&(offset, len)| offset == 7 * sector && len as u64 == sector)
        .count();
    assert_eq!(attempts, 2, "one read per configured attempt");
    assert_eq!(
        media.writes.iter().filter(|&&(offset, _)| offset == 7 * sector).count(),
        1,
        "the zero fill happens once, after the last attempt"
    );
    assert!(media.image[7 * sector as usize..].iter().all(|&b| b == 0));
}
