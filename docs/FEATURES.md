# Feature maintenance guide

Alpha-lock native feedback: user explicitly accepts preview crossing the mask
until reconciliation. Supported black paint remains native with alpha lock on;
canonical SRC_ATOP pixels/history preserve all source alpha. Real SDK open is
checked under alpha lock and AlphaLockChecks verifies raw-source commit/undo/redo.
Gray/wide native shader restrictions are independent and still apply.

History controls (`uiRevision=history-controls-v1`): Undo/Redo are accessible
outline-arrow actions on the brush toolbar, replacing the top-row text buttons.
Their enabled state follows history, independently of gesture preferences.
Settings has separate Undo two-finger tap and Redo two-finger double-tap checkboxes,
both enabled by default. AppSettingsStore saves `undo_gesture_enabled` and
`redo_gesture_enabled` alongside layer-panel position; artwork and ink settings are
untouched. Applying gesture changes cancels queued tap recognition; gesture-only
changes do not rearrange the panel or explicitly reconfigure native input.
The recognizer still distinguishes single from double taps when only one shortcut
is enabled: disabled redo must never become undo, and disabling undo must preserve
redo. With both disabled, navigation receives all touch events. HistoryTapChecks
covers all combinations, delayed suppression and navigation; SettingsChecks covers
defaults, persistence, dialog Apply/Cancel and manual history while gestures are off.

Native eraser trial after baseline commit `388add9`: `native-eraser-v1` permits
white PENCIL feedback only at <=30 document/screen px on exactly one visible,
unlocked, 100%-opacity layer, alpha lock off. Canonical edits remain CLEAR.
`NativePreviewPolicy.color` is context-aware for this case; callers must pass the
document and zoom. Do not broaden black/gray/wide policy or omit fallback on layer/
opacity/alpha-lock/zoom changes. Physical trial is pending; see NATIVE_PREVIEW_SAFETY.md.

Current brush UI: tap a tool to select; tap the selected tool to edit size/dynamics/
shade. No separate right-side brush actions. Eraser (`eraser` ID) is a fixed round
1–128px CLEAR brush with its own saved size and Erase history label. It respects
layer locks/alpha lock, always uses app preview, and isolates pending erase geometry
from paper/lower layers. See BRUSHES.md, EraserChecks and AsyncSurfaceChecks. Preserve
the native preview safety gate and accepted black-ink performance architecture.

Native safety policy now gates TouchHelper creation: gray/wide brushes use app live
preview; only black brushes with maxRenderedWidth <=30px enter the native path.
Native setters run paused, enable last; constant brushes receive no begin/move width
setter. Software live frames include pending strokes, coalesce snapshots in flight
and use the worker on both moves and pen-up. See NATIVE_PREVIEW_SAFETY.md; preserving
the original fast black input path does not mean re-enabling the failed gray shader.

Marker/shade increment: Marker keeps stored ID `monoline` and widens its size range to 128px. `round-marker` draws one round-capped Path for constant-width Marker; pressure/tilt brushes retain continuous-round geometry. `InkShade.kt` defines opaque tones; `InkShadePicker.kt` owns shared draft swatches/dialogs. Shade is global in app preferences (v2), size/dynamics per-preset. Captured brushes carry color through bitmap draw, native color setup and schema-7 diagnostics. Never implement gray as reduced alpha or add per-sample color setters. See BRUSHES.md and MarkerChecks; the accepted asynchronous pipeline remains unchanged.

Physical performance acceptance: on 2026-10-23 the user reported an "incredible difference" with `async-handoff-v1`. Preserve the combined architecture described below and the regression rules in AGENTS.md. Future changes need ordinary-fast and very-fast pen testing; the prior 150ms policy behaved differently at those two cadences.

