# Brush system and drawing toolbar plan

Implemented follow-up (2026-10-23): Marker/grayscale and round Eraser are available.
Tool settings open by tapping the selected toolbar icon again; separate right-side
brush actions are removed. Eraser clears selected-layer pixels with live layer
isolation and exact tile undo/redo. Gray/wide paint and erasing use app preview;
wide Marker responsiveness is a documented current limitation. See BRUSHES.md and
PERFORMANCE.md for current behavior; the original stages below are historical.

Proposed 2026-10-23; initial round presets and toolbar now implemented. Build/lint and the full device suite pass, including default geometry equality, preset persistence, captured commits and dialog checks. Physical comparison of the new presets and advanced-brush investigation remain pending. Read alongside BRUSHES.md, FEATURES.md and the supplied ../brush_engine_implementation_spec.md. Existing accepted live feedback takes priority over the generic spec.

## Current implementation

- Native Android Activity/Views, Kotlin, SurfaceView canvas; no Compose or external painting engine.
- RasterSurface owns one PressureBrush. It draws opaque black circles connected by variable-width filled segments. Sparse events already produce connected geometry; this is not one isolated stamp per event.
- Android pending strokes and committed strokes use that same brush. BOOX live ink is separate native rendering, followed by bitmap commit from its completed point list.
- BooxPenAdapter feeds LivePreviewWidth with PressureBrush.width(sample) times captured viewport scale. PENCIL prevents a second firmware pressure response. Begin queues immediately; moves coalesce at 2ms with a small jitter threshold. This interval is not a latency guarantee.
- Base width is 1-12 document pixels from normalized pressure; the replaceable inclination modifier reaches 2.5x. Raw pressure/tilt provenance is retained. Position maps once through the captured inverse viewport.
- History records before/after 128px tile patches through StrokeEdit; bounds use maximum brush footprint. Layers, alpha lock, layer opacity, checkpointing and PNG composition already exist.
- There are existing horizontal prototype command rows in MainActivity. What is missing is a dedicated drawing-tools toolbar and preset selection model.
- User testing confirms substantially better width matching. Exact native caps, smoothing and endpoint timing remain unverified. Hardware ink temporarily ignores layer opacity, alpha masks, upper-layer occlusion and exact rotated-page clipping.

## Product rule: preview first

Aim for the visible stroke to retain its appearance at pen-up. Preserve the accepted native path for the initial brushes. Shared mathematical width is necessary but does not prove equivalent pixels: firmware geometry and scheduling remain independent.

Initial scope: opaque black round brushes whose differences are expressible through width. Do not expose softness, texture, scatter, flow, opacity dynamics or directional tips as supported native-preview brushes. A generic black native trace would be misleading for those features.

Represent preview support explicitly in compiled brushes: native round-width support, or a future software-rendering requirement. Layer-composite limitations remain separate from brush capability. Do not silently switch modes when changing a brush. Before shipping advanced brushes, prototype shared-engine incremental software preview on the actual tablet and assess latency/ghosting against native feedback. If it cannot meet the product goal, defer those brushes or seek a deliberate product decision on approximation.

## Review of the supplied spec

