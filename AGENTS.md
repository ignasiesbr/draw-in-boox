# Drawing performance: preserve the accepted baseline

The source-renderer trial is isolated in `art.boox.probe.lab`; see
[docs/RECOVERED_RENDERER_EXPERIMENT.md](docs/RECOVERED_RENDERER_EXPERIMENT.md).
Stable checkpoint is `29c7373`. Lab Fountain/Brush/Charcoal use firmware family
preview plus editable app-side Rust geometry; this does not replace firmware.
Pencil now trials firmware CHARCOAL_V2 for textured feedback after the solid PENCIL
preview differed visibly from final graphite; existing tools retain app-width
PENCIL. Never apply a second app width curve to vendor-dynamic families. See
[docs/BRUSH_ENGINE.md](docs/BRUSH_ENGINE.md) for source-only Pencil/Charcoal, tilt,
cached alpha masks, JNI batching, native approximation limits and validation.
Dry raster v2 batches 128 textured dabs through a padded alpha atlas. Keep its
fixed scratch budget and reference fallback for tiny tips/hardware UI canvases.
Idle native close preserves an acknowledged screen cache; actual pending frames
and view/surface changes must still invalidate it. Upright cache seeds copy only
visible document pixels; rotated full-document fallback is intentional.
The official-SDK alternative is documented separately in
[docs/BOOX_BUILTIN_BRUSHES_FALLBACK.md](docs/BOOX_BUILTIN_BRUSHES_FALLBACK.md).

Native crash guard: read [docs/NATIVE_PREVIEW_SAFETY.md](docs/NATIVE_PREVIEW_SAFETY.md)
before changing native preview policy. A gray/wide Marker caused a SurfaceFlinger
allocator-race abort on this firmware. Gray or >30px declared native-preview
footprints use app-rendered live ink. Dry-brush preview fitting can use a smaller
native base independently of final size; its envelope remains base*2.5 <=30px
(configured base <=12px). See docs/NATIVE_PREVIEW_CALIBRATION.md. Do not bypass
this policy based on synthetic tests.
Configure native parameters while drawing is paused, and never send contact-time
width setters for a constant brush. The black/narrow native path retains the
accepted pressure/tilt behavior and asynchronous handoff architecture below.
User-authorized experiment after baseline commit `388add9`: `native-eraser-v1`
permits white PENCIL feedback only for an eraser <=30 document AND screen px on
exactly one visible/unlocked layer at 100% opacity with alpha lock off. Commits still
CLEAR pixels. This is pending real-pen validation, not an accepted expansion of
gray/wide native support. Other eraser cases keep app preview. See the safety doc.
After SDK open, explicitly enable the input reader before enabling rendering;
`openRawDrawing()` alone leaves TouchHelper's input flag false. Verify real SDK
open/reopen readiness, not just the app's inputEnabled property.

Read [docs/PERFORMANCE.md](docs/PERFORMANCE.md) before changing pen input,
brushes, screen presentation, history or their lifecycle coordination.
Read [docs/BRUSHES.md](docs/BRUSHES.md) for the accepted live-width calculation.

On 2026-10-23 the user physically tested `async-handoff-v1` on the Go103 and
reported an "incredible difference". Treat this combined implementation as
the accepted performance baseline:

- Keep BOOX native live ink and the shared pressure/tilt width calculation.
  PENCIL receives the app-computed width; do not add a second firmware pressure curve.
- Commit artwork/history per stroke, independently from screen presentation.
  Do not put a surface redraw, native restart, JSON serialization or file write
  back into every pen-up's input-critical work.
- Present stable pixel snapshots on the surface worker. Never let that worker
  read live mutable document/layer bitmaps. Keep pending work bounded, union
  unacknowledged dirty regions, and preserve frame/session cancellation guards.
- Preserve the 450ms quiet-gap policy and defer native reset again when writing
  resumes during an asynchronous paint. Native SDK View calls remain on UI;
  moving them to a worker requires evidence that it is safe.
- Preserve synchronous fallback, memory checks and close/change-session flushes.

Use BooxPerf stage timings to identify regressions. Test ordinary fast writing
as well as very fast bursts: the earlier 150ms policy was smooth at the fastest
pace but interrupted the more common intermediate cadence. Synthetic CPU gains
and screenshots alone do not establish physical input responsiveness.

Keep the current build marker, physical feedback and remaining limits in
PERFORMANCE.md and HANDOFF.md accurate. The accepted result combines background
painting, batching and input/lifecycle fixes; do not attribute all gains to a
single change or claim a measured end-to-end latency that was not measured.
