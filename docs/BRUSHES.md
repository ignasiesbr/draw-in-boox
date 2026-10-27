# Brush input, tilt and live preview

Per-pen settings (`pen-settings-v1`, 2026-10-25): each preset independently retains
size, shade, pressure/tilt and preview fitting/texture. The drawing owns the complete
set and selected pen; portable 1.1 files and restart checkpoints restore it. Files
without pen metadata and new canvases inherit the current settings. Preferences
schema 4 reads schemas 1–3 and freezes the old shared shade for every pen before
the first independent shade edit. Changing one pen never updates another. See
[SAVE_FORMAT.md](SAVE_FORMAT.md) for defaults and old-reader compatibility.
The shared width calculation, native policy and 450ms handoff remain unchanged.

Current Lab extension: Brush, Pencil and Charcoal join Fountain; see
[BRUSH_ENGINE.md](BRUSH_ENGINE.md) for presets, source-only contracts, tilt shading,
tests and native preview approximation limits. Existing four tools keep their
accepted geometry and input path. The paragraphs below describe earlier stages.

Lab-only Fountain trial: the optional `booxRenderer=recovered` build installs a
separate BOOX Art Lab app. It pairs native FOUNTAIN feedback with an editable Rust
final renderer. `booxLab=true` compares the same adapter with official binaries.
Existing presets retain their accepted app-width PENCIL response; the new preset
uses only vendor dynamics. Source/original final pixels differ in the current
fixture, so matching is experimental. See RECOVERED_RENDERER_EXPERIMENT.md and
the independent BOOX_BUILTIN_BRUSHES_FALLBACK.md before continuing this work.

Alpha-lock product decision (2026-10-23): the user accepts native feedback outside
the alpha mask during contact, corrected by the committed composite. Compatible
black brushes already remain native with alpha lock on; do not add a native gate
for this paint case. Pixels/history use SRC_ATOP and preserve the exact existing
alpha channel. Screen correction retains the accepted 450ms quiet-gap policy.
Gray/wide safety fallbacks and the eraser's alpha-lock restriction are separate.
The user explicitly chose to retain gray live preview when offered a black native
proxy for gray commits. Gray therefore stays app-rendered; do not silently change
its preview color to black to gain native speed.

Current experiment `native-eraser-v1` follows baseline commit `388add9`: a round
eraser <=30px in both document and screen coordinates uses white native feedback
on exactly one layer at 100% opacity, visible/unlocked and alpha lock off. Pixels
still clear to transparency on commit. Other eraser cases retain app preview.
Status identifies the experimental BOOX eraser; toggle Android pen to compare.
User physical validation is pending. This supersedes the always-app eraser wording
below, without relaxing the gray/wide paint guard. See NATIVE_PREVIEW_SAFETY.md.

**Native preview workaround (shader-safe-v2, retained in eraser-toolbar-v1):** the gray/wide native shader path
crashed SurfaceFlinger on this device. Marker sizes/shades remain available through
app-rendered live ink; only black brushes with a declared footprint up to 30px use
native rendering. Constant brushes no longer update native width at contact; native
setup is paused/configured/enabled in that order. App preview uses the same brush,
background snapshots and bounded in-flight work. See NATIVE_PREVIEW_SAFETY.md;
older native-color request guidance below must not be reintroduced without evidence.

## Eraser and selected-tool controls (2026-10-23)

The toolbar now contains Ink, Tilt, Marker and Eraser. Tap another tool to select it;
tap the selected icon again to open its settings with Apply/Cancel. Size, pressure,
tilt and gray shade are grouped in that dialog; separate right-hand size, settings
and shade icons have been removed. Shade is now remembered independently per pen.

Eraser has a fixed round tip, 1–128 document pixels, default 32px, with a separately
remembered size (`eraser` ID). Its settings show size only. It clears selected-layer
pixels to transparency, revealing lower layers or paper, rather than painting white.
Locked/hidden layers cannot be erased; alpha lock prevents erasing. Erasing does not
change the saved ink shade. Pixel history labels the action Erase and restores exact
before/after tiles on undo/redo. Raster checkpoint/PNG formats remain unchanged.

