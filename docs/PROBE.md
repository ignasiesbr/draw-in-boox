# Kotlin drawing prototype: working guide

This is a single-document, single-layer prototype running on the verified Go103 / Android 12 / firmware 4.2 device. Document dimensions, canvas selection, and button zoom are implemented; [../plan.md](../plan.md) tracks later features and milestone status. Detailed UI design is deferred.

## Controls

- **BOOX pen / Android pen:** tap to switch input paths. The label reports the current mode. BOOX explicitly uses the raw-digitizer/SF renderer (no SDK finger renderer), with fountain-style hardware preview and completed strokes committed to a bitmap. Android mode accepts only stylus pointers, including historical samples, and ignores finger, mouse, and unknown tools. SDK setup failures fall back to Android mode and appear in the status and logcat. Finger taps on buttons remain enabled.
- **Refresh:** redraw the bitmap and request a BOOX GC screen refresh.
- **Export PNG:** save the full document, flattened over white, to `Pictures/BOOX Art` through MediaStore. Zoom and the visible screen area do not affect export dimensions. Broad storage access is not required.
- **Clear:** confirmation before erasing the current layer. It keeps the document dimensions.
- **New canvas:** BOOX 1860×2480, square 2048×2048, A5 1748×2480, A4 2480×3508, landscape 2560×1440, or custom pixel dimensions. Swap width/height for orientation. Replacement requires explicit discard or PNG export first.
- **− / +:** center-based zoom steps. **Fit** shows the full document; **100%** maps one document pixel to one view pixel. Zoom is capped at 400%, including Fit for very small canvases. Zoom requests during pen contact are refused until the pen is lifted. Finger pan/pinch is not implemented yet.
- **Rotate −15° / Rotate +15°:** turn the drawing view in steps. **Angle…** accepts any finite angle, including fractional degrees. **Reset angle** returns to 0°. Rotation is clockwise for positive angles, normalized to −180° through less than 180°. It preserves the view center/zoom where bounds allow; when Fit is active, it recomputes the fit for the angled page. Fit/100% retain the selected angle. New canvas resets it. Changing angle during a stroke is refused. No finger-rotation gesture yet.

View rotation does not resample, rotate, or swap document pixels/dimensions, and PNG export retains document orientation. Angle is saved with viewport metadata, not document geometry. BOOX preview and raw input use the paper's bounding rectangle. Large stepped polygon exclusions broke raw callbacks through TouchHelper; sending them directly to EpdController instead restored recording but lost live preview. Both paths now use the simple rectangle. Completed strokes are inverse-mapped, rejected if they start outside the paper, and clipped exactly by the bitmap. Live hardware ink may briefly cross a rotated paper edge into gray until pen-up. Exact polygon clipping of hardware preview remains unresolved; preserve fast preview and stroke recording when investigating it.

View rotation screenshot (screenshot omitted) shows the installed build at 15°. Automated checks pass; physical preview/edge validation is tracked in the handoff.

The heading shows document dimensions and zoom. White is paper; gray is outside the document. Starting an Android pen stroke in the gray margin is ignored; BOOX's drawing region is restricted to the visible paper. Zoom is currently centered, so navigating off-center areas of a zoomed document awaits the finger-pan milestone. Current document/zoom screenshot (screenshot omitted).

Custom sizes must be positive, at most 8192 on either side, and within the runtime pixel budget. On this tablet the Java heap limit is 256 MiB and the initial canvas limit is 12 million pixels. The selector shows bitmap and estimated working memory; all presets were allocated/exported successfully on-device. A5/A4's 300 ppi reference is retained in the checkpoint; PNG currently carries pixel dimensions without embedded print-resolution metadata.

Both input paths normalize pressure to 0–1 before applying the same linear brush curve: 1–12 canvas pixels. BOOX queries `EpdController.getMaxTouchPressure()` (4095 on this tablet). Android uses the input device's reported pressure axis range, falling back to 0–1 when unavailable. Values outside the range are clamped. Status displays normalized percentages; logs retain raw values and range provenance. Equal normalized pressures produce the same bitmap width, but the physical force response may still differ between input paths.

BOOX's low-latency fountain preview uses its firmware brush algorithm and a width setting of 12 document pixels multiplied by zoom. Its exact shape may differ from the app's tapered round bitmap brush after pen-up; inspect that handoff physically. The bitmap brush is shared by Android's live preview and committed strokes. Erasing, multiple layers, undo, finger navigation, and a multi-document save/open workflow are not implemented.

Important SDK ordering: `SFTouchRender.openDrawing()` resets the firmware stroke style to pencil. `BooxPenAdapter.resumePreview()` therefore applies fountain style, width, and color **after** opening/enabling input, and reapplies them after every bitmap handoff. Setting style before `openRawDrawing()` caused fixed-width live ink even though the committed bitmap was pressure-sensitive. The official demo also sets style after opening. User physically retested the fix and confirmed pressure-sensitive live preview works as expected. A screenshot after pen-up only shows the committed bitmap.

The document is checkpointed atomically when the activity pauses and restored on launch. Input is disabled and pending Android strokes are cancelled on pause/focus loss; BOOX callbacks from old sessions are discarded. Input opens again when the activity has focus and a valid surface. A force-stop, crash, or reinstall can lose changes since the last pause; export useful drawings. View resizing/rotation changes only the viewport and no longer crops or reallocates the document pixels. Heading updates get a display frame before hardware ink is reopened, avoiding stale angle/zoom labels. The status area has a fixed height so pressure updates do not shift the drawing region.

