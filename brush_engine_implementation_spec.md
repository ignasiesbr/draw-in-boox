# Brush Engine Implementation Specification

## Codex implementation brief for a performant raster-art brush system

**Status:** Proposed implementation plan\
**Target:** A raster drawing app, including low-power/E Ink tablets\
**Purpose:** Implement a responsive, extensible brush engine inspired by
publicly documented concepts in Krita and MyPaint. This is an
architectural specification, not a claim to reproduce Krita's internal
implementation exactly.

------------------------------------------------------------------------

## 1. Goals

Implement a brush system that:

-   Produces continuous strokes from irregular stylus/mouse events.
-   Supports configurable brush presets without executable scripts.
-   Maps input signals (pressure, speed, tilt, direction, randomness) to
    brush parameters.
-   Keeps the per-event/per-dab hot path allocation-free or close to it.
-   Separates brush configuration, stroke state, rasterization, document
    compositing, and display refresh.
-   Supports a small efficient initial engine, with extension points for
    textured and specialized engines.
-   Is deterministic enough to replay strokes when given the same input
    events, preset, random seed, and engine version.
-   Works on low-power devices and does not tie rendering cadence to E
    Ink panel refresh cadence.

## 2. Reference model: what to borrow from existing applications

Krita documents brush presets as serialized data containing the brush
engine, parameters, brush tip, optional texture, and preview. Its `.kpp`
format packages XML annotations in a PNG; MyPaint presets use JSON plus
a preview image. Krita's paint-op settings are metadata used to
construct runtime painting objects and caches, rather than storing those
runtime objects in the preset.

MyPaint's brush model defines mappings from dynamic inputs such as
pressure, velocity, and randomness to outputs such as radius, hardness,
and color. Its `stroke_to()` API accepts timestamped input samples,
maintains state, interpolates between events, and emits dabs. Krita's
pixel brush documentation similarly describes repeated brush-tip
impressions along a stroke, with spacing controlling dab density and
sensors controlling dab properties.

**Design principle:** copy these high-level patterns, not their exact
file formats or all their complexity.

## 3. Proposed architecture

Keep these modules independent:

1.  **Preset model and validation**
    -   Versioned declarative data format.
    -   Defaults, bounds, migration, and validation.
    -   No executable code in presets.
2.  **Input normalization**
    -   Convert platform-specific pointer/stylus events into a stable
        internal sample.
    -   Normalize pressure and tilt when available.
    -   Use event timestamps, not processing-time timestamps.
    -   Treat missing pressure/tilt as supported capabilities, not fatal
        errors.
3.  **Dynamics evaluator**
    -   Map normalized inputs to brush parameters.
    -   Evaluate small piecewise-linear curves initially.
    -   Precompute curve tables or coefficients when a preset is
        compiled.
    -   Avoid parsing JSON, allocating objects, or looking up strings
        per event.
4.  **Stroke processor**
    -   Maintain state across input events: filtered velocity, prior
        position, accumulated distance to next dab, direction, random
        generator state, and brush-specific state.
    -   Interpolate event gaps and emit dabs according to spacing.
    -   Reset only at explicit stroke boundaries; do not accidentally
        discard state between ordinary motion events.
5.  **Brush engine**
    -   Start with a **dab engine**: stamp a precomputed tip mask along
        the path.
    -   Keep engine-specific behavior behind a small interface.
    -   Add spray/particle, textured, and smudge engines only when the
        base engine is stable.
6.  **Raster backend**
    -   Blend dab pixels into the active layer or a stroke buffer.
    -   Use clipped integer bounds and row-major memory access.
    -   Support dirty rectangles/tiles so only affected canvas regions
        need recompositing.
    -   Keep color/alpha math explicit and tested.
7.  **Display presenter**
    -   Receives dirty regions or completed stroke updates.
    -   Independently chooses partial/full display refresh policy for E
        Ink hardware.
    -   Never make the brush engine wait for a physical panel refresh.

## 4. Data model

A suggested initial schema (adapt names/types to the project's
language):

``` json
{
  "schemaVersion": 1,
  "name": "Basic Pencil",
  "engine": "dab",
  "tip": {
    "kind": "circle",
    "hardness": 0.9,
    "spacing": 0.15
  },
  "base": {
    "sizePx": 5.0,
    "opacity": 1.0,
    "flow": 1.0,
    "angleRadians": 0.0
  },
  "dynamics": {
    "sizePx": {
      "input": "pressure",
      "curve": [[0.0, 0.15], [1.0, 1.0]]
    },
    "opacity": {
      "input": "pressure",
      "curve": [[0.0, 0.25], [1.0, 1.0]]
    }
  }
}
```