Current BOOX presentation (`async-handoff-v1`): final reconciliation waits for a 450ms gap, then prepares immutable pixels on UI and uses `AsyncSurfacePresentation` for SurfaceHolder lock/draw/post on a worker. The worker owns its screen cache and receives no live document/layer bitmaps. Epochs, a bounded mailbox and unioned unacknowledged bounds preserve lifecycle/order. Native reset is postponed again if writing resumes before paint completion. SDK View/native setters remain on UI. Synchronous UI frames invalidate worker epochs and share a canvas gate; see PERFORMANCE.md and AsyncSurfaceChecks for limits and pixel comparison tolerance. This supersedes older 150ms/synchronous handoff wording below.

Historical 150ms policy: combined screen handoffs improved very fast writing, but the user still observed delays at ordinary fast cadence. It is superseded by the accepted asynchronous/450ms pipeline above. PERFORMANCE.md retains the measurements and rationale.

Latest handoff optimization: upright commits use `StrokeDirtyBounds` and `lockCanvas(dirty)`, honoring Android's expanded returned region; rotated/navigation/history frames stay full. `drawFrame` is shared and pixel-tested. On bitmap handoff `BooxPenAdapter` toggles only rendering, retaining the digitizer reader, then reapplies black/base width/PENCIL. Full open/close remains unchanged. Initial width is inline on UI because SFTouchRender already posts callbacks there. See PERFORMANCE.md; these details supersede the older per-stroke full-input-toggle wording below.

Performance maintenance: history reuses transaction-local compression buffers. `StrokeDiagnostics` bounds background JSON/file work; capture UI metadata before enqueueing and never pass mutable document pixels to the worker. `BooxPerf` stage summaries and the isolated device benchmark are documented in PERFORMANCE.md.

Brush toolbar integration: `BrushPreset.kt` owns built-in preset IDs, immutable settings/compilation and versioned preferences; `BrushSettingsDialog.kt` owns draft controls. `MainActivity` dispatches stable `brush.*` action tags through guarded handlers and uses `EinkIconButton` for selection. Preserve per-stroke brush capture in both input paths and adapter contact-to-handoff busy protection. See BRUSHES.md for schema 6 and preview restrictions. The current first engine remains the existing continuous round renderer; toolbar customization and advanced dab brushes are deferred.

Read this when continuing layers, shared UI, settings, or navigation. It documents the working implementation and regression traps, rather than a proposed redesign. Last reviewed 2026-10-23; the user confirmed the layer UI/settings build works very well.

Start with [HANDOFF.md](HANDOFF.md) for current state and accepted limitations, [PROBE.md](PROBE.md) for Windows/ADB commands and diagnostics, and [../plan.md](../plan.md) for feature priorities. [EINK_UI.md](EINK_UI.md) contains UI conventions and research sources. Production Kotlin files below are under `app/src/main/java/art/boox/probe/`; device checks are under `app/src/androidTest/java/art/boox/probe/`.

## Ownership and invariants

| Concern | Owner | Boundary to preserve |
| --- | --- | --- |
| Artwork and layer metadata | `DrawingDocument.kt` | Fixed document pixels; stable layer IDs; UI-thread mutations |
| Editing transactions/history | `EditHistory.kt`, `LayerCommand.kt` | Resolve recorded IDs; compressed patches; no retained live bitmap snapshots |
| Drawing and navigation integration | `RasterSurface.kt` | Input arbitration, render/commit, busy guards, viewport callbacks |
| Coordinate math | `DocumentViewport.kt` | View transform and inverse; navigation never changes artwork |
| Finger recognition | `CanvasGestures.kt`, `HistoryTapGestures.kt` | Pen priority; explicit cancellation; stable pointer IDs |
| Hardware pen and refresh | `BooxPenAdapter.kt`, startup in `ProbeApplication.kt` | Firmware calls stay isolated; generation guards and SDK ordering |
| UI composition and lifecycle | `MainActivity.kt` | Close input before changes; reopen after layout/focus settles |
| Layer controls | `LayerPanel.kt` | Dispatch commands; own no artwork pixels |
| Shared icon UI | `EinkIconButton.kt` | One action/toggle component and monochrome vector glyph family |
| App preferences | `AppSettings.kt`, `SettingsDialog.kt` | Independent of document checkpoints and edit history |
| Deferred navigation badge | `GestureIndicator.kt`, `ZoomLevels.kt` | Small overlay updates; commit chosen transform once on release |

