# Background undo compression

Revision `undo compression v1` / `undo-compression-v1`. The preceding complete
checkpoint is `4efc9db`. This removes admitted pixel edits' zlib work from the
input thread. Bitmap rasterization, sample/geometry preparation, tile reads,
history ordering and native handoff still have UI costs; physical responsiveness
and the remaining firmware transition must be tested on the tablet.

## Ownership and immediate Undo

`EditHistory.execute()` still commits artwork and one history action synchronously.
Before applying an admitted edit it copies the declared before tiles and allocates
all after buffers. After drawing, it reads after pixels, compares exact ARGB arrays,
and immediately publishes the changed tiles to history. No-op edits preserve redo;
failed edits restore before pixels and preserve both branches. Allocation failure
before mutation can safely use the original synchronous compressed path without
repeating an edit with side effects.

`HistoryCompression` receives only independent tile copies and cancellation state.
It never receives a document, layer bitmap, View, Canvas or mutable history list.
A single process-wide executor runs at Android background priority. `HistoryTile`
publishes a complete compressed representation using a volatile replacement; a
concurrent Undo/Redo retains either the raw array or the complete compressed bytes.
There is no intermediate state. The codec keeps the same lossless big-endian ARGB
and zlib representation and reuses its scratch buffers across tiles in a job.
Incompressible tiles stay raw so retained tile sizes can only shrink.

The worker never appends history, changes its cursor or posts completion mutations
to UI. `bytesUsed` reads current retained tile sizes. Clear/New, redo branching,
capacity/byte eviction, surface disposal and Activity destruction cancel obsolete
jobs; late compression cannot resurrect an action or change pixels. Queued work
drops its tile references immediately. Active cancellation checks between tiles
and retains its reservation until the work really returns. No UI joins or waits
are introduced. Pause-time checkpoints still serialize committed artwork, not
history; compression can continue for the current in-memory history while paused.

## Bounds and fallback

- Four total reservations, including capture, queued work and the active job.
- Raw capture reservations total at most `min(8MiB, heap/32)` process-wide.
- Before/after raw tiles and the existing patch accounting must also fit the
  history byte budget after ordinary capacity/redo eviction. If temporary raw
  storage would force extra eviction, capture uses synchronous compression.
- The capture preflight also requires the raw estimate plus 8MiB of remaining
  managed-heap headroom. Both raw sides allocate before any pixels change.
- History retains the existing ten-action and `min(32MiB, heap/8)` limits, now
  accounting for raw as well as compressed patches. Estimates are accounting
  bounds, not measured process RSS; codec scratch/output and object overhead exist.
- Full Clear and structural layer captures retain synchronous compression. Large
  edits, saturated queues, budget pressure and raw allocation failure also use
  that fallback. Compression or worker scheduling failure leaves usable raw Undo
  tiles; scheduling failure disables new async admission for that worker.

These limits deliberately permit occasional synchronous compression under load.
There is no unbounded stroke queue, deferred artwork commit, background bitmap
mutation, file write or new save-format field. Native preview width/color safety,
the saved firmware guard, immutable surface snapshots and 450ms reconciliation
remain unchanged.

## Validation and timings

Local JVM tests exercise exact codec bits/edge sizes, scratch reuse, monotonic
storage, concurrent readers, byte/job admission, queued and active cancellation,
FIFO processing, Clear/new-work ordering, exceptions, allocation failure and
executor rejection. Device `AsyncHistoryChecks` additionally holds compression
jobs to verify exact overlapping/edge Undo/Redo before and after compression,
no-op redo preservation, failure rollback, branching, interleaved layer edits,
eviction/Clear cancellation and synchronous budget/queue fallback.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest -PbooxRenderer=recovered
.\dev.ps1 test -Renderer recovered -HistoryOnly
.\dev.ps1 test -Renderer recovered
```

`history` logs identify raw/compressed capture, fallback reason, candidate raw
bytes and the existing stage timings. `historyCompression` records worker queue
and work time, raw/retained bytes and cancellation. The focused device suite also
runs the same stroke fixtures with synchronous and background history admission;
inspect fallback records when comparing the UI medians. Retained byte totals can
differ while jobs are still pending. Those tests measure commit stages, excluding
physical panel latency and BOOX handoff, and do not establish perceived improvement.

Device suites, stage comparisons and physical ordinary-fast/burst handwriting are
pending a connected tablet. Test quick adjacent strokes, long textured strokes,
immediate Undo/Redo, zoom/rotation, layer switches, New/Open and Home/return. Use
BooxPerf to distinguish reduced history CPU work from the remaining native reset.