App-private `files/document.boox` is a single atomic ZIP checkpoint containing `document.json` and `layer.png`. Metadata includes document/layer IDs, fixed dimensions, optional ppi reference, and viewport scale/center/fit/`rotationDegrees`. Older checkpoints without the angle load at 0°. New layers start transparent; display/export composite over white. The older `canvas.png` is migrated at its actual dimensions (including landscape) and remains untouched as a backup. The archive becomes authoritative after migration. Invalid archives are reported without silently replacing them. This checkpoint is not yet a user-facing document library.

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

`BooxProbe` logcat contains startup/device context, SDK status/failures, lifecycle, view bounds/origin, document dimensions/viewport, clip setup time/exclusion count, and stroke statistics including out-of-document point counts. App-private `files/strokes.jsonl` schema 4 records document/layer IDs, viewport scale/translation/`rotationDegrees`, document/view sizes, brush parameters, pressure range/provenance, wall time, and `[documentX, documentY, normalizedPressure, eventTimestamp, rawPressure]` samples. Schema 3 also used document coordinates but only scale/translation (0°). Schema 2 used view coordinates and no document transform. Schema 1 (no version field) used four slots with raw pressure in slot three and fixed width 5. BOOX timestamps are SDK-native; Android event time is uptime milliseconds. Future replay must interpret each schema explicitly.

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

| File under `app/src/main/java/art/boox/probe/` | Responsibility |
| --- | --- |
| `ProbeApplication.kt` | Firmware API access and Onyx resource/Rx initialization before SDK use |
| `MainActivity.kt` | Controls, input lifecycle, stroke diagnostics, MediaStore PNG export |
| `BooxPenAdapter.kt` | TouchHelper session, raw pressure range, hardware preview, firmware refresh, stale-callback rejection |
| `PressureBrush.kt` | Sample/stroke/range models, normalization, shared variable-width bitmap geometry |
| `RasterSurface.kt` | Document display, stylus-only Android events, view-to-document mapping, zoom, preview, commit, checkpoint |
| `DrawingDocument.kt` | Fixed-size raster document, presets/limits, flattened export, archive persistence/migration |
| `DocumentViewport.kt` | Scale/translation/rotation, inverse matrix, Fit/100%, zoom bounds, polygonal paper mask and hardware exclusions |
| `CanvasSizeDialog.kt` | Preset/custom selection, orientation swap, validation and memory estimate |

`app/src/androidTest/java/art/boox/probe/RasterInstrumentation.kt` runs bitmap/input checks, `DocumentChecks.kt` covers document/viewport/presets, and `CanvasDialogChecks.kt` exercises the selector. `settings.gradle.kts` restricts BOOX repository access; `app/build.gradle.kts` pins SDK dependencies and target behavior. `gradle/wrapper/gradle-wrapper.properties` pins Gradle with its distribution checksum.

Keep firmware calls inside the BOOX adapter/startup code and document mutations on the UI thread. Preserve pen-only drawing and finger-operated controls. Document pixels and brush sizes must remain independent of view size; apply the inverse viewport transform exactly once to input. Keep pressure and coordinate schemas distinct in logs. Do not move fountain configuration before `openRawDrawing()`: the SDK resets it. Guard delayed callbacks against session changes, and scale preview width/restrict its region after viewport changes. Run `build` and relevant device tests after engine/input changes; use a real pen for preview, alignment, and latency checks.

## Troubleshooting

- **No device / unauthorized:** unlock and connect the BOOX with a data cable, enable USB Debug Mode, and accept its authorization prompt. Run `doctor` again. With multiple devices supply `-Serial`.
- **BOOX says enabled but no strokes:** inspect captured logcat. Hidden-API denials and empty mapped regions were fixed by `ProbeApplication` startup. Do not infer successful pen input just from TouchHelper's enabled flags.
- **Pressure appears only after pen-up:** check that `resumePreview()` applies fountain style after opening/enabling and after every bitmap handoff. This ordering issue was fixed and physically retested.
- **Optional firmware class warnings:** the SDK probes `PenConfig`, `PenButton`, and `StrokeConfig`, which are absent here. Those warnings alone did not prevent working input; check actual raw callbacks.
- **Activity does not exist despite successful install:** inspect the package's enabled state. The tablet has disabled the probe after replacement; the scripts now run `adb shell pm enable --user 0 art.boox.probe`. Use the SDK's absolute ADB path if ADB is not on PATH.
- **Zoomed ink jumps at pen-up:** check the session's captured inverse transform, drawing-view origin, and paper region. Raw callbacks are view-relative, while schema 3/4 and layer pixels are document-relative. Do not apply the screen origin or zoom twice.
- **Rotated ink jumps or spills into gray corners:** verify the inverse rotation for saved ink. Temporary hardware preview spill is a known limitation of rectangular BOOX clipping; saved ink must remain inside the document. Large polygon exclusion lists broke recording or preview on this firmware. This SDK ignores empty exclusion lists, so each configuration sends an off-view exclusion rectangle to clear old masks.
- **Interrupted drawing lost:** only completed bitmap strokes are retained, and automatic checkpointing happens on pause. Export useful artwork. The archive keeps a single document; multi-document saving and continuous crash recovery are future work.
- **Cannot open drawing:** retain `document.boox` for diagnosis; loading errors do not silently replace it. Legacy `canvas.png` may still be available as a separate backup. New canvas can deliberately replace an unusable checkpoint after confirmation.
