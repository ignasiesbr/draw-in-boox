# Kotlin drawing prototype: working guide

This is a single-document, multi-layer prototype running on the verified Go103 / Android 12 / firmware 4.2 device. Document dimensions, canvas selection, and button zoom are implemented; [../plan.md](../plan.md) tracks later features and milestone status. Detailed UI design is deferred.

## Current hardware preview policy

The current live bridge feeds the shared app-computed pressure-plus-tilt width into BOOX PENCIL constant-width rendering. This supersedes the earlier fountain-only initialization guidance: the app now drives pressure width explicitly, so adding the firmware fountain curve would give a different response. Updates coalesce over 2ms, with small-jitter suppression and stroke/session cancellation. The user physically retested this implementation and reported it is much better. See BRUSHES.md for the shared-width calculation, maintenance rules and remaining geometry/latency limits.

## Controls

- **Layers (right by default; configurable in Settings):** top layer appears first. Add creates a transparent layer above the selection. Tap a name/thumbnail to select. Show toggles visibility; Lock prevents drawing, Clear, deletion and merging on that layer. Locked layers can still move, hide/show or be duplicated. Duplicate creates independent pixels and selects an unlocked copy. Merge down requires both adjacent layers visible and unlocked; it keeps the lower layer's identity/name. Up/Down change order. Delete keeps at least one layer. These mutations are undoable. Hide collapses to a layers icon; tap the icon to reopen. Panel state is a preference, excluded from document history. Selection/panel changes are refused during strokes/navigation, and BOOX input is reconfigured after layout settles.

Layer count follows a memory budget, capped at 64 for tiny canvases: at most half the heap for live layer pixels, while reserving two full-size raster buffers, min(32MiB, heap/8) history and 40MiB app overhead. The verified 256MiB Go103 heap permits 7 layers for 1860x2480 and 3 for A4. The panel shows the current count/limit and disables allocations at the limit. No thumbnail bitmaps are allocated. See [layer architecture/validation plan](LAYERS.md) and installed panel screenshot (screenshot omitted).

Rendering uses a lazily allocated transparent composite cache and a dirty region built from changed tiles. Pan/zoom/rotation reuse it; edits recompose affected regions only. Android pending strokes draw within the selected layer's position. BOOX live ink is a temporary foreground overlay and can appear above higher-layer content while drawing; pen-up uses the correct composite. Locked/hidden selections leave hardware ink closed while finger navigation/history remain enabled. Layer-specific callback IDs and session generations reject stale strokes after selection changes.

- **Undo / Redo:** up to 10 editing actions in the current app session. Each stroke, Clear and layer mutation is one action; layer selection and panel visibility are excluded. A two-finger tap undoes; two consecutive two-finger taps redo. Single tap waits the Android double-tap interval (about 300ms) so a double tap never performs undo first. Place both fingers within 250ms and lift within 450ms without moving beyond touch slop. Extra contacts, cancellation, pen input, focus loss or starting navigation cancel the tap candidate; moving two fingers navigates; only a stationary two-finger tap enters history recognition. Buttons provide immediate undo/redo. View navigation is excluded. A new effective edit discards redo; a new canvas or process restart starts fresh history. Backgrounding/checkpointing preserves the current artwork, but the undo stack is not saved to disk.

