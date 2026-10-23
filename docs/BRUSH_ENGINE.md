# E-ink brush engine

The editable engine is in BOOX Art Lab (`booxRenderer=recovered`). Firmware still
owns native live ink. Rust supplies committed geometry; the same geometry supplies
app preview for gray/wide brushes. The accepted native input, 450ms quiet gap,
immediate history, immutable snapshots and background presentation remain intact.
Native black feedback is an approximation until physically compared for each tool.
Do not widen the <=30px declared-footprint policy or send gray to native rendering.

Current build `brush perf v1` retains the Pencil textured-preview trial described
below and adds dry raster engine v2: padded texture atlas, 128-dab submissions and
fixed scratch arrays. Tiny tips and hardware dialog canvases retain the bitmap
reference path. Geometry/tip masks are unchanged, but raster rounding can differ
slightly. Complete stroke history, alpha lock and gray app previews share the new
bitmap renderer. See PERFORMANCE.md for controlled comparisons (~2.5x faster dry
raster) and remaining costs. Earlier v1 mask/timing paragraphs are historical.

## Brush

`lab_brush` uses firmware NEO_BRUSH and renderer type 1. Modern source rendering
consumes every pressure sample, with width `2 * size * sqrt(pressure)`; it avoids
the recovered reference implementation's delayed coordinate and growing width.
Legacy renderer behavior is unchanged. The conservative footprint is `2*size+3`;
the 12px default remains inside the native guard. No second app pressure curve or
live width setters are applied. Size and gray are in the selected tool's dialog.
Geometry is cached for bounds, tile history and rasterization; handles are released
on both success and failure. Pixel undo/redo and alpha lock retain their semantics.

Validation: source host tests cover complete pressure batches with no retained
history. Device checks cover deterministic raster replay, actual bounds, dots,
complete undo, exact redo, gray and alpha lock. Real SDK setup tests switch every
available native family and return to Ink. These checks do not measure stylus-to-ink
latency or prove firmware/raster fidelity; ordinary-fast and burst writing still
need physical comparison.

## Pencil

Physical feedback on `82b773c`: user reports Charcoal works okay, but Pencil preview
differs from its real look. Capture `captures/20261023-212120` preserves that trial.
Pencil had a solid round firmware PENCIL preview despite textured/translucent,
elliptical final geometry. Sharing only major width cannot reproduce those pixels.

`pencil-preview-v2` trials firmware CHARCOAL_V2 (style 6) for Pencil's small black
feedback. It owns native dynamics; no app width updater is attached, avoiding a
second pressure response and contact-time shader changes. Rust type 11, tip masks,
final pixels, controls and the 450ms async handoff are unchanged. Charcoal retains
its physically tested firmware CHARCOAL (style 4). Gray/wide brushes keep actual
app preview. This textured native candidate still needs physical comparison; SDK
setup and raster tests cannot establish a match. Header/log identify pencil preview v2.
Earlier Pencil preview paragraphs below describe v1.
Build/lint and full Go103 suite pass for v2, including native family setup/reopen.
Pencil/Charcoal fixture PNGs are byte-identical to the pre-v2 fixtures, confirming
the preview trial did not change their final artwork. This is not a measurement
of native live pixels; physical confirmation remains pending.

`lab_pencil` is source-only type 11, 1–64px, default 8px. Pressure changes contact
width and opacity; tilt broadens the contact up to 2.5x by 65 degrees. Both controls
are enabled by default and can be disabled independently. Missing/invalid tilt
behaves upright. Raw Wacom axes and Android inclination/azimuth are normalized;
azimuth follows document rotation. The tip becomes elliptical for side shading.

Rust emits distance-spaced seven-float records (x,y,major,minor,angle in degrees,
opacity,tip variant). Spacing carries across batches, prediction uses a cloned
state, and 100,000 dabs bounds each stroke. Original types 1–10 retain their
encodings. App JNI calls batch up to 128 samples. Four deterministic shared 64px
texture masks cover all colors and sizes; no bitmap is allocated per dab. Native
handles are released in `finally` and cached primitive geometry serves bounds,
history and rasterization. Pressure/tilt need no extra per-point logging.

