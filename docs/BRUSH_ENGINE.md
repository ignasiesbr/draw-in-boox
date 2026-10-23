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

## Planned shading brushes

Pencil and Charcoal need pressure and tilt: canonical inclination is 0 radians
upright, PI/2 flat; missing tilt behaves upright. Tilt should broaden the contact
for side shading, with deterministic distance-spaced texture and bounded memory.
Do not simply expose the recovered fixed-count Pencil or fixed-size Charcoal
fixtures. Source-only extensions must keep old type encodings compatible and be
hidden in official-binary builds. Native preview may approximate texture/tilt;
document physical limitations without removing the fast path or black safety cap.