- **BOOX pen / Android pen:** tap to switch input paths. The label reports the current mode. BOOX explicitly uses the raw-digitizer/SF renderer (no SDK finger renderer), with hardware preview driven by app-computed pressure/tilt width and completed strokes committed to a bitmap. Android mode accepts only stylus pointers, including historical samples, and ignores finger, mouse, and unknown tools. SDK setup failures fall back to Android mode and appear in the status and logcat. Finger taps on buttons remain enabled.
- **Refresh:** redraw the bitmap and request a BOOX GC screen refresh.
- **Export PNG:** save the full document, flattened over white, to `Pictures/BOOX Art` through MediaStore. Zoom and the visible screen area do not affect export dimensions. Broad storage access is not required.
- **Clear:** confirmation before erasing the current layer. It keeps the document dimensions.
- **New canvas:** BOOX 1860×2480, square 2048×2048, A5 1748×2480, A4 2480×3508, landscape 2560×1440, or custom pixel dimensions. Swap width/height for orientation. Replacement requires explicit discard or PNG export first.
- **− / +:** center-based zoom steps. **Fit** shows the full document; **100%** maps one document pixel to one view pixel. Zoom is capped at 400%, including Fit for very small canvases. Zoom requests during pen contact are refused until the pen is lifted. Drag with two fingers to pan. A single finger does not pan or paint. Movement begins after the Android touch threshold; lifting either finger ends the gesture. Extra contacts or cancellation stop navigation until a fresh touch sequence. Panning retains zoom and rotation, including when the paper fits on screen; Fit recenters. Pinch selects one of seven deferred zoom levels; see below.
- **Rotate −15° / Rotate +15°:** turn the drawing view in steps. **Angle…** accepts any finite angle, including fractional degrees. **Reset angle** returns to 0°. Rotation is clockwise for positive angles, normalized to −180° through less than 180°. It preserves the view center/zoom where bounds allow; when Fit is active, it recomputes the fit for the angled page. Fit/100% retain the selected angle. New canvas resets it. Changing angle during a stroke is refused. Twist two fingers to select rotation: after a 5-degree threshold with contacts at least 48dp apart, a whole-degree angle badge appears while the paper remains still. The whole badge rotates to show the final orientation. Lift either finger to apply exactly the selected angle once. A twist can take over from live panning; after that point the paper stays still. Cancellation, focus loss, a third contact or fingers becoming too close discard the pending angle. Lift all navigation contacts before drawing again.

View rotation does not resample, rotate, or swap document pixels/dimensions, and PNG export retains document orientation. Angle is saved with viewport metadata, not document geometry. BOOX preview and raw input use the paper's bounding rectangle. Large stepped polygon exclusions broke raw callbacks through TouchHelper; sending them directly to EpdController instead restored recording but lost live preview. Both paths now use the simple rectangle. Completed strokes are inverse-mapped, rejected if they start outside the paper, and clipped exactly by the bitmap. Live hardware ink may briefly cross a rotated paper edge into gray until pen-up. Exact polygon clipping of hardware preview remains unresolved; preserve fast preview and stroke recording when investigating it.

Twist rotation snaps upright within 4 degrees of 0. The badge stays at 0 until twisting beyond 7 degrees, making it easier to select upright despite finger jitter. Release while the badge shows 0 to apply exactly 0 degrees. This snap affects the gesture only; the toolbar angle dialog still accepts exact arbitrary angles.

**Pinch zoom:** pinch in/out to select level **1–7**: **10%, 25%, 50%, 100%, 150%, 250%, 400%**. The upright badge shows both the level and actual percentage; the canvas stays still until either finger lifts. Selection uses the starting zoom times the finger-distance ratio, rounded to the closest level in logarithmic scale. A 15% span change and movement beyond touch slop are required, starting with fingers at least 48dp apart. The first deliberate pinch or twist locks that selection for the rest of the gesture; pan remains live until then. Zoom preserves the point under the midpoint where selection started, subject to document bounds. Cancellation/focus loss/extra contacts discard the pending zoom. No special reset snap is used. Toolbar zoom and Fit keep their existing behavior.

View rotation screenshot (screenshot omitted) shows the installed build at 15°. Automated checks pass; physical preview/edge validation is tracked in the handoff.

The heading shows document dimensions and zoom. White is paper; gray is outside the document. Starting an Android pen stroke in the gray margin is ignored; BOOX's drawing region is restricted to the visible paper. Button zoom is centered; two-finger panning reaches off-center areas. Fit recenters the paper. Current document/zoom screenshot (screenshot omitted).

Custom sizes must be positive, at most 8192 on either side, and within the runtime pixel budget. On this tablet the Java heap limit is 256 MiB and the initial canvas limit is 12 million pixels. The selector shows bitmap and estimated working memory; all presets were allocated/exported successfully on-device. A5/A4's 300 ppi reference is retained in the checkpoint; PNG currently carries pixel dimensions without embedded print-resolution metadata.

