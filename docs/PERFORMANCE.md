# Stroke responsiveness investigation

Current UI marker: `drawing mode v1` / `drawing-mode-v1` (2026-10-27).
Fresh/private drawings without a saved viewport and New canvas start centered at
100%; saved Fit/manual views still restore, and portable Open retains upright Fit.
The toolbar's four-corners action hides toolbar and layers, leaving a 52dp Show
controls button at top right. That button or Back restores the overlays without
changing viewport, document, layer expansion or surface size. Visibility changes
use the existing input-close/full-publish/deferred-reopen handshake; native input
excludes only the restore button while hidden. Mode changes reject active drawing,
navigation, pending commits and file work. State survives Activity recreation,
without adding artwork metadata or preferences. Pen geometry, crash guard,
immutable snapshots and the accepted 450ms stroke handoff remain unchanged.
Startup/restore and actual overlay/mode/exclusion checks were updated. Both
renderer builds/lint and test-APK compilation pass; device execution, physical
overlay refresh and ordinary-fast/burst pen checks are pending.

Previous UI marker: `compact layers v1` / `compact-layers-v1` (2026-10-27).
The canvas now fills the workspace behind a content-height layers overlay. Short
lists wrap; long lists scroll within the space above the bottom toolbar. Collapse
leaves only a 52x60dp control. Native session exclusions capture the actual panel
and toolbar rectangles in surface coordinates, so space below the panel remains
available to ink. Panel collapse/expand and side changes explicitly close input
and defer reconfiguration after layout even though surface size/origin stay fixed.
Layer commands retain their existing close/layout/reopen path. No per-sample SDK
setters, brush/policy changes or changes to the accepted 450ms handoff were added.
Sizing/scrolling and panel layout/exclusion checks were updated. Both renderer
builds/lint and test-APK compilation pass; device execution, physical pen and
overlay refresh feedback remain pending tablet validation.

Previous UI marker: `rotation controls v1` / `rotation-controls-v1` (2026-10-27).
Two-finger twist is now opt-in via Settings > Canvas gestures, default false for
fresh and existing installations without the new preference. With it off, twist
does not activate navigation or compete with pinch/pan. The cached setting is
loaded at startup and updated on Apply; gesture samples never read preferences.
Two fixed buttons at the toolbar's right end rotate -15/+15 degrees through the
existing close/publish/deferred-reopen path, guarded against drawing/navigation
and pending commits/file operations. Pens can still scroll within the toolbar;
the native exclusion uses its actual measured bounds. Brush widths, saved crash
guard, immutable live-zoom snapshots and accepted 450ms pen handoff are retained.
Settings/default/persistence/Apply/Cancel and gesture arbitration checks are
updated. Both renderer builds/lint and test-APK compilation pass; device execution
and physical control/zoom/pen feedback remain pending a connected tablet.

Previous UI marker: `live zoom v1` in Settings / `live-zoom-v1` in launch logs.
Pinch and toolbar zoom use 75% through 400% in 25 percentage-point steps; Fit
can still show a smaller whole page. Pinch now changes the complete visible canvas
during contact. Navigation coalesces on animation frames, with one immutable
snapshot in flight and only the latest requested view retained. Each submitted
view seeds the whole screen, including exposed paper/background edges; upright
seeds keep visible-document cropping. Surface
lock/transform/draw/post stay on the existing worker with memory/synchronous
fallback. In-flight views can finish during movement to avoid starving display;
release/cancel invalidate pending frames and synchronously publish the
final/restored view before the existing deferred native reopen. Pen geometry,
saved crash guard, stroke commits and 450ms quiet gap remain unchanged.
New checks cover live viewport changes, 25-point controls, cancellation/anchoring,
and attached-surface PixelCopy comparisons before lift at 0/37 degrees, including
rapid changes and exposed edges. Both renderer builds/lint and test-APK compilation
pass. Device execution and physical pinch/pen feedback are pending a connected
tablet; no new responsiveness measurement is claimed.

Previous UI marker: `pen settings v1` in Settings / `pen-settings-v1` in launch
logs. Each pen retains independent settings in memory; explicit file/checkpoint
operations serialize the drawing's complete set and selection. Loading compiles
the selected brush before input reopens. No preference lookup, JSON or file work
is added to stroke callbacks. Native policy, the saved guard, pressure/tilt geometry,
immutable worker snapshots and 450ms handoff remain unchanged. Physical pen
matching/responsiveness limits remain as documented below.

