# Brush input, tilt and live preview

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