Both input paths normalize pressure to 0–1 before applying the same linear brush curve: 1–12 canvas pixels. BOOX queries `EpdController.getMaxTouchPressure()` (4095 on this tablet). Android uses the input device's reported pressure axis range, falling back to 0–1 when unavailable. Values outside the range are clamped. Status displays normalized percentages; logs retain raw values and range provenance. Equal normalized pressures produce the same bitmap width, but the physical force response may still differ between input paths.

BOOX's low-latency preview uses native PENCIL rendering with live widths supplied by the app. `LivePreviewWidth` receives `PressureBrush.width(sample) * capturedZoom`, so pressure and tilt follow the same curve as bitmap commits. Begin updates immediately; moves coalesce over 2ms with a small jitter threshold. The user reported substantially improved matching. Native caps/interpolation may still differ; hardware opacity/alpha masking remains a pen-up operation. The bitmap brush is shared by Android live preview and commits. Erasing and public multi-document save/open are not implemented.

SDK ordering remains important: configure style/width/color after raw drawing is opened/enabled and after each bitmap handoff. Historically FOUNTAIN fixed pressure appearing only at pen-up because the app did not yet drive live pressure widths. The current app-width bridge intentionally uses PENCIL so firmware does not apply a second pressure algorithm. Do not revert it based on the historical workaround. See [BRUSHES.md](BRUSHES.md).

The document is checkpointed atomically when the activity pauses and restored on launch. Input is disabled and pending Android strokes are cancelled on pause/focus loss; BOOX callbacks from old sessions are discarded. Input opens again when the activity has focus and a valid surface. A force-stop, crash, or reinstall can lose changes since the last pause; export useful drawings. View resizing/rotation changes only the viewport and no longer crops or reallocates the document pixels. Heading updates get a display frame before hardware ink is reopened, avoiding stale angle/zoom labels. The status area has a fixed height so pressure updates do not shift the drawing region.

App-private `files/document.boox` is an atomic version-2 ZIP checkpoint containing `document.json` and `layers/<layer-id>.png` for each layer. Metadata preserves bottom-to-top order, names, lock/visibility flags and selected layer ID. Version-1 archives (`layer.png`) remain readable and migrate on the next checkpoint. Metadata includes document/layer IDs, fixed dimensions, optional ppi reference, and viewport scale/center/fit/`rotationDegrees`. Older checkpoints without the angle load at 0°. New layers start transparent; display/export composite over white. The older `canvas.png` is migrated at its actual dimensions (including landscape) and remains untouched as a backup. The archive becomes authoritative after migration. Invalid archives are reported without silently replacing them. This checkpoint is not yet a user-facing document library.

## Development

```powershell
.\dev.ps1 doctor
.\dev.ps1 build    # APK and Android lint
.\dev.ps1 run      # build, install, launch
.\dev.ps1 test     # isolated device bitmap checks; stops the running app
.\dev.ps1 capture  # screenshot, relevant logcat, stroke samples, firmware
```

Run these from the project folder in Windows PowerShell. `run` and `test` first visit Home if the app process is running so `onPause` can checkpoint the drawing before installation replaces it. They enable the installed `art.boox.probe` package for user 0 because the tablet has disabled it after replacement. `test` installs a separate test APK and leaves the drawing app stopped. Use `run` to reopen it. Neither command uninstalls or clears app data.

To select a device or nondefault SDK:

```powershell
.\dev.ps1 run -Serial YOUR_DEVICE_SERIAL
.\dev.ps1 doctor -Sdk 'D:\Android\Sdk'
```

`-Sdk` affects ADB/doctor only; also update `local.properties` for Gradle if moving the SDK. `JAVA_HOME` must point to a compatible JDK; when unset, the script uses `C:\Program Files\Android\Android Studio\jbr`. No global Java/ADB PATH changes are required. Android Studio can generate `local.properties`; its current local value is `sdk.dir=C\:/Users/YOUR_NAME/AppData/Local/Android/Sdk`.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The installed app is **BOOX Art Probe**, package `art.boox.probe`, launcher activity `.MainActivity`. Capture output goes to `captures/yyyyMMdd-HHmmss/` with `screen.png`, `logcat.txt`, `strokes.jsonl`, `document-state.json`, and `device.txt`. Document state describes the latest active viewport/input configuration, not a complete document backup. Captures, build output, local SDK settings, and downloaded `.tools` files are ignored by Git.