| Proposal | Decision for this repository |
| --- | --- |
| Separate preset, compiled resources, stroke state, raster and presentation | Adopt as small boundaries in the current pipeline; retain DrawingDocument and adapter ownership. |
| Start with a circular dab engine | Defer replacement of current continuous round geometry. Preserve it as the first engine so baseline artwork and preview behavior do not change during extraction. |
| Pressure-to-opacity in phase 1 | Defer: native black preview does not currently demonstrate equivalent alpha behavior. |
| Declarative versioned presets, validation and stable IDs | Adopt. Start with built-in definitions and persisted typed settings; public JSON import/export can follow. |
| Shared dynamics and explicit stroke boundaries | Adopt. Begin with existing stateless pressure/tilt width, retaining its exact formula. Stateful speed/random dynamics need independent live/final equivalence work. |
| Normalized tilt X/Y and float timestamp seconds | Keep current nullable inclination, view-relative orientation, raw BOOX axes and Long event timestamps. Establish timestamp units/clock provenance before velocity/replay; derive relative double seconds if needed. Do not normalize raw axis direction into a fictitious shared orientation. |
| All listed dynamic inputs initially | Reduce to pressure and inclination. Direction is circular; whole-stroke progress is unknown during contact; neither is a generic 0-1 sensor without a defined policy. |
| Incremental alpha compositing first | Fine for future paint brushes, but specify flow versus whole-stroke opacity before implementation. Repeated segment/circle blending must not create accidental overlap darkening. |
| Allocation-free hot path | Target avoidable work after measurement. Current capture allocates samples/lists and LivePreviewWidth synchronizes scheduling; neither warrants a rewrite just to satisfy an absolute rule. |
| Dirty tiles and display separation | Reuse current history/composite invalidation. Profile actual SurfaceView presentation costs; dirty history regions alone do not prove partial screen updates. |
| Deterministic replay | Add sample fixtures and a replay harness. Store resolved preset/settings plus engine version; a preset ID alone cannot reproduce an edited preset. Keep raster patches authoritative for undo. |

Krita's official pixel-brush documentation supports tips, spacing, sensor dynamics and different accumulation modes. This validates the generic architecture, not compatibility with BOOX hardware ink. Sources: https://docs.krita.org/en/reference_manual/brushes/brush_engines/pixel_brush_engine.html and https://github.com/mypaint/libmypaint/blob/master/brushsettings.json. The local pinned SDK and accepted device evidence take precedence for native preview behavior.

## Proposed UI

Add a fixed, compact horizontal drawing toolbar immediately above the drawing area, below existing document/navigation controls. This is an initial placement recommendation, avoiding another side rail beside the layer panel. Preserve current command rows during this increment; their later consolidation is separate.

Initial controls, in order:

1. Ink: pressure-sensitive round brush without tilt widening.
2. Tilt: the current pressure-plus-tilt brush, selected by default to preserve behavior.
3. Monoline: constant round width, without pressure or tilt modulation.
4. Size action: labeled current maximum/base diameter in document pixels; opens numeric entry plus a slider and explicit Apply/Cancel. Pressure presets describe their minimum width and maximum tilted width clearly. Initial range must follow device width-limit checks and a conservative footprint/memory budget.
5. Brush settings action: opens a draft dialog for the selected preset, initially size, pressure response on/off and tilt widening on/off where applicable. Show a static stroke example generated with the actual bitmap engine. Keep the first revision simple; curve editors come later.

These are proposed user-facing names; native PENCIL is an implementation setting, not a claim of textured pencil appearance. No eraser icon until actual transparent erasing and live-preview behavior are implemented.

Use EinkIconButton and the existing vector glyph family, at least 48dp finger targets, white background, black outlines and a model-driven selected border. Every icon has a meaningful accessibility label and tooltip. Selection changes only after the command succeeds. Keep a visible short selected-brush/size label so similar round tools remain distinguishable. Avoid animated previews, expanding sliders on canvas and hover-driven continuous redraws.

Register stable action IDs independently of placement (for example brush.ink, brush.tilt, brush.monoline, brush.size, brush.settings). Prepare for later reorder/hide customization without implementing a toolbar editor now. Store selected preset and per-preset adjustments separately from document/history, using the existing typed preference infrastructure. Unknown IDs fall back to Tilt. Selecting a preset is not an undo action and does not reinterpret existing artwork.

Toolbar insertion/resizing must use the existing close-input, settle-layout, render, scheduled-reopen handshake. Preserve the SurfaceView attachment, document dimensions, panel-side preference and canvas coordinate alignment. Refuse brush/size changes during a stroke or navigation; dialogs keep native input closed until focus returns.

## Delivery sequence

