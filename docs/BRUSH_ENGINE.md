# E-ink brush engine

The editable engine is in BOOX Art Lab (`booxRenderer=recovered`). Firmware still
owns native live ink. Rust supplies committed geometry; the same geometry supplies
app preview for gray/wide brushes. The accepted native input, 450ms quiet gap,
immediate history, immutable snapshots and background presentation remain intact.
Native black feedback is an approximation until physically compared for each tool.
Do not widen the <=30px declared-footprint policy or send gray to native rendering.

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

## Planned Charcoal

Charcoal also needs pressure and tilt: canonical inclination is 0 radians
upright, PI/2 flat; missing tilt behaves upright. Tilt should broaden the contact
for side shading, with deterministic distance-spaced texture and bounded memory.
Do not simply expose the recovered fixed-count Pencil or fixed-size Charcoal
fixtures. Source-only extensions must keep old type encodings compatible and be
hidden in official-binary builds. Native preview may approximate texture/tilt;
document physical limitations without removing the fast path or black safety cap.