Build pins: AGP 9.2.1, Gradle 9.4.1 with distribution checksum, compile SDK 37, Build Tools 36.0.0, Java bytecode 17. The installed Studio JBR 25 runs Gradle. Min SDK 29 and target SDK 32; compiling against SDK 37 does not require newer Android on the tablet. This is a sideloaded probe, so the Google Play target-version lint check is disabled specifically. Other lint checks run normally.

BOOX dependency: `com.onyx.android.sdk:onyxsdk-pen:1.5.4.4`, as used by the official demo. BOOX artifacts come from its HTTPS Maven repository. Three older dependencies need the BOOX proxy repository; repository access is restricted to those groups. Jetifier bridges legacy Android support dependencies; vector drawable artifacts are pinned to 1.2.0 to resolve a manifest namespace conflict. SDK permissions unrelated to this probe are removed during manifest merging. No external native library was copied from a third-party drawing app.

`ProbeApplication` follows the official demo's startup: initialize `ResManager` and `RxBaseAction`, and use `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` to allow the SDK's firmware API reflection on Android 11+. The exemption applies to this app process; no device-wide hidden-API policy is changed. Without this startup step, Android 12 blocked the firmware methods and raw input regions mapped to empty despite TouchHelper reporting enabled.

Sources checked 2026-10-22:

- [Official BOOX pen demo source](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/scribble/ui/ScribbleTouchHelperDemoActivity.java)
- [Official demo dependencies](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/build.gradle)
- [Official demo Application initialization](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/DemoApplication.java)
- [TouchHelper documentation](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md)
- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)

## Diagnostics and checks

Verified on the Go103: Android pen input and pressure samples, BOOX raw callbacks and committed strokes, and an exported PNG containing artwork. User reports BOOX mode works well and is much more responsive than Android mode. Input/palm behavior is acceptable for now, and live pressure response is physically confirmed after the SDK initialization-order fix. This is provisional validation, not a full hardware test matrix.

Pressure follow-up: device tests pass with the shared variable-width brush; real BOOX strokes reach normalized pressure 1.0 using the firmware-reported maximum 4095. The pressure screenshot (screenshot omitted) shows committed variable-width artwork before the subsequent live-preview fix. Home/return and sleep/wake produced pause/resume logs and reopened raw input, and real strokes continued afterward. A checkpoint hash matched across the initial cycle. Systematic edge alignment and exact preview/bitmap shape matching remain unmeasured.

`BooxProbe` logcat contains startup/device context, SDK status/failures, lifecycle, view bounds/origin, document dimensions/viewport, clip setup time/exclusion count, and stroke statistics including out-of-document point counts. App-private `files/strokes.jsonl` schema 5 records document/layer IDs, viewport scale/translation/`rotationDegrees`, document/view sizes, brush parameters, pressure range/provenance, wall time, and `[documentX, documentY, normalizedPressure, eventTimestamp, rawPressure]` samples. Schema 3 also used document coordinates but only scale/translation (0°). Schema 2 used view coordinates and no document transform. Schema 1 (no version field) used four slots with raw pressure in slot three and fixed width 5. BOOX timestamps are SDK-native; Android event time is uptime milliseconds. Future replay must interpret each schema explicitly.

Device instrumentation checks bitmap pixels, PNG round-trip, pressure normalization/clamping, variable width, edge clipping, clear, mixed finger+pen input with pointer index changes, non-pen rejection, cancellation, mode/lifecycle interruption, and checkpoint restore. It also checks all presets/exports, invalid sizes, selector validation, legacy migration, document IDs/ppi, atomic archive restoration, corrupt-file preservation, viewport transforms/bounds, and pixels surviving view resize. Pixel tests use separate cache directories; selector checks briefly open and close the real activity without replacing its drawing. Tests do not validate physical preview, palm behavior, latency, or ghosting. Build/lint pass with warnings from legacy SDK code/resources and UI text; do not treat that as release readiness.

The user physically tested the document/zoom build and reported zoomed pen alignment works well. Exact hardware/bitmap brush-shape equivalence and a systematic orientation/edge/ghosting matrix remain regression work.