Canvas input is pen-only; fingers operate UI and navigation. Keep raw samples, normalized pressure, view coordinates and document coordinates distinct. Map input through the inverse viewport exactly once. View resizing must never crop/reallocate artwork.

## Layers: data, rendering and history

`DrawingDocument.layers` is bottom-to-top; `LayerPanel` lists it in reverse. Each `RasterLayer` owns a transparent bitmap, stable UUID, name, visible flag, locked flag and integer opacity (0-100%, default 100). `selectedLayerId` identifies the active layer; compatibility aliases `layer` and `layerId` refer to it. Selection is not an edit.

Current semantics:

- Add inserts above the selected layer. Duplicate copies pixels independently and selects an unlocked copy.
- Hidden or locked active layers reject strokes and Clear. Keep finger navigation/history available; do not equate `inputEnabled` with permission to paint. `canDraw`/`canPaint` enforce that distinction.
- Locked layers can be selected, renamed, reordered, hidden/shown and duplicated. Delete requires unlocked and at least one surviving layer.
- Merge down requires an existing lower neighbor and both layers visible/unlocked. It preserves the lower layer's identity/name and the visible composite.
- Rename trims text, rejects blank/control-character names, and permits at most 80 characters (`MAX_LAYER_NAME_LENGTH`). It is metadata-only and undoable, including for hidden/locked layers.

Use `RasterSurface.applyLayerCommand(...)` from UI. It guards drawing/navigation and coordinates the BOOX handoff. Add structural behavior as a `LayerCommand`; implement/validate the document operation centrally. `affectedPixels()` must identify every layer whose pixels can change or disappear. Metadata-only operations leave it empty. Do not mutate flags/bitmaps directly from a widget and bypass history/cache invalidation.

`EditHistory` combines raster and structural entries in one bounded stack: 10 actions, compressed bytes capped at min(32MiB, heap/8). Pixel entries capture lossless before/after 128px tiles and resolve their recorded layer ID on restore. Structural entries keep before/after stack metadata and compressed pixels only for inserted/removed/changed layers. Undo restores the appropriate selection. No-op commands preserve redo; effective new edits discard it. Restart/new canvas resets history; navigation/settings/selection are excluded. Future fill/cut tools should implement `DocumentEdit` and conservatively declare all affected regions.

Composition uses a lazily allocated transparent bitmap cache for multiple layers, invalidated by dirty regions or structure changes. Navigation reuses it. A single visible layer can draw directly. Android pending strokes render at their selected layer position. Thumbnails scale existing source bitmaps without allocating copies; ordinary stroke updates invalidate thumbnails rather than rebuild all controls.

BOOX live ink is firmware-rendered foreground feedback: it can appear above higher layers until pen-up. Final bitmap composition has the correct order. This accepted limitation is separate from the rotated-edge clipping limitation below.

Layer limits are intentional: `LayerLimits.maxLayers(spec)` reserves history, two full-size buffers and app overhead, with at most half the heap for layer pixels and a hard count cap of 64. Verified Go103 limits are 7 at 1860x2480 and 3 at A4. Revisit the whole working-memory budget before adding per-layer caches, masks or previews; do not simply raise the cap.

Internal `DocumentStore` v2 archives contain `document.json` and `layers/<id>.png`, preserving order/names/flags/selection and viewport metadata. Version 1 and legacy PNG migration remain supported. Validate dimensions, IDs, viewport values and memory before exposing loaded state; recycle partial allocations on failure. Public save/open/sharing is deferred by user choice. This internal checkpoint is not that public format.

### Layer opacity and layer settings