Previous UI marker: `image import v1` in Settings / `image-import-v1` in launch
logs. JPG/PNG decode runs on the explicit file worker with canvas-bounded pixels;
layer/history insertion and one checkpoint occur while input is closed for import.
No decode, image file access or serialization is added to pen-up. The saved
crash guard, brush geometry and accepted handoff remain. See IMAGE_IMPORT.md.

Previous UI marker: `floating toolbar v1` in Settings / `floating-toolbar-v1` in
launch logs. The canvas fills the former top-control area, with an opaque floating
bottom toolbar. Native input excludes that one surface-local rectangle at session
open; the paper's rectangular clip remains. No contact-time region setters are
added. Brush geometry, native width/color policy, saved crash-guard preference,
per-stroke commits, immutable worker snapshots and the 450ms quiet gap are unchanged.
This UI update establishes no new physical responsiveness or pen-matching result.

Previous header/log: `notes pens v2` / `notes-pens-v2`. The separate BOOX pens row
adds original-SDK square nib, ballpoint and two textured pencils without changing
existing brush algorithms, the 450ms quiet gap or surface worker. Vendor geometry
is prepared once for bounds and rasterization, in 128-input batches with bounded
retained results; native handles close per prepared stroke. Device parameters are
captured at brush compilation, never per input sample. See BOOX_NOTES_PENS.md.
First-pass physical widths did not match; v2 corrects Notes' square-width factor
and pencil tilt configuration. Matching and ordinary-fast/burst responsiveness
still require physical confirmation; no end-to-end latency result is claimed.

Go103 v2 full-suite run: the 180-sample tilted fixture took warm geometry
~17.5–18.2ms Calligraphy, ~1.6ms Ballpoint, ~16–20ms fine Pencil and ~18–20ms
coarse Pencil. Initial Calligraphy/fine Pencil calls cost ~77ms/~59ms; their
cached raster draws cost ~1ms/~3.8ms (coarse ~3.2ms). Original SDK generation
therefore has a real pen-up CPU cost; retained native feedback does not erase
that cost. Existing Rust algorithms remain unchanged. The surface crop check
still copied 1,227,600 seed pixels/~6.3ms and a 2,000-pixel patch/~0.6ms.
These are single-suite stage observations, not a controlled latency benchmark.
Local evidence: `captures/notes-pens-v2/recovered-validation.log` (PID 13832).

Earlier build-marker paragraphs below describe previous revisions.

Current header/log is guard settings v1 / guard-settings-v1. The crash guard is now
a saved Settings > Advanced preference, default on. Applying it while idle closes
the old native session and reconfigures after layout; pen callbacks use a cached
boolean. Native feedback, dry raster v2, immutable worker snapshots and the 450ms
quiet gap retain their accepted behavior. No guard-off physical reliability or
latency result is claimed.

Portable drawing files add explicit background Save/Open operations; see
SAVE_FORMAT.md. The editor freezes and closes pen input only for those operations.
Stroke commits still do no artwork serialization or save-file writes, and the
native safety/450ms/immutable-snapshot presentation policy is unchanged. File
round-trips and input regression checks do not establish a new physical latency
measurement.

Current header/log: `preview fit v1` / `preview-fit-v1`. Native preview-only
fitting and the explicit firmware crash-guard code switch retain the accepted
input/presentation and dry raster v2 performance path. See
NATIVE_PREVIEW_CALIBRATION.md. Default builds enforce the guard; no guard-off
physical reliability or latency result is claimed.

## Brush performance follow-up (`brush-perf-v1`)

Checkpoint before optimization: `663eb14` (Pencil textured-preview trial). The user's
physical capture `captures/20261023-212120` showed normal upright strokes already
using partial surface patches, but the first stroke after a tool/cache reset copied
the entire 1860x2480 document (4.61M pixels, ~22–24ms UI preparation). Charcoal also
spent 61–114ms rasterizing 379/827-sample strokes on UI; surface work alone did not
explain the slow commits. Native feedback still ran while surface handoff waited
for the accepted 450ms quiet gap.

