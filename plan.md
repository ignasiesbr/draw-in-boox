# BOOX Art development plan

Prepared and updated 2026-10-22 from the working Go 10.3 probe. Sections 1 and 2 are implemented as the first delivery milestone; later sections remain planned. Detailed UI layouts, icons, placement, and visual design will be reviewed later.

## Milestone status

- **Delivered:** fixed-size single-layer document, canvas presets/custom dimensions, portrait/landscape swap, memory limits, atomic document checkpoint, and full-document PNG export.
- **Delivered:** +/−, Fit, and 100% zoom, document/view coordinate mapping, zoom-scaled hardware preview width, and paper-region clipping. Zoom is capped at 400%; requests during a pen stroke are refused until the pen is lifted. Two-finger pan, deferred twist rotation and seven-level deferred pinch zoom are implemented; physical pinch validation is pending.
- **Verified:** build/lint; device tests for presets/exports, invalid dimensions, migration, transforms, view resizing, persistence, existing pressure/input behavior, and selector validation. User tested button zoom and reported pen alignment works well.
- **Next:** physically validate section 3 navigation, then continue the pen system. Exact hardware/bitmap brush-shape matching and a systematic orientation/edge/ghosting test matrix remain useful regression checks. Recorded-stroke replay and detailed render timing remain follow-up work, not delivered commands.
- **View rotation addition:** arbitrary-angle viewport rotation with ±15° steps, an exact/fractional angle control, and reset is implemented. Pixels/exports keep their document orientation. Transform, clipping-mask, pen-input, persistence, and unchanged-export tests pass; the user confirmed live BOOX preview and stroke retention through zoom, accepting temporary preview spill across angled paper edges. The baseline before this addition is Git tag `baseline-before-view-rotation` (`8d85b0f`). Deferred two-finger twist rotation is implemented; see section 3.

## Original starting point before this milestone

- Native Kotlin, original BOOX Go 10.3, Android 12 / API 32, firmware 4.2. Development and USB ADB run directly on Windows.
- BOOX hardware pen input and pressure-sensitive live preview work and have been physically tested. Android pen mode works as a comparison/fallback, with more noticeable delay.
- Pressure is normalized to 0–1; the current bitmap brush varies line width from 1 to 12 pixels. The firmware reports a raw pressure maximum of 4095.
- Canvas drawing accepts pen input only. Fingers operate the controls. Palm behavior is acceptable in current testing; navigation gestures will require another palm test.
- One bitmap currently follows the drawing surface's size. Resizing can crop artwork. PNG export and atomic pause-time checkpoints work, but there is no editable document format.
- Build/lint and device bitmap/input tests pass. Capture includes screenshots, stroke samples, logs, and device context. Stroke replay and detailed render timing are still planned.

Operational instructions are in [docs/PROBE.md](docs/PROBE.md); device details and previous fixes are in [docs/HANDOFF.md](docs/HANDOFF.md).

## 1. Separate the document from the screen

**Purpose:** establish the foundation for zoom and layers, and remove artwork cropping caused by screen changes.

Work:

- Introduce a document model with fixed pixel dimensions and an initial single raster layer. The view displays the document; it does not determine the artwork's dimensions after creation.
- Add canvas-size selection when creating a document, using the starting presets below and custom pixel dimensions. Keep this separate from viewport zoom and device orientation.
- Preserve the existing drawing when moving from the probe bitmap. Do not silently resize it or discard the current checkpoint.
- Introduce a viewport containing scale and translation, with explicit document-to-view and view-to-document transforms. Keep the view's on-screen origin separate from these transforms.
- Store stroke coordinates and brush sizes in document pixels. Normalize pressure at input, then pass it to the document brush as now.
- Resizing or rotating the view changes the viewport, not the document bitmap. PNG export always uses the full document at its original resolution.
- Keep BOOX integration behind the adapter. Its raw callback coordinates currently relate to the drawing view; convert them explicitly before committing to the document.
- Preserve document dimensions and pixels in the checkpoint. Treat viewport state as separate metadata so reopening cannot change artwork dimensions.