Long-press a thumbnail to open `LayerSettingsDialog`. Long-pressing the name still renames. The modal is the extension point for future per-layer settings; it is separate from application preferences. Its explicit black track (thin range, thicker filled portion) avoids an invisible stock SeekBar track on the BOOX theme. The monochrome 0-100% slider updates a percentage label without repainting the artwork while dragging. Apply dispatches one `LayerCommand.Properties` containing a typed `LayerProperties` draft (opacity and alpha lock); Cancel changes nothing. Hidden/locked layers may change opacity, like rename/visibility metadata. Zero opacity does not lock a layer: new strokes still record in its pixels.

Opacity is included in `LayerInfo`/stack history and duplication, but never baked into source pixels by normal editing. `DrawingDocument.drawLayer` applies group opacity in both the single-layer fast path and cached composition. Android pending strokes and existing pixels are grouped before fading, avoiding darker overlap during live drawing. Thumbnails and flattened exports honor opacity too. Merge down is the exception: it composites both layers at their respective opacities into a temporary bitmap, then retains the lower layer at 100%. Allocate before changing originals; Undo restores original pixels and opacity metadata. The 100%/100% merge keeps its direct fast path.

The v2 checkpoint adds an optional integer `opacity` field to each layer; absent fields mean 100 for older checkpoints. Invalid/nonfinite/fractional/out-of-range opacity is rejected before decoding layer pixels. Diagnostics also include opacity. Do not migrate it into app settings or change raster stroke pressure to simulate layer opacity.

BOOX firmware live pen ink still uses the black native preview; the final composite reflects layer opacity at pen-up. Android preview can apply group opacity directly. Preserve fast BOOX feedback rather than assuming the firmware renderer supports arbitrary alpha.

`LayerOpacityChecks` covers alpha/group overlap, cache updates, zero opacity, validation, metadata history, duplicate, merge/undo/redo appearance, PNG and checkpoint round-trips/defaults/corruption. `LayerPanelChecks` exercises actual thumbnail long-press, percentage feedback, draft isolation, Cancel, Apply and undo against isolated artwork.

## Shared icons and layer UI

`EinkIconButton(context, icon, label, toggleable = false)` implements `Checkable`. Its state is model-driven: a click dispatches a command; it does not automatically flip the checked state. After success, render from document state. This avoids a misleading toggle when a busy guard rejects a command.

For an action, supply a `UiIcon`, meaningful label and click listener. For a toggle, set `toggleable = true` and `isChecked` from the model. Checked toggles draw a 2dp black border. Visibility is checked when visible; lock is checked when locked. The opposite states have explicit crossed-out-eye/open-lock glyphs. Up/down are momentary actions and disable at stack boundaries.

Reuse this component for future icon actions/toggles. It provides at least 48dp targets, white background, black vector outlines, disabled glyph treatment, accessibility checked state, content descriptions and tooltips, with no elevation/ripple/state animation. Glyphs live in `UiIcon`/`UiIconDrawable`; no icon library dependency is required. The native-widget lint suppression is deliberate because the Activity uses framework widgets, without AppCompat tinting.

Selected layer rows use `EinkUi.selectionBackground(..., true)` for a solid 2dp black border, not a shadow or faint gray fill. Keep row dimensions stable when selected. The compact top row is Add/Duplicate/Merge/Delete; each layer's lower row is Visibility/Lock/Up/Down. Hide leaves a 52dp expand rail. Expanded panel width is 220dp. Names and thumbnails select; long-pressing a name opens Rename, with accessibility action text. Tooltips explain action icons.

When adding UI, follow [EINK_UI.md](EINK_UI.md). Use discrete local updates, readable black outlines and finger-sized targets. Screenshots cannot establish physical ghosting, latency or hardware-preview quality.

## Settings and panel layout

`AppSettings` is a typed immutable application-preference model. `AppSettingsStore` uses the separate `app_settings` preferences file. `LayerPanelSide` stores stable `left`/`right` values; missing/unknown values fall back to RIGHT. Panel expansion currently remains in the Activity's preferences. Neither setting belongs in artwork or undo history.