Three changes reduce that work:

- Closing/reopening an idle native reader no longer invalidates an acknowledged
  screen cache. Outstanding asynchronous work still invalidates; view changes,
  synchronous replacement, surface destruction and lifecycle flushing keep their
  existing guards. This avoids forcing a full seed on a tool change alone.
- Necessary upright cache seeds copy only the visible integer document region,
  with two source pixels of filter padding and a matching translated transform.
  Rotated snapshots keep the full-document fallback. Workers still receive
  immutable pixels only, and preserve their full screen buffer for expanded surface
  locks. `asyncSnapshot` now logs the reason, source rectangle and pixel counts.
- Dry raster v2 submits 128 textured dabs at a time through a padded ALPHA_8 atlas
  and reusable ~15KiB vertex/texture/color arrays. No per-dab bitmap or vertex array
  allocations. Tip geometry, spacing, grain masks, dynamics and per-stroke history
  remain the same. Fine tips below 4px and hardware UI canvases use the reference
  bitmap path. The worker never gains access to mutable layer pixels.

Matched-device comparison, same 1,000-sample changing-angle geometry, three warm
measurements after one warmup: Pencil reference ~41–43ms versus batched ~17ms;
Charcoal ~42–44ms versus ~17–18ms. These are raster CPU times, about 2.5x faster,
not physical pen latency. Standard dry-brush benchmark with separate geometry
preparation measured ~20ms raster, previously ~58–60ms in the earlier run.

Attached-surface comparison on a 1860x2480 document: seed source reduced from
4,612,800 to 1,227,600 pixels (~73% fewer); matched UI preparation was 18.25ms
uncropped versus 5.76ms cropped. After idle close, the next test stroke copied a
2,000-pixel patch instead of seeding again. Single-run timings include allocation
and scheduling noise; source areas and pixel comparisons are the stronger evidence.
Rotated source remains 4.61M pixels and is an explicit remaining cost.

The triangle path introduces small raster rounding/edge differences; it is not
pixel-identical to the old bitmap path. Measured maximum visible difference in
the 1,000-sample comparisons was 14/255 (normal transparency), 6/255 under alpha
lock. Fidelity checks bound visible premultiplied ink on black and white to 18/255
and mean <4/255 in the long trace, and test dots, abrupt dynamics, gray and sizes
1/4/32/64/128. Locked alpha must remain exactly unchanged across all 256 levels.
Engine version is 2 for dry brushes; old raster artwork is never reinterpreted.

Async checks compare visible seeds and patches against the prior uncropped worker
path using a dense patterned document, plus the existing synchronous comparisons,
queued disjoint updates, cancellation, gray pre-lift feedback and eraser layering.
Native setup, async handoff and the <=30px/black crash guard are unchanged. Physical
ordinary-fast/burst input and the textured Pencil preview still need user feedback.
Recovered build/lint and the full Go103 suite pass, including the batching fidelity
checks and surface comparisons. The first crop-suite attempt hit the existing
layer-layout timeout while the tablet was not awake; awake targeted and final full
runs passed. No native gray/wide path was re-enabled.

Earlier investigation entries below are historical.

Brush-engine follow-up: Pencil/Charcoal keep native black/narrow input and the
accepted handoff; actual app previews use cached distance-spaced Rust geometry.
Shared alpha-only masks prepare in background, and JNI input is batched. The warm
1,000-sample texture benchmark still costs roughly 58–60ms to rasterize on UI;
native feedback and background surface presentation do not eliminate this cost.
See BRUSH_ENGINE.md for test conditions, limits and pending physical comparison.

Recovered-renderer Lab follow-up: user tested `f4bde6c` Fountain Lab and reported
"its good". Its fast live feedback still comes from BOOX firmware FOUNTAIN, while
the editable Rust renderer supplies final geometry. This feedback supports that
combined trial; it does not measure end-to-end latency or establish that Rust alone
provides the hardware preview. See RECOVERED_RENDERER_EXPERIMENT.md for boundaries.

Current revision: `penPipeline=native-eraser-v1` (retains `shader-safe-v2` input setup).
Baseline is committed as `388add9`. The user requested a native eraser trial:
white feedback is limited to <=30 document/screen px, one full-opacity unlocked
layer, alpha lock off. True CLEAR commits and 450ms background handoff are retained.
Physical white-eraser performance and stability remain unverified; all other eraser
cases retain app preview. See NATIVE_PREVIEW_SAFETY.md for the experiment's scope.