Black/narrow Pencil uses firmware PENCIL with the same app pressure/tilt major
width, retaining the accepted 2ms updater. Firmware PENCIL does not reproduce our
grain, transparency or elliptical contact. It is an explicitly approximate fast
preview, corrected after the quiet gap. Gray or >30px maximum footprint uses the
actual app brush for live ink. Turning tilt off can make a size native-compatible.

Validation: 27 source tests and full Go103 device suite pass, including tilted
footprints, missing/invalid tilt, disabled controls, document azimuth rotation,
gray/alpha lock, complete undo and deterministic redo. The 180-sample cold check
measured geometry 9.58ms and draw 32.14ms including first-use mask initialization;
these are CPU stages, not panel latency. Physical writing/preview matching remains
unverified. An initial suite run failed when the device slept; the awake rerun passed.

## Charcoal

`lab_charcoal` is source-only type 12, 1–128px, default 10px. It shares Pencil's
distance spacing, tilt ellipse, controls and seven-float contract. A coarser mask
with larger grain holes and stronger pressure/opacity response distinguishes it
from Pencil. At full tilt, a 10px tip has a 25px major diameter. Pressure changes
width and darkness; both dynamics can be disabled. Declared width includes tilt.
Black/narrow feedback uses firmware CHARCOAL, with no second app pressure curve.
Its own texture/pressure/tilt response can differ from our final renderer, including
when dynamics are disabled in settings. Physical comparison is still required.

Pencil and Charcoal masks now use ALPHA_8 so Paint supplies the selected gray
directly, avoiding a per-dab color filter. Eight shared 64x64 masks total 32KiB
of mask pixels. Preparation starts on a background thread when a dry brush is
compiled. Drawing waits for completion only if first use wins that race. Each dab
uses a reused transform matrix instead of saving/rotating/restoring the canvas.
No worker reads document pixels or changes history; only immutable tip resources
are prepared off-thread. Grain may leave the exact contact pixel empty: dot
checks verify visible ink in the contact region instead of requiring a solid center.

Use `./dev.ps1 test -Renderer recovered -BrushesOnly` for bitmap/geometry/bounds,
tilt, replay/history and the 1,000-sample warm benchmark. The full suite also checks
native SDK open/reopen, controls and async presentation. `brushEngine` and
`dryBrushBenchmark` logs separate geometry/raster CPU stages; neither measures
hardware input latency. Compare ordinary-fast writing, bursts and side shading
on the tablet before treating native preview fidelity as accepted.

Final validation (2026-10-23): 29 Rust host tests, recovered build/lint and full
Go103 suite pass. The source suite includes actual gray dry-brush live/committed
SurfaceView comparisons, no artwork/history mutation until lift, native family
open/reopen, independent controls, complete tilted tile undo and exact redo.
The official normal app build/lint and official Lab Brush/Fountain targeted checks
also pass; source-only dry tools are hidden in those builds. Saved selection falls
back to Tilt if switching to a backend without that tool.

Final warm 1,000-sample checks (2 warmups, 5 measured runs): Pencil median geometry
5.82ms / draw 57.86ms; Charcoal 5.28ms / 59.45ms. The initial ARGB/color-filter
version drew at 62.13ms / 68.94ms. These small runs show a modest raster improvement,
not native-equivalent software speed. Long textured strokes still have meaningful
UI raster cost on pen-up, separate from native feedback and background presentation.
Do not treat mask preparation or these CPU measurements as end-to-end latency gains.

Lab header/log marker: `brushes v1` / `uiRevision=brush-engine-v1`. Diagnostics
schema 9 appends document azimuth as point column 10 (older 9 columns unchanged),
records captured pressure/tilt flags and identifies each native/app brush preview.
Serialization remains on the bounded diagnostics worker. Artwork stays raster;
document/checkpoint formats and per-stroke undo semantics are unchanged.