`SettingsDialog.show(context, settings, apply)` edits a draft and calls `apply(updated)` only on Apply. Returning false leaves the modal open; Cancel commits nothing. Add future fields through the model/store and labeled dialog sections, preserving unknown-value fallbacks. Avoid creating a separate settings mechanism per feature. Commit listeners are installed immediately after `dialog.show()` so validation is ready without waiting for an asynchronous on-show event.

`MainActivity.arrangeDrawingArea()` moves only `LayerPanel`, preserving the canvas `SurfaceView` attachment, document and expansion state. Changing sides may leave canvas dimensions identical while changing its screen origin. A surface-size callback alone is insufficient. The apply path calls `preparePanelResize()`, updates preferences/layout, then posts `schedulePenConfiguration()` explicitly after layout.

Keep that handshake for future sidebars/toolbars: close raw input before moving the drawing region, let layout settle, reopen using the new origin and clip region. Reject layout changes during a stroke/navigation. Renaming/settings dialogs also trigger focus loss; do not force input open underneath a modal.

## Navigation and the two different previews

There are two independent preview systems: BOOX pen ink follows the digitizer through the firmware, while `GestureIndicator` is an ordinary Android overlay that displays a proposed zoom/angle. Preserve both when changing rendering. Improving the badge must not replace the low-latency pen path.

`DocumentViewport` owns scale, translation and clockwise rotation; positive angles rotate clockwise and normalize to [-180,180). Artwork dimensions/pixels and PNG orientation stay unchanged. Fit computes the rotated page bounds; Fit/100% retain the angle. New canvas resets the view. Toolbar Angle accepts arbitrary finite/fractional angles; gesture rotation chooses whole degrees. Bounds can limit center/anchor preservation.

`CanvasGestures` tracks a stable pair of finger IDs. Pan begins beyond Android touch slop and is live. A deliberate twist/pinch can take over; the first selected mode remains locked until the gesture ends. If both thresholds are crossed in one event, normalized gesture strength resolves intent. Keep this ownership policy when adding gestures rather than making independent recognizers compete.

| Gesture | Activation | Feedback | Commit |
| --- | --- | --- | --- |
| Pan | Two-finger midpoint passes touch slop | Canvas moves live | Already applied; cancelling does not roll back prior pan |
| Rotate | Twist at least 5 degrees, contacts at least 48dp apart | Canvas freezes; whole badge rotates to the proposed angle | Either finger lifts; apply displayed angle once |
| Zoom | Starting span at least 48dp, span change beyond touch slop and 15% ratio threshold | Canvas freezes; upright badge shows level and percentage | Either finger lifts; apply displayed level once |

Rotation uses the gesture-start angle plus accumulated bearing changes, handling wraparound. Upright snap enters within 4 degrees and stays caught until beyond 7 degrees, using the unrounded target to resist jitter. It commits exactly 0 when displayed; toolbar angle entry is unsnapped.

`ZoomLevels` selects the nearest absolute scale in logarithmic space from gesture-start scale times span ratio. Levels 1-7 are 10%, 25%, 50%, 100%, 150%, 250%, 400%. The zoom anchor is the midpoint when deferred zoom first selects a level; it stays fixed for that selection. There is no reset snap. Toolbar zoom/Fit are separate from these gesture levels.

Cancellation/focus loss/extra contacts discard pending zoom/angle; rotation also cancels if contacts become too close. Lift navigation contacts before drawing again. Pen input takes priority. `HistoryTapGestures` observes the same stream before navigation: a valid stationary two-finger tap undoes, two consecutive taps redo. Single undo waits the platform double-tap timeout. Navigation begin cancels tap candidates/timers, and cancellation is shared across lifecycle changes. Preserve this arbitration when extending gestures.