Validation:

- Forward/inverse coordinate transforms agree at the center, edges, and translated positions.
- Rotation and surface recreation preserve document dimensions and artwork, including pixels outside the visible area.
- Exported dimensions and content remain independent of viewport size.
- Existing pressure, pen-only input, lifecycle, and PNG tests still pass.

### Canvas-size selection

Include the behavior in this first milestone; review its detailed UI later. Use a short initial preset list rather than reproducing every paper, device, or social-media format.

| Starting preset | Pixel dimensions | Intended use |
| --- | --- | --- |
| BOOX screen size | 1860 × 2480 | A familiar starting size based on this tablet's native display resolution |
| Square | 2048 × 2048 | General illustration and square compositions |
| A5 at 300 ppi | 1748 × 2480 | Smaller print-oriented artwork |
| A4 at 300 ppi | 2480 × 3508 | Standard print-oriented artwork |
| Landscape 16:9 | 2560 × 1440 | Digital landscape compositions |
| Custom | User-entered width and height | Other artwork dimensions |

These are proposed starting defaults, not an exhaustive list or a final product decision. Pixel dimensions for paper presets are rounded to whole pixels.

Requirements:

- Allow portrait/landscape switching by swapping width and height. A device rotation must never change these document dimensions.
- Use pixels as the document's authoritative raster dimensions. Paper presets include a print-resolution reference; changing print-resolution metadata alone must not resample artwork. The tablet's display density is separate from document print resolution.
- Initially default to BOOX screen size for new drawings; retain the existing bitmap's actual dimensions when migrating the current drawing, including a landscape bitmap.
- Validate positive integer dimensions and an explicit pixel/memory limit before allocating a bitmap. Show an estimated memory cost so a large custom document is an informed choice.
- Budget for more than one bitmap: document pixels, display/compositing buffers, and future layers/history. For reference, one 2048 × 2048 ARGB_8888 bitmap is 16 MiB; one A4 preset bitmap is about 33.2 MiB. Choose the initial maximum using measurements on the Go103 rather than an arbitrary large limit.
- Creating a new document must preserve/save the current drawing or require explicit discard confirmation. Do not reuse Clear as an implicit new-document operation.
- Keep resizing an existing document out of this initial selector. Later resizing must distinguish changing canvas bounds from resampling artwork, with deliberate handling of cropping.

Additional validation: create every preset, swap orientation, reject invalid or oversized custom dimensions, restore the selected document size after reopening, and confirm export dimensions match the selection.

## 2. Basic zoom using toolbar actions

Fresh/private drawings without a saved view and New canvas start at centered
100%; saved views still restore. Drawing mode hides toolbar and layers without
changing zoom/pan; a small top-right Show controls button or Back restores them.

**Purpose:** make zoom useful and validate BOOX compatibility before adding gesture complexity.

Initial actions: zoom in, zoom out, fit document, and 100%. These are functional requirements; their final presentation is deferred. Define 100% as one document pixel per drawing-view pixel.

Work:

- Apply bounded scale changes around the visible center. Keep the same document point under that center when zoom changes.
- Fit the entire document inside the available drawing area. Handle margins and clamp translation so the document cannot become irretrievably lost off-screen.
- Freeze the viewport during a pen stroke. Apply requested zoom changes between strokes, never halfway through an active stroke.
- Scale displayed brush width with zoom while keeping its stored document width unchanged.
- Update the BOOX drawing region and hardware preview configuration after viewport changes. Pen coordinates, hardware preview, and bitmap rendering must agree on position and width.
- Keep the existing SDK initialization and preview ordering: open/enable input first, then apply the pressure-sensitive fountain style. Repeat this after relevant session changes.

**Compatibility gate:** do not assume the hardware preview supports arbitrary viewport transforms correctly. Test coordinate mapping, clipping, and width scaling on this firmware. If matching fails, investigate the adapter/SDK path before adding pinch zoom; do not silently replace the responsive BOOX mode with the slower Android path.

Validation:

- Draw at several zoom levels, including below and above 100%, near the center and every document/view edge.
- Live ink stays under the nib and does not jump when committed after pen-up.
- The same document brush scales visually with zoom; exports do not change size or stroke placement.
- Fit, 100%, refresh, and repeated zoom changes preserve artwork and pressure response.

**First delivery milestone:** fixed-size document plus button zoom, running on the actual tablet.

## 3. Finger navigation: pinch and pan

Rotation gesture now defaults off, with a saved Settings > Canvas gestures choice
to enable it. Two icons at the right end of the toolbar rotate left/right by 15
degrees without requiring a twist. Disabled twist cannot claim pinch/pan input.

Live pinch zoom (`live-zoom-v1`) repaints the whole visible canvas in 25 percentage-point steps from 75% through 400%. Button zoom shares those steps; Fit remains independent. The upright badge shows the percentage. Immutable worker snapshots and latest-view coalescing bound pending redraws; release publishes the final view, cancellation restores the view at pinch selection. Rotation still applies on release. Validation is recorded in PERFORMANCE.md and HANDOFF.md; physical pinch feel and e-ink ghosting remain to validate.

Twist selection includes upright snapping: catch within +/-4 degrees of 0, release beyond +/-7 degrees, with the badge showing the exact committed angle. Other snap angles and preferences remain future UX choices.

**Panning implemented, physical validation pending:** two-finger midpoint translation with movement threshold, stable pointer IDs, cancellation/focus handling, pen priority, and BOOX suspension/reconfiguration once per navigation session. Single fingers do not pan or paint. Panning works at any zoom and angle, including fitted paper; Fit recenters. No new dependency. Deferred two-finger twist rotation now shares the CanvasGestures lifecycle: select the angle on a small rotating badge, apply once on release, discard on cancellation. The user confirmed selection works; the badge now rotates as an orientation preview. Live pinch zoom is implemented in 25-point steps from 75% to 400%; physical validation is pending.

**Purpose:** add natural navigation on top of the already-tested viewport.

Work:

- Start with two-finger pinch and two-finger pan. Single-finger movement does not draw or pan in the initial version, reducing accidental movement from a resting hand.
- Keep the document point under the gesture midpoint stable while scaling. Reuse the same viewport operations as button zoom.
- Define explicit idle, pen-drawing, and navigation states. While a pen stroke is active, ignore finger navigation. A pen arriving during navigation should end navigation before beginning a stroke with a stable transform.
- Track pointer IDs across pointer additions/removals, and handle cancellation, focus loss, and sleep/wake without leaving a gesture active.
- Suspend hardware ink during navigation and reconfigure it for the final viewport before drawing resumes. Avoid unnecessary full refreshes during every gesture update; tune based on physical e-ink behavior.
- Continue accepting finger taps on the UI. Fingers on the canvas may navigate through the allowed gesture, but never create paint marks.

Validation:

- Pinch/pan, lift one finger, resume drawing, and switch repeatedly between pen and navigation.
- A resting palm does not move the canvas during pen contact or introduce stray marks.
- Gesture midpoint anchoring, bounds, and button zoom remain consistent.
- Check physical responsiveness and ghosting. Screenshots alone cannot validate these.

Gesture thresholds, optional single-finger pan, and navigation preferences can be decided after testing the initial behavior.

## 4. Small, extensible pen system

**Purpose:** turn the working pressure brush into configurable tools without losing the reliable pen path.

Initial scope:

- A round pressure-sensitive pen, an optional constant-width pen using the same engine, and a round eraser.
- Brush size, pressure on/off, and a simple pressure response setting. Preserve the current linear response as the baseline.
- A small number of saved presets after the core settings work. Defer elaborate brush editors, textures, and large brush libraries.

Work:

- Introduce immutable brush settings and stable tool identifiers, separate from toolbar widgets and SDK classes.
- Snapshot settings at stroke start so changing a control cannot reinterpret a stroke already in progress.
- Keep normalized pressure in the shared engine and raw pressure/range provenance in diagnostics. Equivalent normalized pressure should use the same bitmap curve in both input modes.
- Map supported tools to hardware preview styles. Document limitations where firmware preview cannot match a custom brush exactly.
- Implement erasing as transparency removal from the active layer, not painting white. Keep document background separate so this works when layers arrive.
- Route UI eraser selection and physical eraser/button events through the same tool model where supported. Decide exact physical button behavior later.