After a confirmed SurfaceFlinger
native allocator-race abort with a gray/wide Marker, native preview is restricted
to the previously accepted black/up-to-30px footprint envelope. Gray/wide brushes
use coalesced app live preview through the background worker. Native configuration
now occurs while paused; constant brushes have no contact-time width updater.
The reader is explicitly enabled before rendering; real SDK open/reopen checks
verify input readiness after the v1 startup regression.
See [NATIVE_PREVIEW_SAFETY.md](NATIVE_PREVIEW_SAFETY.md) for evidence, tests and limits.
Older paragraphs requesting gray colors from native firmware are historical.

## Accepted limitation: wide/gray Marker responsiveness

2026-10-23: after the crash workaround, the user reported Marker feels less
responsive and asked to document the limitation for now. Capture
`captures/20261023-151950` retains that session. Its latest stroke was a black,
100.8 document-pixel Marker at 1x zoom, using Android input and `app-round-brush`.
Width alone selected the fallback; gray was not required. Only black paint with
declared maximum footprint <=30px is currently eligible for native preview.
Marker's new 32px default also falls outside that compatibility envelope.

Inspected live updates show roughly 8–26ms surface-lock waits on the worker,
in addition to animation scheduling and UI snapshot preparation. Near the end
of the 573-sample stroke, some snapshots cost 5–10ms; its pen-up commit cost
36ms, mostly tile history (34ms), versus about 2ms brush rasterization.
These are pipeline stages, not measured stylus-to-visible latency. The software
path cannot currently be claimed to match the accepted native ink responsiveness.
Keep the crash guard; do not treat the slower preview as grounds for re-enabling
the unverified native shader path. Further app-preview optimization is deferred.
The new Eraser also uses this app preview path, with no native erase approximation.

Marker follow-up (2026-10-23): the asynchronous presentation/450ms/native-width pipeline is unchanged. Constant-width Marker now uses one round-capped Path instead of repeated circles/segments. On Go103, 1,000 samples at 128px measured median 49.72ms with the original renderer versus 1.77ms with `round-marker` (four warmups, five measured draws). This is raster CPU work only. Shade is opaque RGB and configured once per session/resume; no per-point color SDK calls are added. See BRUSHES.md for settings and native preview verification limits.

## Accepted baseline: background surface painting

Physical acceptance, 2026-10-23: after testing `async-handoff-v1`, the user reported an **"incredible difference"** and emphasized preserving this knowledge for future implementation. This confirms a major perceived improvement on the actual tablet for the combined implementation. It does not establish an exact latency figure or isolate the contribution of background painting versus the longer quiet gap and cancellation fixes.

Future maintenance starts with [../AGENTS.md](../AGENTS.md). Preserve the separation between immediate artwork/history commits, native live feedback, background screen painting and guarded native reconciliation. Recheck ordinary fast writing alongside very fast bursts after changing any of these boundaries. The earlier 150ms implementation demonstrated that a policy can feel smooth at one cadence and still stall at another.

Build marker: `penPipeline=async-handoff-v1` (2026-10-23). The user confirmed very fast writing was smooth with the prior batching policy, but ordinary fast writing could delay new lines. Capture `captures/20261023-124719` shows the 150ms timer could occupy the UI thread at the next contact: one handoff took 39ms and the next begin callback arrived 2ms after it completed. Callback time alone does not establish the pen's physical arrival time; raw begin now also logs point and callback wall timestamps for comparison.

`AsyncSurfacePresentation` now handles BOOX surface lock/draw/post on a dedicated HandlerThread. The UI supplies immutable pixels; the worker never reads DrawingDocument, live layer bitmaps, history or mutable viewport state. Upright dirty frames capture a small screen patch. Initial, rotated and view-change frames copy the document composite, then perform viewport transformation on the worker. A worker-owned full screen buffer preserves pixels when SurfaceHolder expands a requested dirty rectangle. One active job and one replaceable waiting job bound work; the UI unions unacknowledged dirty regions so coalescing cannot lose marks.