`GestureIndicator` is a sibling overlay above `RasterSurface`, not painted into artwork or the composite cache. Its fixed 280dp square contains the 240x96dp badge at any angle so old corners can be repainted. Angle rotates text and background together; zoom stays upright. Digit redraws coalesce at 33ms; the chosen value updates immediately and is what gets committed, even before the next paint. Per-view DU refresh is enabled while visible and restored on hide, with ordinary Android refresh fallback. Do not enable app-wide fast mode. This is an e-ink interaction choice, not a promise of firmware-ink latency.

## BOOX pen handoff: preserve these details

1. Before viewport, active layer, stack or drawing-region changes, suspend/close hardware input through the existing surface/activity callbacks. Navigation uses its own `navigationStarted` callback so fingers continue navigating while raw pen input is closed.
2. Update the viewport/document/layout and render the final bitmap; dismiss deferred indicators. `ready` schedules pen configuration after the UI update.
3. `schedulePenConfiguration()` uses `postOnAnimation` then `post`, guarded by a configuration generation, so headings/layout update before hardware refresh takes over. Immediate reopen previously left angle/zoom labels stale.
4. `configurePen()` requires a resumed/focused Activity and a valid nonzero surface. Hidden/locked selections keep hardware ink closed while navigation remains enabled. Adapter open captures the current inverse transform and stable layer ID.
5. Adapter callback posts are generation-guarded. Stroke commit verifies the expected layer ID. Keep these guards to prevent old callbacks drawing onto a newly selected layer or with an obsolete transform.
6. After `openRawDrawing()` or every bitmap handoff, `resumePreview()` enables raw drawing and reapplies black color, base width and the current preview style. The current app-width bridge uses PENCIL constant-width rendering and feeds `PressureBrush.width(sample) * zoom` during contact, sharing pressure and tilt with bitmap geometry. Historically PENCIL without those updates caused fixed-width live ink, fixed at the time by FOUNTAIN. Do not restore FOUNTAIN while also feeding full app-computed pressure width; that would apply two pressure responses.

Raw BOOX positions are view-relative. Captured `ViewTransform.map` maps once into document coordinates. Do not add screen origin or inverse zoom again. Screen origin still matters when configuring the firmware input region after layout. Both input modes normalize pressure to 0-1; the app brush uses 1-12 document pixels, with tilt increasing it up to 2.5x; live native preview uses the same width calculation, although cap/smoothing geometry can differ.

The working BOOX clip is the paper's axis-aligned bounding rectangle plus an off-view exclusion sentinel `Rect(-1,-1,0,0)`. Empty exclusion lists do not clear old SDK masks. Large stepped polygon exclusions broke raw callbacks through TouchHelper; direct firmware exclusions restored recording but suppressed the live preview. The rectangle preserves both. Strokes starting outside paper are rejected; final raster clipping is exact. Hardware ink may briefly spill over angled edges until pen-up. The user explicitly accepted this limitation. Do not reintroduce polygon exclusions without testing both actual raw commits and live pen feedback; a screenshot after pen-up proves neither.

## Verification for future changes

Use `./dev.ps1 build` for APK/lint and `./dev.ps1 test` for device instrumentation from Windows PowerShell. `test` leaves the app stopped; reopen with `./dev.ps1 run`. These scripts visit Home before replacing a running APK to allow its pause-time checkpoint, and enable the package after install. Do not uninstall/clear data as a routine development step.

| Changed area | Relevant existing checks |
| --- | --- |
| Layers/composition/history | `LayerChecks`, `HistoryChecks`, full raster instrumentation |
| Rename | `LayerRenameChecks`; isolated real long-press dialog validation/undo in `LayerPanelChecks` |
| Panel layout/settings | `LayerPanelChecks` actual collapse/expand and left/right switch-back with document/width/input checks; `SettingsChecks` persistence/fallback/Apply/Cancel |
| Viewport/input | `DocumentChecks`, bitmap/pressure/pointer checks in `RasterInstrumentation` |
| Navigation/preview selection | `CanvasGestureChecks`, `GestureRotationChecks`, `GestureZoomChecks` |
| Gesture arbitration/history taps | `HistoryTapChecks`, including combined pan/pinch/twist dispatch |