`round-eraser` v1 shares the constant round Path/dot geometry and uses CLEAR. Live
preview isolates the selected layer before clearing so paper and lower ink survive;
the same pixels are committed on lift. The native gate rejects erasers at every size.
Eraser uses the coalesced background app renderer; native black/narrow brush input
and the 450ms handoff remain unchanged. Schema-8 logs add `operation=paint|erase`;
older entries imply paint. Gray/ARGB fields on erasers retain the chosen ink shade
as context, not the erasing operation's color.

The user confirmed wide Marker is less responsive on this fallback and asked to
record the tradeoff for now. A captured 100.8px black stroke used Android/app preview;
even black Marker above 30px, including the 32px new default, takes this path. Black
paint at <=30px declared footprint can use native ink. Gray paint always falls back.
See PERFORMANCE.md for measured stages and NATIVE_PREVIEW_SAFETY.md for crash evidence.

Older toolbar-swatch and separate-size/settings instructions below are historical;
use the selected tool's dialog for all brush controls.

## Marker and grayscale ink (2026-10-23)

Monoline is now labeled **Marker**, with a broader nib icon. Its stored `monoline` ID is unchanged, preserving existing per-tool settings. Marker allows 1–128 document pixels and defaults to 32px for settings that do not exist yet; existing saved sizes are retained. Ink and Tilt retain their 1–12px base ranges and original response. Marker defaults to constant width. Optional pressure/tilt settings remain editable; their declared maximum footprint includes widening, so enabling tilt on a 128px Marker can reach 320 document pixels. History and dirty bounds use that conservative footprint.

Constant-width Marker uses engine `round-marker` v1: one round-capped/round-joined stroked Path per stroke, or a filled circle for a dot/all-coincident samples. This avoids repeated overlapping circle/segment rasterization at large widths. Enabling pressure or tilt uses the existing `continuous-round` engine instead. Existing artwork is raster, so changing this engine does not reinterpret saved pixels. Tests cover broad clipped strokes across multiple history tiles, dots after paths, captured size/shade, opaque overlaps, PNG, undo/redo and a 4× preview width of 512 sent screen pixels. This tests app width calculation, not firmware width limits.

`InkShade` provides opaque RGB gray levels 238, 204, 170, 136, 102, 68, 34 and 0. The toolbar swatch opens `InkShadeDialog`; the same `InkShadePicker` appears in Brush settings. Both edit a draft and Apply/Cancel. Shade, size and dynamics are remembered per-preset. Grays are RGB ink values, not reduced alpha: overlapping interiors keep the selected shade and light ink can cover dark pixels. White/paper and erasing remain separate. The static stroke sample scales large nibs to fit and labels its displayed percentage.

Historical preference schema 2 reads versions 1–2, keeps existing keys/IDs and adds global `ink_gray_level`. Missing/unknown shades fall back to black; invalid widths fall back to that preset's default. Schema-7 diagnostic strokes retain the old point layout and record captured `inkColorArgb`, `grayLevel`, settings version 2 and the actual engine ID/version. Old records with no color mean black. Raster checkpoint and PNG formats need no migration.