Manual regression checklist (repeat when changing input, brush, layout, or lifecycle handling):

1. Draw dots, slow diagonals, curves, and fast strokes in both modes. Compare the preview with the committed stroke after lifting the pen, especially near all edges.
2. Rest your palm while drawing; fingers should not draw. Verify toolbar taps still work.
3. Export and inspect the PNG, then refresh: artwork should remain aligned and intact.
4. Home and return; sleep/wake and return; rotate. Check input recovery and full document retention, including pixels outside the current viewport.
5. Compare physical latency and ghosting on the tablet. Screenshots cannot measure these or guarantee capture of hardware preview.

UI Automator returned a null root on the first device attempt; screenshot inspection and ADB logs work. Do not rely on UI Automator for this firmware until investigated.

## Code map and development rules

Read [FEATURES.md](FEATURES.md) for feature extension recipes and preview/lifecycle regression traps.

| File under `app/src/main/java/art/boox/probe/` | Responsibility |
| --- | --- |
| `ProbeApplication.kt` | Firmware API access and Onyx resource/Rx initialization before SDK use |
| `MainActivity.kt` | Controls, input lifecycle, stroke diagnostics, MediaStore PNG export |
| `BooxPenAdapter.kt` | TouchHelper session, raw pressure range, hardware preview, firmware refresh, stale-callback rejection |
| `PressureBrush.kt` | Sample/stroke/range models, normalization, shared variable-width bitmap geometry |
| `RasterSurface.kt` | Document display, stylus-only Android events, view-to-document mapping, zoom, preview, commit, checkpoint |
| `DrawingDocument.kt` | Fixed-size raster document, presets/limits, flattened export, archive persistence/migration |
| `LayerCommand.kt` | Stable-ID structural commands for add/duplicate/delete/merge/reorder/lock/visibility |
| `LayerPanel.kt` | Configurable-side panel, icon controls, long-press rename and direct bitmap thumbnails |
| `EinkIconButton.kt` | Shared monochrome action/toggle component, selection borders and vector glyphs |
| `AppSettings.kt`, `SettingsDialog.kt` | Typed independent app preferences and extensible Apply/Cancel editor |
| `DocumentViewport.kt` | Scale/translation/rotation, inverse matrix, Fit/100%, zoom bounds, polygonal paper mask and hardware exclusions |
| `CanvasGestures.kt` | Stable finger pair, live pan, first-intent pinch/twist selection, deferred zoom/rotation and commit/cancel |
| `GestureIndicator.kt` | Shared rotating angle/upright zoom-level badge; fixed overlay and coalesced redraws without repainting the document |
| `ZoomLevels.kt` | Seven absolute zoom levels, display percentages, nearest-level selection |
| `EditHistory.kt` | DocumentEdit action boundary, stroke/Clear actions, compressed before/after tile patches, bounded undo/redo |
| `HistoryTapGestures.kt` | Two-finger tap validation, delayed single-tap undo and double-tap redo; injected scheduling |
| `CanvasSizeDialog.kt` | Preset/custom selection, orientation swap, validation and memory estimate |

`app/src/androidTest/java/art/boox/probe/RasterInstrumentation.kt` runs bitmap/input checks, `DocumentChecks.kt` covers document/viewport/presets, `CanvasGestureChecks.kt` covers two-finger panning, pointer order changes, cancellation, pen priority, persistence and post-pan pen mapping; `GestureRotationChecks.kt` covers deferred twist selection/release, angle wrapping, pointer order, cancellation, close contacts, pen priority and unchanged pixels; `CanvasDialogChecks.kt` exercises the selector. `settings.gradle.kts` restricts BOOX repository access; `app/build.gradle.kts` pins SDK dependencies and target behavior. `gradle/wrapper/gradle-wrapper.properties` pins Gradle with its distribution checksum.