Validation:

- Light/firm strokes, dots, curves, constant-width mode, and tool changes work at multiple zoom levels.
- Pressure affects live BOOX ink as well as the committed bitmap for supported styles.
- Erasing reveals transparency/background correctly and never touches another layer.
- Saved brush settings survive restart; old strokes and exported artwork do not change when settings change.

## 5. Layers, undo/redo, and editable persistence

The layers panel now wraps its content height over a full-workspace canvas. Long
lists scroll above the toolbar; collapsed state uses only a small expand control.
Native input excludes actual overlay rectangles, leaving canvas below the panel.

**Layers implemented:** collapsible panel (right by default, configurable) with stable-ID selection, add/delete, lock/visibility, duplicate, merge down and up/down reordering. Memory-based layer cap, cached dirty-region composition, layer-targeted raster history and undoable structural commands are in place. Internal checkpoint version 2 preserves layers and reads prior checkpoints; portable save/open is implemented; see [the format contract](docs/SAVE_FORMAT.md). Automated layer/history/input/export/migration and real panel layout tests pass. Physical pen/layer UX checks remain. See [docs/LAYERS.md](docs/LAYERS.md).

**Initial history implemented:** strokes and Clear are `DocumentEdit` actions. The raster history stores compressed before/after 128px tile changes, up to 10 actions and a 32MiB/heap-based budget. Undo/Redo buttons plus two-finger tap Undo / double two-finger tap Redo are implemented; physical gesture validation is pending. Single-tap dispatch waits briefly to disambiguate double-tap redo. New effective edits invalidate redo; view changes do not enter history. New canvas/restart clears the session stack. Automated coverage includes generic fill/cut-style raster edits, overlap/alpha, Clear, branching, eviction, timing/cancellation and current pen/navigation regression checks. Eraser tools, layer renaming/opacity and durable public save/open remain future work; history persistence across restarts is not implemented.

**Purpose:** make artwork editable across tools, sessions, and multiple raster layers. Build these together because history must identify which document and layer each action affects.

Deliver incrementally:

1. Establish bounded stroke undo/redo on the initial layer, including erasing.
2. Add multiple transparent raster layers with selection, add/delete, rename, visibility, ordering, and opacity.
3. Save and reopen an editable document containing dimensions, layer pixels, metadata, and ordering. Keep flattened PNG export as a separate operation.

Work:

- Give documents and layers stable IDs. Painting and erasing target the selected layer; use the same viewport for every layer.
- Composite visible layers over a separate background. Export the document composite, not a screenshot of the viewport.
- Capture before/after data for changed regions rather than a full-document copy for every stroke. A replay of brush commands alone must not be the only way to recover prior pixels.
- Make layer operations undoable and invalidate redo consistently after a new edit. Prevent painting into a hidden or deleted layer with a clear, deliberate rule.
- Set a memory budget for layers and history before expanding limits. Start with bitmaps; add tiles when measured memory or update costs justify them.
- Save editable documents atomically with format versioning and recovery behavior. Pause-time checkpointing is not enough for durable document saving. Persisting the undo stack across app restarts is optional for the first version.

Validation:

- Overlapping layers, visibility, opacity, reordering, and transparent erasing produce the correct composite.
- Undo/redo restores pixels and layer state accurately across tool changes and zoom changes.
- Save/reopen preserves dimensions and layer data. Flattened PNG matches the visible document composite.
- Repeated strokes/layer operations stay within a measured memory budget. History eviction does not corrupt the current document.

Layer-panel design, document browsing, and advanced blend modes are deferred.

## 6. Customizable toolbar

Current toolbar follow-up (2026-10-23): Ink/Tilt/Marker/Eraser are implemented.
Tap the selected tool again for settings; separate brush action icons are removed.
Round Eraser supports 1–128px, selected-layer transparency and undo/redo. Physical
eraser-end/button routing remains deferred. Wide/gray Marker app-preview lag is a
documented current limitation; see docs/BRUSHES.md and docs/PERFORMANCE.md.