Run relevant checks after changes; run the full device suite for coupled input/layout/render work. The latest integrated build/lint and full suite passed. UI tests restore preferences and use isolated artwork for rename; preserve that isolation.

Physically draw before/after zoom, arbitrary-angle rotation, pan, layer switches, lock/show changes, panel hide/show/side changes, dialogs, Home/return and sleep/wake. Check live pressure before pen-up, correct committed position/order, retained strokes after zoom, and usable controls without ghost borders. Inspect `BooxProbe` logs for real raw begin/end and commits, plus drawing-region dimensions/origin; enabled flags alone do not prove pen input works. Public save/open, exact firmware/bitmap brush matching, exact polygon preview clipping and systematic ghosting/edge measurement remain future work.

## Alpha lock

Layer settings includes a shared checked icon for Alpha lock. The draft toggles locally; Apply commits opacity and alpha lock together as one metadata/history transaction, Cancel discards both. `RasterLayer`/`LayerInfo` retain `alphaLocked`, default false; duplication, stack restore, diagnostics and the optional v2 checkpoint field preserve it. Older checkpoints default false. This differs from full layer lock: alpha lock still permits painting, constrained to existing alpha. Hidden/full-locked layers may edit the metadata but still reject paint.

`PressureBrush.draw(..., alphaLocked)` uses SRC_ATOP and resets its paint transfer mode in finally. This preserves destination alpha exactly, including soft/partially transparent edges, while painting the existing pixels. `StrokeEdit` selects that mode from the target layer; BOOX and Android commits use the same path. Blank alpha-locked layers remain blank and produce no pixel-history action. With today's black pen on black artwork, recoloring may be visually unchanged; the constraint becomes especially useful with future color tools.

Android pending strokes use the same blend mode inside an isolated group in `DrawingDocument.drawLayer`, even at 100% opacity. Without that isolation the white paper or lower layers would incorrectly become the alpha mask. Never draw an alpha-locked pending stroke directly against the full composite. Clear is refused/disabled while alpha lock is on; turn it off before clearing. Explicit structural operations such as merge/delete remain governed by the existing full-lock rules; merge retains the lower layer's alpha-lock flag.

BOOX firmware live feedback cannot use the app's per-pixel alpha mask through the current adapter: it can temporarily appear outside existing ink, then the correct masked composite replaces it on pen-up. Preserve fast hardware preview. Future fill/eraser/color tools must deliberately honor alpha lock rather than assuming the generic history transaction implements tool semantics.

`AlphaLockChecks` verifies per-pixel alpha preservation, recoloring, undo/redo, blank no-op behavior, Clear protection, duplicate/checkpoint state, isolated pending preview and both commit sources using separate test directories. Modal checks exercise draft/Cancel and combined Apply/undo. Full device suite and build/lint pass. One initial test mistakenly used the real app directory and replaced the disposable development drawing with a blank 32x32 checkpoint; fixed to use an isolated ContextWrapper. Preserve test storage isolation.

## Tilt and future brushes

Read [BRUSHES.md](BRUSHES.md) before extending input or brush dynamics. Tilt capture is independent of the replaceable `StrokeWidthModifier`. Android preview/commits and BOOX commits share the response; firmware preview remains separate. Preserve nullable input semantics and conservative history bounds when changing dynamics. Stroke diagnostics are now schema 5 with tilt fields; older schemas remain pressure-only.

Live BOOX width update: `LivePreviewWidth` injects the complete shared pressure/tilt width into native PENCIL rendering via coalesced `setStrokeWidth` calls. The user confirmed much better live/committed matching. See BRUSHES.md before changing the pressure policy, threading, zoom or reset rules.

Current preview policy supersedes the historical fountain-only notes above: use PENCIL plus full app-computed pressure/tilt widths, as documented in BRUSHES.md. Live update cadence is 2ms with subpixel jitter suppression. The user reported much better matching; exact hardware/bitmap shape equality remains unmeasured.
