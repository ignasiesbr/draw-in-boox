# BOOX Art

Undo/Redo arrow buttons are on the drawing toolbar. In Settings → History gestures,
disable Undo (two-finger tap) and Redo (two-finger double tap) separately, then Apply.
Toolbar actions remain available when gestures are off; choices are remembered.

Current native compatibility policy: gray and wide brushes use app-rendered live
ink after a BOOX system pen-renderer crash. The fast native path remains for black
brushes up to the accepted 30px footprint. See [the crash workaround](docs/NATIVE_PREVIEW_SAFETY.md).

The drawing toolbar offers Ink, Tilt (default), Marker and Eraser. Tap a tool to select it; tap the selected icon again for settings with Apply/Cancel. Marker and Eraser support 1–128 document pixels, with new settings defaulting to 32px and constant width. Ink/Tilt retain their 1–12px base range. Drawing-tool settings include eight opaque gray shades, shared across pens. Eraser clears the selected layer to reveal lower layers or paper, with undo/redo and layer-lock protection. Existing settings are retained. Wide/gray Marker uses app preview, which can feel slower than native black/narrow ink; the native crash guard remains. Eraser currently trials native white feedback at <=30px on a single full-opacity layer with alpha lock off; other eraser cases use app preview. See [brush implementation notes](docs/BRUSHES.md) and [performance limitations](docs/PERFORMANCE.md).

Native Kotlin raster drawing prototype targeting the original BOOX Go 10.3, Android 12 (API 32).

## Resume here

The user physically accepted `async-handoff-v1` as an **"incredible difference"** in responsiveness. Preserve this baseline when extending the app: immediate artwork/history commits, native live ink, bounded background surface painting from stable snapshots, and guarded reconciliation after a 450ms quiet gap. Read [the performance maintenance rules](AGENTS.md) and [the evidence and architecture](docs/PERFORMANCE.md) before changing this pipeline.

For implementation work, read [the feature maintenance guide](docs/FEATURES.md): layers/history, shared icons, extensible settings, deferred zoom/rotation indicators, and BOOX pen-preview handoff traps.

Read [the probe guide](docs/PROBE.md) for commands, code structure, diagnostics, and troubleshooting. Read [the handoff](docs/HANDOFF.md) for accepted decisions, device context, validation history, and next steps.

The feature sequence, milestone status, and implementation considerations are in [plan.md](plan.md). The fixed-size document, canvas-size selector, and button zoom are implemented. Detailed UI design is deferred.

Arbitrary-angle view rotation is also implemented with ±15° steps, an exact angle control, and reset. It rotates the display/input coordinates, not document pixels or PNG exports. Rotation tests pass, and the user confirmed live preview and stroke retention through zoom. Temporary preview spill across angled paper edges is accepted. The pre-rotation working version is saved in Git as commit `8d85b0f`, tag `baseline-before-view-rotation`.

The prototype builds and runs on the tablet. BOOX live pressure sensitivity and button-zoom alignment are physically confirmed; the canvas accepts pen input while buttons accept fingers. Pressure is normalized to 0–1 and controls a 1–12 document-pixel brush. New canvases support BOOX, square, A5/A4, landscape, and custom sizes. Document dimensions survive view resizing, and PNG export always uses the full document. Two-finger panning and deferred twist rotation are implemented. The user confirmed angle selection works; the latest improvement rotates the angle badge itself to preview the final orientation. Deferred pinch zoom is also implemented with seven levels from 10% to 400%, applied on release; the user confirmed it works. Undo/redo supports up to 10 session actions, including strokes, Clear and layer changes, using buttons or two-finger single/double taps. A collapsible layer panel (right by default, configurable) supports add/select, lock/show, duplicate, merge down, delete and up/down ordering. A canvas-dependent memory limit protects RAM (7 layers on the BOOX preset). Layers persist in the internal checkpoint; public save/open/sharing remains deferred. A recorded-stroke replay command is not implemented yet.

Development should run directly on Windows: Windows project folder, Android Studio/SDK, Gradle, ADB, and screen capture. The previous conversation ran inside WSL; we deliberately chose to switch for simpler physical USB access.

Suggested project location: `C:\Users\YOUR_NAME\Projects\boox-art`.

Windows commands (PowerShell, from this directory):

```powershell
.\dev.ps1 doctor
.\dev.ps1 build
.\dev.ps1 run
.\dev.ps1 test
.\dev.ps1 capture
```

Open this folder in Android Studio to edit and build. `local.properties` contains the local SDK path and is ignored by Git. On another machine, let Studio create it. `dev.ps1` uses the Studio-bundled Java if `JAVA_HOME` is unset. `-Sdk` selects the SDK for ADB/doctor; Gradle uses `local.properties` separately. `-Serial` selects the tablet when needed. The first Gradle build downloads dependencies.

`run` and `test` visit Home before replacing a running app so it can checkpoint the drawing, then enable the installed probe package (this tablet has disabled it after installation). `test` leaves the app stopped; use `run` to reopen it. Export useful drawings to PNG: the single-document checkpoint is not yet a document browser or save/open workflow.

The gear opens extensible app settings, currently layer-panel side (right by default). Long-press a layer name to rename. Layer selection and toggled icons use black borders; see [e-ink UI conventions](docs/EINK_UI.md).

Long-press a layer thumbnail for per-layer settings, currently an undoable 0-100% opacity slider with Apply/Cancel.

Layer settings also supports Alpha lock: paint within existing transparency, with combined Apply/Cancel, undo/redo and persistence.

A [replaceable tilt-width modifier](docs/BRUSHES.md) now prepares the brush system: tilted samples widen strokes while pressure still sets base width. BOOX live width now shares the complete pressure/tilt calculation through a guarded native PENCIL update bridge; the user confirmed much better matching before and after pen-up.