Keep firmware calls inside the BOOX adapter/startup code and document mutations on the UI thread. Preserve pen-only drawing and finger-operated controls. Document pixels and brush sizes must remain independent of view size; apply the inverse viewport transform exactly once to input. Keep pressure and coordinate schemas distinct in logs. Keep current PENCIL style/width configuration after `openRawDrawing()` and each handoff; dynamic app widths now provide live pressure. Guard delayed callbacks against session changes, and scale preview width/restrict its region after viewport changes. Run `build` and relevant device tests after engine/input changes; use a real pen for preview, alignment, and latency checks.

The angle badge uses per-view DU refresh while visible, restored on hide, with ordinary Android refresh as fallback. This is separate from raw pen ink; its speed/ghosting need physical checks. See [Onyx screen update documentation](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Screen-Update.md) for per-view update modes. No application-wide fast mode is enabled.

`LayerChecks` covers layer order/alpha/cache updates, independence of copies, selected-vs-recorded history targets, protected input, merge/delete/reorder undo/redo, v1-to-v2 migration, multi-layer restore/export, limits and the full BOOX layer budget. `LayerPanelChecks` exercises actual panel collapse/expand, viewport resize and unchanged document identity. A seven-layer 1860x2480 device run measured 104ms for initial full composition and 8.8ms per cached full-document draw, with about 163MiB native heap. These are synthetic rendering measurements, not perceived pen/gesture latency guarantees.

`DocumentEdit` declares affected document regions and applies a raster mutation. Future fills/cuts should enter through this boundary and declare every pixel they may touch. `EditHistory` records changed 128px tiles before/after with fast lossless deflate compression; undo restores pixels rather than replaying brush code. Strokes include antialias/width padding, so overlap and clipped edges restore exactly. Clear participates too; unchanged edits do not consume entries or invalidate redo. History identifies document/layer IDs, keeps at most 10 total undo/redo entries, and budgets compressed storage at min(32MiB, heap/8), evicting oldest entries under pressure. An edit exceeding the whole budget leaves no undo chain across that edit. No full-canvas copies are retained. Pixel entries restore the recorded layer ID even after selection changes. Structural entries store before/after metadata plus compressed images only for inserted, removed or modified layers; add/lock/visibility/reorder avoid copying unchanged pixels. Navigation, selection, panel visibility and document replacement are outside this action stack. Undo/redo suspends BOOX input and uses the same frame-before-preview reopen path as navigation.

`HistoryChecks.kt` covers stroke overlap/alpha/tile edges, generic fill/cut-style actions, failed-action rollback, Clear, branching/no-ops, 10-entry eviction, memory limits, busy pen, view changes and session restore behavior. `HistoryTapChecks.kt` uses virtual time for single/double disambiguation, pointer reordering, timeout boundaries, cancellation, pen priority, extra/moving contacts, long holds, slower deliberate taps, and combined dispatch with pan/pinch/twist so navigation cannot be swallowed by tap recognition. Existing pen/navigation checks remain required.

## Troubleshooting

- **No device / unauthorized:** unlock and connect the BOOX with a data cable, enable USB Debug Mode, and accept its authorization prompt. Run `doctor` again. With multiple devices supply `-Serial`.
- **BOOX says enabled but no strokes:** inspect captured logcat. Hidden-API denials and empty mapped regions were fixed by `ProbeApplication` startup. Do not infer successful pen input just from TouchHelper's enabled flags.
- **Pressure appears only after pen-up:** check `Hardware preview: app-width pencil`, raw move delivery and live width update counts/ranges. Verify `PressureBrush.width(sample) * capturedZoom` reaches the setter before pen-up. Preserve style configuration after open/handoff, immediate begin updates and generation guards. Older fountain-only advice is historical.
- **Optional firmware class warnings:** the SDK probes `PenConfig`, `PenButton`, and `StrokeConfig`, which are absent here. Those warnings alone did not prevent working input; check actual raw callbacks.
- **Activity does not exist despite successful install:** inspect the package's enabled state. The tablet has disabled the probe after replacement; the scripts now run `adb shell pm enable --user 0 art.boox.probe`. Use the SDK's absolute ADB path if ADB is not on PATH.
- **Zoomed ink jumps at pen-up:** check the session's captured inverse transform, drawing-view origin, and paper region. Raw callbacks are view-relative, while schema 3/4 and layer pixels are document-relative. Do not apply the screen origin or zoom twice.
- **Rotated ink jumps or spills into gray corners:** verify the inverse rotation for saved ink. Temporary hardware preview spill is a known limitation of rectangular BOOX clipping; saved ink must remain inside the document. Large polygon exclusion lists broke recording or preview on this firmware. This SDK ignores empty exclusion lists, so each configuration sends an off-view exclusion rectangle to clear old masks.
- **Interrupted drawing lost:** only completed bitmap strokes are retained, and automatic checkpointing happens on pause. Export useful artwork. The archive keeps a single document; multi-document saving and continuous crash recovery are future work.
- **Cannot open drawing:** retain `document.boox` for diagnosis; loading errors do not silently replace it. Legacy `canvas.png` may still be available as a separate backup. New canvas can deliberately replace an unusable checkpoint after confirmation.