Bitmap rendering and `TouchHelper.setStrokeColor` receive the same opaque ARGB from the captured brush. Color is set at session setup/renderer resume, not per input sample. Full app-computed width still drives native PENCIL; the accepted worker, 450ms gap, cancellation and bitmap/history ownership are unchanged. Logs include `requestedColorArgb` and sent width ranges. These are requests, not measured native output. [Onyx's older Scribble API documentation](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Scribble-API.md) describes black/white-only native color; the pinned Pen SDK forwards general color integers but cannot establish grayscale support on this particular firmware. Physically compare very light/middle/near-black strokes and wide sizes before and after lifting, including zoom. Native shade/width fidelity remains unverified until that test.

Build/lint and the full device suite pass for Marker/shade behavior. A separate Go103 benchmark (1,000 samples, 128px, 4 warmups/5 measured draws) measured median 49.72ms for the original constant-width circle/segment renderer versus 1.77ms for `round-marker`. It excludes history, presentation and actual pen latency. See `captures/performance-marker-gray/timings.txt`.

The following initial-system sections are historical where they describe Monoline, 12px-only sizing or black-only configuration.

Physically accepted performance baseline: the user tested `async-handoff-v1` on 2026-10-23 and reported an "incredible difference". Keep the shared brush/native-width response and the asynchronous presentation boundaries together. AGENTS.md and PERFORMANCE.md record the rules for future changes; older pending-verification notes below refer to earlier stages.

Latest presentation revision (`async-handoff-v1`): 450ms quiet gap and background SurfaceHolder painting from immutable pixel snapshots. Raw width updates, pressure/tilt math and immediate pixel/history commits remain shared. Native SDK reset stays on UI and is deferred again if a new contact or committed stroke appears while the worker paints. Lifecycle changes invalidate worker frames and publish pending committed artwork. See PERFORMANCE.md for memory fallback, worker ownership, measured stage timings and physical checks; earlier 150ms presentation text below is historical.

Current writing-burst experiment (`penPipeline=quiet-handoff-v1`): raw strokes still commit bitmap pixels/history immediately, but `StrokePresentation` combines dirty bounds and delays display/native handoff until a 150ms writing pause. New contact cancels the timer; session close publishes pending committed pixels. This avoids paying native pause/resume between every short stroke. The shared pressure/tilt width, inline UI begin and 2ms move policy remain unchanged. The final layer/mask/edge composite now follows the quiet gap, so test physical feedback and pen-up appearance. See PERFORMANCE.md; older per-stroke display-handoff wording below is historical.

Latest fast-writing fix: the pinned SDK already posts raw callbacks to UI. Begin width therefore applies inline when already on UI, so queued end/list callbacks cannot cancel every update for a short stroke. Move coalescing remains 2ms. Bitmap handoff now pauses/resumes only native rendering, retaining raw reader input and still reapplying black/base width/PENCIL after resume. Initial open and session close still use full SDK configuration. Upright commits repaint conservative stroke bounds; rotated canvases retain full frames after exact comparison found rotated clipping differences. These details supersede older full-input-toggle/queued-begin wording below; see PERFORMANCE.md for real handwriting evidence and tests. New physical preview continuity testing remains required.

Performance follow-up: the width response/cadence is unchanged; per-stroke diagnostics now report worst scheduling lateness and native width-setter duration via BooxPerf. Full stroke JSON/file writes run off the UI thread on a bounded worker. History reuses compression resources and brush segments reuse the previous radius. See PERFORMANCE.md for stage logs, measured device CPU improvements and the remaining physical handwriting checks.

## Initial brush system (2026-10-23)

The dedicated drawing toolbar offers Ink, Tilt and Monoline, plus Size and Brush settings. Tilt defaults to the accepted original 1–12px pressure curve and 2.5× tilt response; Ink removes tilt and Monoline removes both dynamics. Each preset retains its own size/pressure/tilt adjustments in versioned `app_settings` preferences, outside artwork/history. Unknown selected IDs fall back to Tilt. Size is conservatively limited to 1–12 document pixels; tilt can widen the maximum footprint to 30px. No native width-limit expansion is claimed.

`BrushSettings` is immutable validated preset data; `compile()` creates the existing `PressureBrush` continuous-round renderer with fixed settings and renderer-owned Paint/Path resources. This deliberately retains circle/segment geometry instead of replacing it with a dab engine. Engine ID/version is `continuous-round`/1; preview capability is currently native round width only. No JSON import, texture, opacity/flow dynamics, dab engine, speed/random dynamics or eraser is implemented.

`RasterSurface` captures the brush for Android pending/committed strokes. `BooxPenAdapter` captures it with the session transform/layer and passes it to completed-list commit. Raw contact through handoff is busy for toolbar changes, including the interval after raw end and before the queued bitmap commit. Preset/settings changes close the old session and reopen through the existing deferred configuration handshake. Diagnostics use the committed stroke's captured brush, not the current toolbar selection. Schema 6 retains schema-5 point arrays and adds engine/version/preset metadata; resolved min/max width and modifier metadata remain sufficient for the current fixed dynamics. Old raster checkpoints need no migration.

`BrushSettingsDialog` edits a draft with numeric size, a visible black slider, pressure/tilt toggles, resolved width bounds and a static bitmap sample from the same renderer. Apply installs the next-stroke settings; Cancel discards them. `BrushChecks` covers baseline pixel equality, preset widths, invalid size rejection, preference isolation/fallback, captured queued commits and undo/redo, plus real dialog validation/Apply/Cancel. Tablet pen tests must still compare the new presets live and after lifting; synthetic equality cannot prove native geometry or latency.

This is the first tilt-width feature, ahead of the full brush engine. Keep input capture separate from brush behavior. The user chose wider strokes with tilt and explicitly requested an easily replaceable response.

## Accepted working state

On 2026-10-23 the user physically retested the shared pressure-plus-tilt width bridge and reported it is "much better". Build/lint and the full device suite passed. Preserve this implementation when introducing the brush engine. That feedback confirms a substantial improvement, not exact pixel-for-pixel hardware/bitmap equivalence or a measured latency guarantee.

## Why the preview now matches better

Previously, the app computed final widths using its linear pressure curve plus tilt modifier, while the live FOUNTAIN renderer applied BOOX's own pressure algorithm. The first live fix sent only a tilt-adjusted maximum width, so the two pressure responses could still differ. Working raw callbacks and width-update counts ruled out missing tilt as the only cause.

The accepted implementation gives the app ownership of width for both paths:

```kotlin
val documentWidth = brush.width(sample) // normalized pressure plus replaceable tilt response
val hardwareWidth = documentWidth * sessionTransform.scale
// LivePreviewWidth schedules this on the UI thread:
touchHelper.setStrokeWidth(hardwareWidth)
// resumePreview configures this after opening/enabling the renderer:
touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
```

PENCIL is deliberate: it supplies constant-width native rendering for each width we send. BOOX still draws the fast live ink; the app supplies the changing width. FOUNTAIN would introduce a separate pressure response after our calculation. The same `PressureBrush.width(sample)` drives committed bitmap geometry and Android live geometry. Keep the injected width function in `LivePreviewWidth`; do not duplicate a pressure or tilt formula in the adapter.

For example, pressure 0.5 gives 6.5 document pixels. At 40 degrees the initial tilt modifier is 1.75, giving 11.375 document pixels. At zoom 1.5 the native preview receives 17.0625 screen pixels. The saved stroke keeps 11.375 document pixels and reaches the same scale when displayed. Zoom is applied once to preview width, never baked into saved pixels.

## Extension points

- `PenSample` retains pressure plus optional `tiltRadians`, `viewOrientationRadians`, `rawTiltX` and `rawTiltY`. Existing sample construction defaults these to missing; old pressure-only checks/inputs retain their behavior.
- `PenTilt` adapts platform data into inclination: zero is upright, PI/2 is flat. Android uses AXIS_TILT plus historical axis samples only when the input device advertises that axis. Its orientation is retained relative to the view/screen, not converted into document brush direction. BOOX raw points carry signed tiltX/tiltY in degrees; inclination is acos(cos(x) * cos(y)). Invalid axes return missing. Both raw BOOX axes are retained for diagnostics and future direction-aware tips.
- `StrokeWidthModifier` is the replaceable dynamics hook: stable ID, maximum multiplier and per-sample multiplier. It has no Android/BOOX dependency. `NoWidthModifier` restores pressure-only widths. `TiltWidthModifier` is the initial response; a future brush engine can supply its own modifier or replace this boundary without altering capture/logging.
- `PressureBrush.width(pressure)` remains the 1-12 document-pixel pressure curve. `width(sample)` applies the modifier. The first tilt curve has a 15-degree upright dead zone, smoothstep growth, and maximum 2.5x at 65 degrees (up to 30 document pixels). Missing/invalid tilt adds no widening. This is a product starting point, not a hardware calibration standard.
- `maxRenderedWidth` includes the modifier's declared maximum. `StrokeEdit.affectedRegions` uses it for conservative before/after tile capture. Any future tip/spacing/size dynamics must conservatively bound the full rendered footprint; otherwise undo can leave pixels outside captured tiles.

Android live and committed geometry share the same brush. BOOX commits use that brush too. Its fast live hardware preview now receives the full app-computed pressure-plus-tilt width during raw begin/move callbacks through `LivePreviewWidth`. The adapter uses PENCIL constant-width mode to avoid applying a second firmware pressure curve. Do not claim exact firmware/bitmap geometry equivalence without real pen testing. Keep style and width configuration after SDK open/resume as described in FEATURES.md.

View transforms map position once and preserve inclination, which is independent of document zoom/rotation. Orientation is explicitly view-relative for now; a future directional tip must deliberately convert its angle into document space. Do not silently treat raw BOOX axis direction as already matching Android screen orientation.

## Device evidence and diagnostics

Go103's Wacom input device advertises ABS_TILT_X and ABS_TILT_Y, both -63 to 63. The pinned BOOX SDK exposes TouchPoint.tiltX/tiltY; its Android-to-axis conversion provides a cross-check for the inverse conversion. Capability and synthetic validation do not prove that every real raw callback supplies changing values. Draw upright and tilted strokes at similar pressure and inspect the status/logs before declaring physical tilt verified. Recent schema-5 BOOX strokes did contain changing inclination, roughly 21-69 degrees across the inspected examples, confirming real callback data rather than capability alone. Constant zero BOOX values can mean unavailable SDK data; there is no per-point capability flag in this adapter.

Stroke log schema 5 appends fields to each point:

`[documentX, documentY, normalizedPressure, timestamp, rawPressure, tiltRadiansOrNull, viewOrientationRadiansOrNull, rawTiltXOrNull, rawTiltYOrNull]`

Brush metadata records modifier ID, maximum multiplier and maximum rendered width. Status and BooxProbe logs report inclination ranges and raw BOOX axis ranges. Schema 4 and earlier have no tilt; future replay must default those to missing rather than fabricate measured upright data. Persistence still saves raster artwork, so checkpoints need no new brush-format migration.

`TiltChecks` covers missing/invalid input, signed-axis magnitude, round-trip against the SDK conversion, replaceable width response, monotonic/bounded widening, pressure interaction, rotation-invariant inclination, historical Android samples, and widened strokes crossing a history tile boundary through undo/redo. Synthetic events use a tilt-capable InputDevice when present and isolated document storage.

## Primary references

- [Android MotionEvent axis semantics](https://developer.android.com/reference/android/view/MotionEvent#AXIS_TILT)
- [Android advanced stylus input](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- Pinned local SDK bytecode: onyxsdk-base 1.8.5.5 TouchPoint; onyxsdk-baselite 1.1.1.3 TouchPoint and Companion.computeAndroidTiltXY. Artifact versions remain controlled by the project's pinned pen SDK dependency.

Continue the brush engine here rather than accumulating separate platform-specific rendering algorithms. Future work includes brush definitions/selection and settings, spacing/tips, color, directional tilt/shading, reproducible per-stroke settings, and a deliberate BOOX preview capability policy.

## Live BOOX width bridge

Current policy (2026-10-23): `BooxPenAdapter` creates a `LivePreviewWidth` per raw session with an injected width function: `PressureBrush.width(sample) * capturedZoom`. This is the full pressure-plus-tilt curve, shared with bitmap rendering. Firmware style is PENCIL (constant width), deliberately avoiding the separate FOUNTAIN pressure algorithm. This supersedes the earlier tilt-only bridge, which multiplied base width and let fountain calculate pressure independently.

The change follows physical feedback that live and committed strokes still differed. Capture `captures/20261023-102506` showed 1410 move callbacks and 46 live width updates for one stroke, with tilt 26-67 degrees; all four inspected strokes had real changing tilt/pressure and computed final widths roughly 2-24px. Delivery was active, so missing tilt alone did not explain the mismatch. A final screenshot cannot establish pre-pen-up geometry, and exact pressure-response blame remains an inference rather than a measured calibration.

Raw begin starts a stroke epoch; move callbacks update its latest desired width. The UI consumer only calls `TouchHelper.setStrokeWidth`; it does not reopen input or redraw artwork. The pinned SDK forwards this setter to RawInputReader/nativeSetStrokeWidth and EpdController. Begin queues immediately; moves coalesce over 2ms, suppressing changes smaller than max(0.1 screen px, 1%). This tighter cadence matters for pressure, which can change faster than inclination.

Point-list arrival/end and adapter close cancel pending work. Epoch guards reject removed-but-already-queued tasks; adapter session/input checks reject stale updates. After bitmap handoff, resumePreview restores base width and PENCIL; the next begin always forces its initial computed width. The historical fixed-width PENCIL bug is not a reason to restore FOUNTAIN here: PENCIL was fixed-width before the app supplied pressure widths. Do not combine full app width with fountain and accidentally apply pressure twice.

`LivePreviewWidthChecks` uses a virtual scheduler to cover coalescing, latest sample, jitter, zoom scaling, pressure-only and combined pressure/tilt equality with the bitmap brush, forced stroke starts and late callbacks across pen-up/new strokes. Logs emit `Live width preview moves=... widthUpdates=... sentWidthPx=(min,max)` per handoff. These are sent widths, not measured physical line widths. The user reported this revision is much better during real pen testing. Exact cap/smoothing geometry, a systematic latency/ghosting matrix and pixel-for-pixel equivalence remain unmeasured. Layer alpha/opacity masking remains a pen-up composite behavior for hardware ink.

## Maintenance and regression recipe

1. Keep pressure normalization shared (BOOX firmware maximum 4095 on this tablet; Android device range/fallback). Do not treat raw pressure as already normalized.
2. Preserve raw/current/historical sample capture separately from brush dynamics. Future brush settings should feed one width calculation into both bitmap geometry and the preview bridge. Keep the declared maximum footprint correct for tile history.
3. Capture zoom with the raw session; width updates use screen pixels. View/layer changes close that session before installing a new transform. Do not query a moving viewport from an old stroke callback.
4. Keep begin immediate and force its first width. Coalesce moves over 2ms and send the latest sample; this is a scheduling interval, not a latency promise. Suppress subpixel jitter only in preview, never in the saved geometry.
5. Keep all SDK width setters on the scheduled UI consumer. Cancel queued tasks at point-list handoff, raw end and adapter close, with both stroke epochs and adapter generation/input checks. No close/open, style reset, full canvas draw or screen cleanup refresh per sample.
6. After each bitmap handoff, re-enable raw drawing and restore color, base width and PENCIL. Every subsequent begin must override that base, even if its desired width equals the previous stroke's width.
7. Run `LivePreviewWidthChecks`, `TiltChecks` and the full device suite for coupled input/render changes, plus build/lint. Tests exercise shared pressure/tilt widths, zoom, historical axes, widest-stroke undo bounds, queue coalescing/jitter and stale callbacks. Use isolated test artwork/storage.
8. Physically compare slow pressure ramps with the pen upright, then tilt changes at similar pressure, plus fast curves/dots, different zooms/angles, adjacent strokes and Home/return. Check that width changes before lifting and does not jump substantially at pen-up. Inspect `Hardware preview: app-width pencil` and `Live width preview` counts/ranges. A screenshot after lifting only shows committed pixels.

Known remaining differences: native cap/interpolation/smoothing geometry can differ from our circle/segment brush; initial/last points can be affected by native callback timing; layer opacity/alpha masks and rotated-paper polygon clipping are still applied precisely only in the final composite. Do not remove fast hardware feedback to hide these differences without an explicit product decision.