Frame epochs invalidate old worker jobs across surface/view/session changes. Synchronous painting and worker surface access share a gate to prevent two simultaneous canvas owners. Ordinary input callbacks do not take that gate. Session close cancels in-flight frames and publishes already-committed pixels; worker shutdown does not join on UI. Snapshot allocation uses a conservative remaining-heap estimate and falls back to the synchronous path on memory/allocation/presentation failure. Canonical bitmap/history mutations remain on UI and commit per stroke.

The quiet gap is now **450ms**, covering the intermediate cadence observed in the capture. Native live drawing is not intentionally delayed; final bitmap/layer/edge reconciliation waits for this gap. If contact or another completed stroke appears while a worker frame is in flight, its completion requeues reconciliation instead of resetting native drawing during the new stroke. BOOX native pause/resume/style/width calls remain on UI: the pinned SDK reaches hidden View methods through reflection, which does not establish that they are safe on a worker. The approximately 22ms native transition is therefore still a remaining cost after a quiet period.

New records: `asyncSnapshot` measures UI preparation, `asyncSurface` measures worker queue/lock/draw/post, `asyncCompletion` measures return-to-UI queue time, and `presentation deferredAgain=true` records a reset postponed because writing resumed. Small UI snapshots measured median **0.66ms** in the attached-surface test, while observed surface-lock waits of 10–42ms ran on the worker thread. These are stage measurements, not measured stylus-to-visible latency; large composites, cache initialization and memory fallback can cost more.

`AsyncSurfaceChecks` uses PixelCopy against the synchronous renderer for upright/rotated frames, multiple queued disjoint updates, and invalidation by a synchronous viewport replacement. An extra screen bitmap conversion causes low-channel rounding: tests allow up to 4/channel normally, and up to 20/channel in at most 0.1% of pixels at antialiased edges. The inspected rotated comparison had 400/1.6M pixels above 4 and maximum 17/channel. Artwork pixels are not changed by this screen conversion. Targeted background tests and the full device suite pass. Physical ordinary-fast handwriting and continuity through the remaining native transition still need user testing.

## Prior experiment: reconcile after a writing pause

Build log marker: `penPipeline=quiet-handoff-v1` (2026-10-23). After the user reported the prior change felt worse, a summary of the actual handwriting capture (`captures/20261023-123554/timing-summary.json`, PID 5653) showed 24 handoffs: median 47.14ms, p95 104.99ms, maximum 143.37ms. Native renderer pause/enable medians were 11.61/11.22ms; canvas-lock median was 8.90ms. The cheaper bitmap draw did not eliminate the main interruption.

`StrokePresentation` now postpones expensive canvas/native handoff until a 150ms quiet gap. New contact cancels the scheduled task while retaining a union of dirty document bounds and a stroke count. Completed point lists still paint the layer, capture history and enqueue diagnostics immediately, one history action per stroke. SDK begin/end/list callbacks already on UI are handled directly rather than posted a second time. No samples or extra bitmaps accumulate in the presenter.

After the gap, the presenter renders the combined bounds and performs one native renderer pause/resume/configuration. Both supported SDK list-before-end and end-before-list orders are tested. Stroke epochs reject cancelled-but-already-queued tasks. Closing/changing a session cancels its timer and publishes already-committed dirty pixels after closing native ink. Tool changes may close/publish a pending frame once contact and point-list commit have finished; they do not wait for the quiet timer. Android pen mode still presents per stroke.

This deliberately delays **final bitmap reconciliation** by 150ms after the last completed stroke. It does not intentionally delay native live ink or artwork/history commit. Layer opacity, masking and rotated-edge corrections can therefore remain native-preview approximations for that extra gap. Firmware can still have its own pen-up refresh behavior; continuous live feedback, ghosting and appearance require physical testing. There is no claim that the remaining native transition itself became faster.

New records: `handoffQueued` measures completed-list conversion/queue/commit without screen reconciliation, `commit deferred=true` identifies the fast commit path, and `presentation strokes=N quietMs=150` measures the combined render/native transition. `StrokePresentationChecks` verifies six rapid strokes have six immediate pixel/history commits and one presentation, merging of dirty regions, stale task rejection, both callback orders, cancellation and undo/redo.