## Layer UI and settings

The panel defaults to the right. The gear opens Settings; choose left/right and Apply. Preferences persist independently of artwork and undo history. Collapsing preserves the chosen side. Long-press a layer name to rename; rename supports Undo/Redo and survives checkpoints. Names are trimmed, nonblank single-line text up to 80 characters.

The top icon row is Add, Duplicate, Merge down, Delete. Each layer has Visibility, Lock, Up, Down in one row. A black outline marks the selected layer and enabled toggle state (visible/locked); crossed-out eye/open lock show the opposite states. Reorder arrows disable at stack boundaries. Long-press action icons for tooltips. All icon targets are at least 48dp. [E-ink UI conventions](EINK_UI.md) explain the reusable component and source research.

`AppSettings.kt` owns typed preferences; `SettingsDialog.kt` owns their editor. Add future settings through this model/store/editor rather than artwork metadata. `EinkIconButton.kt` owns shared action/toggle visuals and glyphs. `LayerRenameChecks` covers validation, metadata history, locked/hidden layers and persistence; `SettingsChecks` covers defaults, persistence, fallback and modal commit/cancel behavior. `LayerPanelChecks` includes the actual rename dialog against an isolated document.

### Per-layer settings and opacity

Long-press a layer thumbnail to open Layer settings (name long-press still renames). Opacity ranges from 0 to 100%, with a percentage readout. Slider changes stay in the modal until Apply; Cancel leaves the artwork unchanged. Apply is one undoable action. Opacity persists in checkpoints and duplicates, affects thumbnails and export, and preserves appearance when merging. A 0% layer can still receive strokes. Locked/hidden layers allow opacity changes. BOOX firmware live pen feedback remains black; the layer opacity appears in the correct composite at pen-up.

`LayerSettingsDialog.kt` is the per-layer modal extension point. `LayerCommand.Properties` changes metadata through history; `DrawingDocument.drawLayer` owns group alpha, including Android pending ink. [FEATURES.md](FEATURES.md) documents merge, backward compatibility and regression checks.

### Alpha lock

Layer settings also has Alpha lock. It preserves the layer's existing transparency while painting, including partially transparent pixels; an empty layer stays empty. This is separate from full layer lock. Apply commits opacity and alpha lock together; Cancel discards changes. Both are undoable and checkpointed. Clear is disabled while alpha lock is enabled. Current black ink may not visibly change existing black artwork. BOOX live preview can briefly extend outside the alpha mask; the correct constrained stroke appears at pen-up. Android live preview is masked directly. See FEATURES.md for blend-mode/isolation requirements and AlphaLockChecks.

## Tilt-width foundation

Tilt widens pressure-sensitive strokes through a replaceable modifier (upright dead zone 15 degrees; maximum 2.5x at 65 degrees). Android live geometry and final bitmap strokes use it. BOOX fast firmware preview is preserved and can differ until pen-up. Status/logs show tilt ranges; physical changing raw data still needs confirmation. [BRUSHES.md](BRUSHES.md) documents capture, schema 5 point fields, history bounds and the future engine boundary.

BOOX live pressure/tilt preview now updates PENCIL width using the shared bitmap width function (2ms coalescing), without closing input or repainting the canvas. Logcat reports move/update counts and sent width ranges. Real tilt data and improved live response are physically confirmed by logs/user feedback. See BRUSHES.md for lifecycle and cancellation rules.