**Purpose:** let the user adapt frequently used actions after the underlying tools and commands are established.

Prepare early by giving actions stable IDs and separating command handling from `MainActivity` button creation. Implement customization after the initial zoom, pen, and layer actions exist.

Initial scope:

- Reorder actions, show/hide optional actions, and restore defaults.
- Save toolbar preferences independently of document data.
- Allow brush presets to be referenced by stable IDs once presets exist.

Work and validation:

- Ensure customization never removes access to its own settings or prevents restoring a usable toolbar.
- Handle new, removed, or renamed actions when loading older preferences.
- Keep command behavior independent of placement. Moving an action must not affect the selected tool, active layer, viewport, or document history.
- When toolbar dimensions change, update the viewport and BOOX drawing region without resizing the document or shifting pen coordinates unexpectedly.
- Retest finger controls, palm interactions, orientation changes, and pen alignment after layout changes.

Toolbar position, orientation, compactness, grouping, customization interaction, and icon design will be reviewed later.

## Work carried through the milestones

- Add transform fixtures and repeatable stroke replay alongside the document/zoom work. Extend captures with viewport, document, brush, and active-layer context as those concepts appear. Do not claim replay tests the real digitizer or hardware preview.
- Add useful timings for stroke processing and bitmap commit; use physical pen tests for perceived latency and ghosting.
- Run `dev.ps1 build` and relevant device tests for each meaningful engine/input change. Physically check hardware preview whenever mapping, brush style, or lifecycle behavior changes.
- Keep [README.md](README.md), [docs/PROBE.md](docs/PROBE.md), and [docs/HANDOFF.md](docs/HANDOFF.md) current as milestones are completed. Mark implementation and verification separately.
- Review the UI in depth separately. The plan defines behavior, data ownership, and validation; it does not settle the final interface.

Layer UI follow-up complete: right-side default with persistent left/right settings, shared monochrome action/toggle icons, black active borders and undoable long-press rename. See [e-ink UI conventions](docs/EINK_UI.md). Integrated build/lint and device tests pass; physical UX review follows.

Per-layer settings added: thumbnail long-press opens an opacity draft slider (0-100%), Apply/Cancel, undo/redo and persistence. Composition, duplicate, merge and PNG respect opacity. Full device checks and build/lint pass; BOOX live pen ink remains full black until pen-up.

Alpha lock delivered in layer settings: preserve pixel alpha during strokes, combined opacity/alpha-lock Apply/Cancel, undo/redo and persistence. Clear is protected; BOOX preview masking is applied at pen-up.

Brush preparation: tilt capture and replaceable width dynamics implemented, with wider tilted strokes and pressure retained. Build/lint and full device tests pass; physical tilt response pending. See docs/BRUSHES.md before the full brush-engine milestone.
# Brush milestone update — 2026-10-23

Initial preset system and dedicated drawing toolbar implemented: Ink, Tilt (accepted default), Monoline, persisted per-preset size/dynamics and Apply/Cancel settings. Both input paths capture brush settings for the stroke; the BOOX preview keeps app-width PENCIL and the existing pressure/tilt calculation. Size remains 1–12 document pixels with optional 2.5× tilt widening. See docs/BRUSHES.md and docs/BRUSH_ENGINE_PLAN.md. This supersedes older generic native-style guidance below: do not combine app-computed pressure width with FOUNTAIN. Earlier tilt-only verification status is also superseded by the physically accepted shared-width bridge documented in BRUSHES.md. Layer rename/opacity/alpha lock are implemented despite older milestone text below. Advanced brushes, erasing, preset import/export and configurable toolbar placement remain deferred; physical testing of the new preset UI and live/final appearance is pending.

# Verified brush increment (2026-10-23): build/lint pass (0 lint errors), full device instrumentation passes including baseline pixel equality, preset persistence, captured commits, history and dialog validation. The new presets still require physical live/final comparison.