The actual attached-SurfaceView benchmark reported immediate mode (six presentations): median commit 25.81ms, total 150.77ms. Deferred mode (one final presentation): median commit 3.96ms, total CPU/wall work 37.34ms. This test excludes the 150ms intentional quiet wait, native transitions and physical pen/panel latency. It demonstrates reduced work in between strokes, not a measured stylus-to-ink improvement. Build/lint and the full device suite pass for this revision.

Earlier sections below describe prior experiments and measurements.

## Follow-up from real handwriting (2026-10-23)

Capture `captures/20261023-122730` identified a different dominant cost from the isolated history benchmark: ordinary short strokes typically spent 20–25ms presenting a full canvas and 25–30ms in native preview handoff, for about 50–60ms total. Rapid bursts queued behind prior handoffs (examples exceeded 70–80ms queue wait), and some completed strokes had zero width updates. History for those short strokes was generally 3–8ms; diagnostics were off-thread with no dropped records.

Pinned SDK bytecode shows `SFTouchRender` posts begin/move/end/point-list callbacks to the host view UI thread. Reposting the initial width update let queued end/list callbacks cancel it before it ran. The adapter now executes a zero-delay begin update inline when already on UI; off-thread callers still post, and move updates retain 2ms coalescing and cancellation guards. An inline one-point-stroke regression check verifies the initial width is sent.

Committed upright strokes now compute conservative document bounds, map them to a screen rectangle and call `SurfaceHolder.lockCanvas(dirty)`. Android may enlarge that rectangle to preserve buffers; rendering uses the returned rectangle. Frame painting is shared with full rendering, including paper/background and layer composition. Navigation, layout, history and rotated canvases retain full rendering. Rotated partial painting failed exact pixel comparison in an initial test, so the rotated fallback is intentional. `DirtyFrameChecks` compares partial versus full frames across angles, zooms, edge crossings and layer opacity.

Per-stroke handoff now toggles **rendering only**, keeping the raw digitizer reader enabled. SDK bytecode distinguishes this from `setRawDrawingEnabled`, which additionally pauses/resumes the reader and resets default tool modes. Initial open and actual session close retain full configuration. After every render resume, black color, base width and PENCIL are still reapplied. New `nativeResume` timings separate pause, enable, color, width and style calls; physical preview continuity still needs retesting.

A real isolated SurfaceView benchmark (10 measured full/dirty pairs after four warmups) reported full median 40.86ms versus dirty median 15.41ms, including buffer-lock wait. Software draw itself was roughly 13–15ms full versus 0.3–0.9ms dirty. This is a small repeated test rectangle, not an end-to-end handwriting latency result. `surface` logs now include requested and actual pixel areas so buffer expansion is visible. The full device suite passes, including the frame comparisons, live-width scheduler and attached-surface benchmark.

The earlier sections below describe the first optimization pass; firmware handoff now follows the renderer-only policy above.

2026-10-23. Trigger: fast writing feels less responsive, particularly around pen-up. Preserve the shared pressure/tilt width and native PENCIL feedback while investigating. CPU/queue timings below are not measurements of the physical panel or end-to-end pen latency.

## Instrumentation

`StrokePerformance` emits `BooxPerf` logcat summaries using monotonic nanosecond timestamps. It defaults to enabled in this probe; `StrokePerformance.enabled = false` suppresses output. No per-point logcat messages are added.

| Record | Interpretation |
| --- | --- |
| `liveWidth` | Move/update counts, worst width-consumer lateness beyond the requested delay and slowest native width setter. High lateness indicates scheduling pressure; neither duration measures firmware presentation. |
| `history` | Tile enumeration, before capture, bitmap brush application and after capture/comparison. Reports candidate and changed tile counts. |
| `surface` | SurfaceView canvas lock, software draw/composition, and unlock/post durations. This can also occur during navigation or UI changes; posting is not physical refresh completion. |
| `commit` | History, control notifications, surface render, diagnostics enqueue and total synchronous commit. |
| `handoff` | BOOX completed-list conversion, UI queue wait, commit, SDK preview resume and total from list receipt. Does not include firmware delay before the list callback. |
| `diagnostics` | Background serialization/file work, worker queue wait, backlog and dropped diagnostic records. |
| `benchmark` | Isolated geometry/history test on a BOOX-size document; excludes SurfaceView/native preview/diagnostic writer. |