### 1. Extract the brush contract without changing output

Introduce immutable BrushPreset, validated CompiledBrush, and a stroke settings snapshot with engine/version, resolved parameters and conservative footprint. Keep mutable Paint/Path renderer resources confined to their render thread, outside serializable settings. Wrap the existing continuous round renderer; retain the current pressure and smoothstep tilt formulas exactly rather than approximating them with a lookup table.

Make RasterSurface pending drawing, StrokeEdit and the BOOX width bridge consume the same captured compiled brush. Current adapter lambdas read view.brush dynamically; capture the brush alongside transform/layer when creating the session, and carry it into stroke commit. Prevent queued completed-point-list callbacks from being interpreted with a newly selected brush, even if raw end has already cleared the drawing flag. Use the existing generation guards and explicit handoff/busy state. Brush changes close/reconfigure the session.

Expand stroke diagnostics to record the snapshot from the stroke itself, not whatever brush happens to be selected when MainActivity.recordStroke runs. Older schema-5 samples retain missing-tilt semantics; no artwork checkpoint migration is required for raster pixels.

Exit: default bitmap fixtures unchanged, live width values unchanged, widest-stroke history bounds unchanged, stale callbacks cannot cross settings changes.

### 2. Ship the first three presets and toolbar

Add Ink, Tilt and Monoline, toolbar action dispatch, selection/size/settings drafts and preference persistence. Keep all initial brushes opaque black and round. Validate finite widths and bounded tilt multipliers before installing a compiled brush; reject invalid settings without disrupting the active preset. Test fallback for missing/unknown preset IDs.

Exit: settings affect the next stroke only; Apply/Cancel, restart, undo/redo, disabled layers and dialog focus behavior work; no pen alignment shift after toolbar/layout changes.

### 3. Validate preview retention on the Go 10.3

Run build/lint, LivePreviewWidthChecks, TiltChecks, history/layer/UI checks and the full device suite for the coupled change, using isolated test artwork. Add meaningful checks for snapshot races, preset selection, constant-width behavior, size bounds and persistence. Reopen the app after instrumentation.

Physically compare upright pressure ramps, steady-pressure tilt ramps, dots, fast curves, rapid adjacent strokes and Home/return at multiple zooms/angles. Include opacity/alpha-locked and obscured layers to distinguish known composite differences. Observe or record before-and-after pen-up; a final screenshot cannot validate live fidelity. Log sent native widths separately from measured appearance. Measure callback-to-width-setter timing, bitmap commit time, memory and visible latency without inventing a target before obtaining a baseline.

Exit: the accepted Tilt brush remains at least as good as today's observed match, new round brushes show no substantial unexplained width jump, and remaining geometry/composite differences are explicitly recorded. Exact pixel equivalence remains an aspiration unless measured.

### 4. Investigate advanced brushes before expanding the UI

Prototype an incremental engine/presenter connection using existing layer composition rather than a second document. Demonstrate matched live/final textured or soft output and acceptable tablet behavior before releasing such presets. Then implement deterministic spacing across event boundaries, interpolated dynamics, bounded dab work and a bounded tip cache. Define dab caps without silently dropping parts of a stroke; test sparse events and extreme jumps. Extend footprint calculation for rotated tips/scatter and include any stroke buffers in the existing memory budget.

Only then add softness, flow/opacity, textures, directional tilt, speed/random dynamics and optional preset import/export. Erasing is a separate follow-up with transparent pixel removal, shared tool selection, hardware capability testing and explicit alpha-lock semantics. Smudge/mixing and threading remain later work justified by use cases and profiling.

## Documentation follow-through

On implementation, update BRUSHES.md, FEATURES.md, PROBE.md, HANDOFF.md and README with actual behavior and device evidence. Reconcile plan.md milestone text: it still contains stale tilt-verification and layer-feature status and older generic native-style guidance. This document proposes a sequence; it does not mark those changes implemented or tested.