Rules:

-   All normalized values are finite and clamped to documented ranges.
-   Curve x coordinates are strictly increasing, begin at 0, and end at
    1; either validate this strictly or normalize during preset
    compilation.
-   Unknown fields should be handled according to a documented
    compatibility policy.
-   Presets must have a schema version and migration path.
-   Store stable IDs for engine/input/property names; never depend on
    display labels.
-   Presets contain data only. Do not permit arbitrary expressions,
    scripting, or dynamic code execution in version 1.
-   Keep preview thumbnails and optional tip/texture assets as separate
    resources or well-defined embedded assets.
-   Distinguish a **preset** (serializable user configuration) from a
    **compiled preset** (validated settings plus precomputed
    tables/resources) and a **stroke instance** (mutable state for one
    stroke).

## 5. Input sample contract

Normalize platform events into a compact structure similar to:

``` text
PointerSample {
    x: float
    y: float
    pressure: float       // normalized 0..1
    tiltX: float          // normalized device-independent range
    tiltY: float
    timestampSeconds: float
    buttons: bitset
    capabilities: bitset  // pressure/tilt available, etc.
}
```

Requirements:

-   Use the hardware/platform event timestamp when available. Processing
    time introduces velocity errors when the UI thread is busy.
-   Preserve subpixel coordinates.
-   Clamp invalid values and reject NaN/infinity.
-   Support mouse input: pressure can be synthesized as a fixed value
    while pressed; document the behavior.
-   Process zero-pressure motion events if needed to maintain
    velocity/direction estimates; stroke begin/end must be explicit.
-   Do not smooth positions by default in a way that creates visible
    lag. Filter velocity/dynamics independently from the raw path.
-   Define how duplicate timestamps and out-of-order events are handled.

## 6. Dynamics mapping

Initial inputs:

-   `pressure` --- normalized pen pressure.
-   `speed` --- filtered speed in document pixels per second.
-   `direction` --- movement direction in radians.
-   `tiltX`, `tiltY` --- normalized stylus tilt when supported.
-   `random` --- deterministic pseudo-random value.
-   `strokeProgress` --- optional normalized progress through a stroke.

Initial outputs:

-   `sizePx`
-   `opacity`
-   `flow`
-   `angleRadians`

Use a compact curve representation. Piecewise-linear curves are
sufficient for the first version and cheap to evaluate. At preset
compilation, build a small lookup table (for example, 256 or 1024
entries depending on quality needs) or precompute segment slopes.
Benchmark both; do not assume a lookup table is faster on every target.

For velocity, use a time-aware low-pass filter rather than
frame-count-based smoothing. A common form is:

``` text
alpha = exp(-dt / timeConstant)
filtered = alpha * filtered + (1 - alpha) * input
```

Handle `dt <= 0`, large time gaps, and stroke boundaries explicitly. Use
filtered velocity for speed-sensitive dynamics, but preserve the
original sampled path for geometric interpolation.

## 7. Stroke interpolation and dab placement

Do not draw one dab per pointer event. Event rates vary, so that
approach causes gaps at fast movement and excessive overlap at slow
movement.

Maintain `distanceUntilNextDab` or equivalent accumulated path distance.
For each segment between previous and current sample:

1.  Compute segment length in document coordinates.
2.  Determine the current brush diameter and spacing according to a
    documented policy.
3.  Place dabs at spacing intervals along the segment.
4.  Interpolate position, pressure, tilt, and timestamp for each dab.
5.  Evaluate dynamics and rasterize each dab.
6.  Preserve the unused distance at the end of the segment for the next
    event.

Use subpixel positions. Define spacing clearly, preferably as a fraction
of effective brush diameter with a configurable minimum. Avoid a
zero-spacing configuration that can generate unbounded work. Add limits
for maximum dabs per input segment and robust handling of huge jumps
caused by device glitches.

Important: pressure/size can vary within a segment. The implementation
must define whether spacing uses the previous, interpolated, or current
effective diameter. Start with interpolated diameter and verify
visually; make this policy testable.

## 8. Dab rasterization

For the first engine:

-   Support a generated circular tip and a bitmap/grayscale tip.
-   Cache tip masks by all parameters that affect the mask
    (e.g. diameter bucket, hardness, aspect ratio, rotation if baked
    into the mask).
-   Do not regenerate a bitmap tip for every dab.
-   Use a fast path for a hard circular brush if it is materially
    faster.
-   Clip dab bounds to the target layer/canvas before entering pixel
    loops.
-   Iterate contiguous rows/pixels; avoid per-pixel virtual calls.
-   Keep blend/composite logic in tight, testable functions.
-   Make alpha/flow semantics explicit. Do not let "opacity" and "flow"
    become synonyms accidentally.
-   Reuse temporary buffers and scratch memory.
-   Use integer bounds around each dab and a subpixel offset only where
    needed.
-   Avoid expensive trigonometry per pixel. Precompute rotated tips or
    use a cheaper transform strategy if rotation is supported.
-   Add SIMD/vectorization only after profiling identifies the pixel
    loop as a bottleneck.

If rendering uses tiles, mark only tiles touched by the dab as dirty. A
stroke should not force a full-canvas redraw.

## 9. Performance requirements

### Hot-path rules

-   No JSON parsing, preset validation, string comparisons, or resource
    loading during a stroke.
-   No heap allocation per input event or per dab.
-   No locks in the inner pixel loop.
-   No UI work from the raster loop.
-   Preallocate/reuse stroke buffers, dab buffers, and tile lists.
-   Use stable numeric enums/IDs internally.
-   Cache compiled dynamics and tip resources.
-   Keep display refresh scheduling outside the engine.
-   Avoid unbounded catch-up work after a long frame pause; process
    input robustly and preserve stroke continuity.

### Threading

Begin with a correct single-threaded stroke engine and profile it. If
rendering needs concurrency:

-   Keep stroke-state mutation serialized per active stroke.
-   Parallelize independent tiles or independent brush-tip preparation,
    not unordered overlapping dabs that must blend sequentially.
-   Preserve dab ordering where alpha blending or smudging makes order
    observable.
-   Use immutable compiled presets/resources shared between strokes.
-   Avoid introducing thread synchronization into every dab.

Krita documents multithreaded brush-tip processing in its pixel brush
engine, but the appropriate strategy depends on the app's raster backend
and target hardware. Do not copy a threading strategy without profiling.

### E Ink-specific rules

-   Keep the canvas model and raster result independent of physical
    refresh.
-   Send dirty rectangles/tiles to a presentation layer.
-   Allow a fast interaction mode and a cleaner refresh mode if the
    hardware API supports them.
-   Never block stroke processing on panel refresh completion.
-   Avoid continuous animated brush previews and expensive live filters
    during drawing.
-   Provide a manual full-refresh mechanism in the UI if supported by
    the device.
-   Test ghosting, partial-refresh artifacts, latency, and battery
    consumption on actual target hardware.

## 10. Stroke compositing and correctness

A dab-based brush can darken at overlapping dabs if every dab is
composited incrementally. This may be desirable for pencil/paint
brushes, but not for every brush.

Define explicit modes rather than relying on accidental overlap
behavior:

-   **Incremental:** each dab composites onto the target as it is
    emitted.
-   **Stroke accumulation / maximum-alpha style:** dabs are combined
    into a stroke mask/buffer and the stroke is composited with the
    intended opacity semantics.
-   **Smudge/mix:** samples existing canvas pixels and therefore has
    ordering and read-after-write requirements.

Start with incremental compositing for the simplest engine. Add
alternative modes only with reference tests. For smudge, ensure later
dabs can observe the effect of earlier dabs when that is part of the
brush's intended behavior.

Use a documented color space and alpha convention. Test transparent
edges carefully to avoid halos and fringes when scaling or compositing.

## 11. Testing strategy

### Unit tests

-   Preset schema validation and migration.
-   Curve endpoints, interpolation, clamping, and malformed curves.
-   Input normalization, missing pressure/tilt, and invalid timestamps.
-   Low-pass filter response at different event rates.
-   Distance accumulation and spacing across event boundaries.
-   Deterministic random input with a fixed seed.
-   Dab clipping at canvas edges.
-   Blend modes, opacity, flow, and alpha behavior.
-   Undo/redo integration for strokes.

### Golden/reference tests

Render fixed stroke samples with a known preset and compare output
images within a defined tolerance. Include:

-   Slow straight line.
-   Fast line with sparse events.
-   Curved line.
-   Pressure ramp.
-   Sudden pressure changes.
-   Direction-sensitive tip.
-   Stroke near each canvas boundary.
-   Long stroke crossing many tiles.
-   Identical stroke replayed twice with the same seed.

### Performance benchmarks

Benchmark separately:

-   Input event processing (without rasterization).
-   Dynamics evaluation.
-   Dab generation.
-   Rasterization for small, medium, and large brushes.
-   Tip cache hit/miss.
-   Long strokes on large canvases.
-   Dirty-tile recomposition.
-   End-to-end stylus-to-visible-update latency.

Record median and high-percentile timings, allocations per stroke, dabs
per second, and memory use. Set targets only after measuring the actual
target device. Avoid claiming a universal millisecond target without
hardware data.

## 12. Suggested implementation phases

### Phase 1 --- Correct minimal engine

-   Versioned preset schema.
-   Circular dab engine.
-   Pressure-to-size and pressure-to-opacity mappings.
-   Timestamped input samples.
-   Interpolation and spacing accumulator.
-   Basic alpha compositing.
-   Unit tests and deterministic stroke replay.

### Phase 2 --- Optimize

-   Compiled presets and curve precomputation.
-   Brush-tip caching.
-   Buffer reuse and allocation profiling.
-   Dirty tiles/rectangles.
-   Performance benchmarks on the target device.

### Phase 3 --- Artist controls

-   Brush preset UI.
-   Tip hardness, spacing, opacity, flow, size.
-   Editable mapping curves.
-   Bitmap tips and texture support.
-   Preset import/export and version migration.

### Phase 4 --- Advanced engines

-   Scatter/spray.
-   Directional or bristle tips.
-   Smudge/color mixing, with explicit canvas sampling semantics.
-   Optional specialized rendering paths justified by use cases and
    profiling.

## 13. Acceptance criteria

The implementation is acceptable when:

-   A stroke remains continuous across sparse or irregular input events.
-   Identical recorded inputs and preset versions produce reproducible
    results where deterministic mode is enabled.
-   The hot path performs no avoidable heap allocations per event or
    dab.
-   Presets are validated and compiled before drawing begins.
-   Tip resources and dynamics are cached and invalidated correctly when
    settings change.
-   Canvas edges and tile boundaries do not introduce gaps or seams.
-   Brush rendering does not block on E Ink refresh operations.
-   Automated correctness tests and target-device benchmarks exist.
-   Performance claims are based on measured results, not assumptions.

## 14. Reference links

These sources informed the architectural recommendations:

1.  **Krita Manual --- Brush Preset:** preset contents and `.kpp`/`.myb`
    storage.\
    https://docs.krita.org/en/reference_manual/resource_management/paintoppresets.html

2.  **Krita Manual --- Pixel Brush Engine:** brush tips, spacing,
    sensors, and multithreaded brush-tip processing.\
    https://docs.krita.org/en/reference_manual/brushes/brush_engines/pixel_brush_engine.html

3.  **MyPaint --- Brush Engine backend documentation:** libmypaint and
    time-aware low-pass filtering.\
    https://www.mypaint.app/en/docs/backend/brush-engine/

4.  **libmypaint --- brushsettings.json:** canonical definitions of
    brush inputs/settings.\
    https://github.com/mypaint/libmypaint/blob/master/brushsettings.json

5.  **libmypaint --- Using Brushlib:** mapping inputs to settings and
    `stroke_to()` interpolation/state model. This wiki page documents an
    older API version, so treat it as conceptual guidance and verify
    current source before adopting exact APIs.\
    https://github.com/mypaint/libmypaint/wiki/Using-Brushlib

6.  **Krita source documentation --- KisPaintOpPreset:** separation
    between serialized preset metadata and runtime objects.\
    https://srcdoc.krita.maou-maou.fr/structKisPaintOpPreset.html

## Instructions to Codex

Inspect the existing repository before implementing anything. Identify
the language, UI framework, canvas storage/compositing model, stylus
event path, undo architecture, and E Ink display API. Adapt this
specification to the existing architecture rather than introducing a
parallel canvas or brush system. Implement Phase 1 first, add tests,
then profile before optimizing. Report which requirements are
implemented, which are deferred, and benchmark results on the actual
target environment.