`dev.ps1 capture` now includes BooxPerf in its saved logcat. To inspect live timings from PowerShell:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -v threadtime -s BooxPerf BooxProbe AndroidRuntime
```

For an isolated CPU benchmark, install app/test APKs via `dev.ps1 test`, wait for that command to finish, then run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am instrument -w -e performanceOnly true art.boox.probe.test/art.boox.probe.RasterInstrumentation
```

Do not overlap instrumentation sessions. The benchmark owns a temporary in-memory document and does not mutate saved artwork. Instrumentation leaves the app stopped; reopen with `dev.ps1 run`.

## Changes

1. Serialize full stroke JSON and append `strokes.jsonl` on a single background-priority worker. Snapshot document/layer IDs, dimensions, viewport, wall time and stroke number on the UI thread. Worker receives no document bitmap. Four waiting jobs plus one active job bound the backlog; saturation drops diagnostic records and reports a count. Artwork, undo and checkpointing are unaffected. Activity destruction drains the bounded queue without joining on the UI thread. Process death may lose pending diagnostic records.
2. Reuse one native Deflater, pixel array, byte buffer and compression scratch buffer across each history transaction instead of allocating them for every before/after tile. Release native resources with `use`, including failures. History remains synchronous, lossless and immediately available.
3. Reuse the preceding sample's computed brush radius across segments. Geometry and sample capture are unchanged.

The firmware handoff ordering, live 2ms coalescing/jitter policy, document composition and pressure/tilt formulas are preserved. Moving history itself to a worker would require new ownership/ordering and memory guarantees; it is deliberately not part of this optimization.

## Measurements

Go103, 1860×2480 document, continuous 900px-wide sinusoidal strokes, pressure ramp and fixed inclination; 14 strokes per sample count, first four excluded, ten measured runs. The path shifts vertically, so candidate tiles vary between 8 and 16. Baseline output is saved in `captures/performance-baseline/timings.txt`. An intermediate buffer-reuse version is in `captures/performance-optimized/timings.txt`.

| Samples | Baseline median | Intermediate median | Baseline p95 | Intermediate p95 |
| --- | --- | --- | --- | --- |
| 100 | 20.20 ms | 15.62 ms | 21.54 ms | 16.71 ms |
| 500 | 22.57 ms | 18.80 ms | 23.37 ms | 19.56 ms |
| 1500 | 31.54 ms | 27.41 ms | 36.03 ms | 28.41 ms |

These preliminary comparisons cover buffer reuse/radius caching. History byte totals matched the baseline exactly. An additional uniform-tile scan was tested and removed: its CPU overhead made the benchmark slower (about 25/27/35ms medians). That rejected experiment is retained in `captures/performance-final/timings.txt`; the misleading directory name reflects when it was tested, not the shipped implementation. Small samples and a single run cannot characterize sustained handwriting, thermal effects, frame scheduling or layered-composite cost. No numerical end-to-end latency improvement is claimed.

Final shipped-version repeat (`captures/performance-shipped/timings.txt`): medians 16.60/18.67/26.81ms and p95 18.14/20.77/28.02ms for 100/500/1500 samples, respectively. This is approximately 15–18% lower median CPU time than baseline; history byte totals again match. Build/lint, the full device suite, and the isolated benchmark/live-width scheduler checks pass. The initial overlapping instrumentation attempt was discarded; a later UI timeout was traced to the tablet being Dozing and the suite passed after waking it.

## Physical follow-up

Draw short rapid letters/dots with minimal gaps, then long fast strokes and pressure/tilt ramps, at several zooms and with one/multiple layers. Capture before and after the session. Compare high `handoff.queueMs`, `commit.historyMs`, `surface.drawMs/postMs` and `handoff.resumeMs` to determine where the next effort belongs. Check `liveWidth.maxScheduleLatenessMs` for width updates stalled by UI work. Test that adjacent strokes retain native feedback and no marks disappear at handoff. A screenshot after pen-up does not establish this.

Automated checks include exact history round-trips, edge tiles, color/alpha, no-op redo preservation, failure rollback, memory eviction, original brush geometry and bounded/nonblocking diagnostics queue ordering. Run build/lint and the full device suite after coupled rendering/history changes.
