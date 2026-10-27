# BOOX Art

The **floating toolbar** groups Marker, Calligraphy, Fountain, Ink, Brush, Ballpoint,
and Pencil in fine/coarse textures (Fountain/Brush require the Lab build), followed
by Eraser, Undo, Redo, Settings and rotate-left/right icons. The rotation icons at
the right end turn the view by 15 degrees per tap. Studio pens and the old top rows are hidden.
Tap the selected pen again to edit it. The new pens use original SDK
geometry with firmware-matched settings; physical matching is still under test.
See [BOOX pens implementation and validation](docs/BOOX_NOTES_PENS.md).

Open **Settings** for Files, New canvas, Clear, Refresh, View controls and input
mode. Clear still asks before clearing the selected layer. View controls contains
button zoom and rotation; finger navigation remains available on the canvas.
Two-finger twist rotation defaults off; enable **Settings > Canvas gestures >
Rotate: two-finger twist** to use it. Pinch zoom and two-finger pan stay available.

The layers panel takes only the height its controls and layer list need. The
canvas continues underneath it; long lists scroll and hiding leaves a small
expand button. The panel can still sit on either side.

Fresh drawings and New canvas start at **100% zoom**; existing saved views restore.
Tap the **four-corners icon** between Redo and Settings for Drawing mode, hiding
the toolbar and layers. Use the small **Show controls** button at top right, or
Back, to bring them back. Zoom and pan stay where you left them.

New documents default to **BOOX screen · landscape**, 2480 × 1860 pixels.
Use **Swap portrait / landscape** in New canvas for portrait. Existing drawings
keep their saved dimensions.

Use **Settings → Files → Import image…** to add a JPG or PNG as a new layer,
centered and fitted inside the canvas. Long-press that layer's thumbnail to change
its opacity. Import preserves PNG transparency and supports Undo/Redo; imported
pixels are included in saved drawings and PNG exports. See [image import](docs/IMAGE_IMPORT.md).

Use **Files → Save a copy…** to store an editable `.boox` drawing, and **Files →
Open drawing…** to resume it on this device or another device running the app.
Files preserve all layer pixels, ordering, names and settings. PNG export is also
in Files. New/Open offer saving before replacement. The versioned portable format
includes per-pen settings and selection, and omits zoom, rotation and history; see [the format contract](docs/SAVE_FORMAT.md).

The editable native renderer trial runs as a separate **BOOX Art Lab** app. Build
with `dev.ps1 run -Renderer recovered` after building the Rust library. It adds a
Fountain Lab brush; live/final matching remains experimental. See
[experiment instructions](docs/RECOVERED_RENDERER_EXPERIMENT.md) and the independent
[official BOOX brush fallback guide](docs/BOOX_BUILTIN_BRUSHES_FALLBACK.md).

Undo/Redo arrow buttons are on the drawing toolbar. In Settings → History gestures,
disable Undo (two-finger tap) and Redo (two-finger double tap) separately, then Apply.
Toolbar actions remain available when gestures are off; choices are remembered.

Current native compatibility policy: gray and wide brushes use app-rendered live
ink after a BOOX system pen-renderer crash. The fast native path remains for black
brushes up to the accepted 30px footprint. See [the crash workaround](docs/NATIVE_PREVIEW_SAFETY.md).

Tap a tool to select it; tap the selected icon again for settings with Apply/Cancel. Marker and Eraser support 1–128 document pixels, with new settings defaulting to 32px and constant width. Ink retains its 1–12px base range. Drawing-tool settings include eight opaque gray shades, remembered independently per pen. Width, dynamics and preview settings are also kept per pen and saved with the drawing. Eraser clears the selected layer to reveal lower layers or paper, with undo/redo and layer-lock protection. Existing settings are retained. Wide/gray Marker uses app preview, which can feel slower than native black/narrow ink; the native crash guard remains. Eraser currently trials native white feedback at <=30px on a single full-opacity layer with alpha lock off; other eraser cases use app preview. See [brush implementation notes](docs/BRUSHES.md) and [performance limitations](docs/PERFORMANCE.md).

Native Kotlin raster drawing prototype targeting the original BOOX Go 10.3, Android 12 (API 32).

## Resume here

The user physically accepted `async-handoff-v1` as an **"incredible difference"** in responsiveness. Preserve this baseline when extending the app: immediate artwork/history commits, native live ink, bounded background surface painting from stable snapshots, and guarded reconciliation after a 450ms quiet gap. Read [the performance maintenance rules](AGENTS.md) and [the evidence and architecture](docs/PERFORMANCE.md) before changing this pipeline.

For implementation work, read [the feature maintenance guide](docs/FEATURES.md): layers/history, shared icons, extensible settings, live zoom and deferred rotation indicators, and BOOX pen-preview handoff traps.

Read [the probe guide](docs/PROBE.md) for commands, code structure, diagnostics, and troubleshooting. Read [the handoff](docs/HANDOFF.md) for accepted decisions, device context, validation history, and next steps.

The feature sequence, milestone status, and implementation considerations are in [plan.md](plan.md). The fixed-size document, canvas-size selector, and button zoom are implemented. Detailed UI design is deferred.

Arbitrary-angle view rotation is also implemented with ±15° steps, an exact angle control, and reset. It rotates the display/input coordinates, not document pixels or PNG exports. Rotation tests pass, and the user confirmed live preview and stroke retention through zoom. Temporary preview spill across angled paper edges is accepted. The pre-rotation working version is saved in Git as commit `8d85b0f`, tag `baseline-before-view-rotation`.

The prototype builds and runs on the tablet. BOOX live pressure sensitivity and button-zoom alignment are physically confirmed; the canvas accepts pen input while buttons accept fingers. Pressure is normalized to 0–1 and controls a 1–12 document-pixel brush. New canvases support BOOX, square, A5/A4, landscape, and custom sizes. Document dimensions survive view resizing, and PNG export always uses the full document. Two-finger panning and deferred twist rotation are implemented. The user confirmed angle selection works; the latest improvement rotates the angle badge itself to preview the final orientation. Pinch zoom now repaints the complete visible canvas during contact, in 25 percentage-point steps from 75% through 400%; button zoom shares these steps and Fit still shows the whole page. This live update awaits tablet validation. Undo/redo supports up to 10 session actions, including strokes, Clear and layer changes, using buttons or two-finger single/double taps. A collapsible layer panel (right by default, configurable) supports add/select, lock/show, duplicate, merge down, delete and up/down ordering. A canvas-dependent memory limit protects RAM (7 layers on the BOOX preset). Layers persist in the internal checkpoint and portable `.boox` files; use Files to save/open editable drawings. A recorded-stroke replay command is not implemented yet.

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

`run` and `test` visit Home before replacing a running app so it can checkpoint the drawing, then enable the installed probe package (this tablet has disabled it after installation). `test` leaves the app stopped; use `run` to reopen it. Save useful drawings as `.boox` files to retain editable layers independently of the single internal restart checkpoint.

The gear opens extensible app settings, currently layer-panel side (right by default). Long-press a layer name to rename. Layer selection and toggled icons use black borders; see [e-ink UI conventions](docs/EINK_UI.md).

Long-press a layer thumbnail for per-layer settings, currently an undoable 0-100% opacity slider with Apply/Cancel.

Layer settings also supports Alpha lock: paint within existing transparency, with combined Apply/Cancel, undo/redo and persistence.

A [replaceable tilt-width modifier](docs/BRUSHES.md) now prepares the brush system: tilted samples widen strokes while pressure still sets base width. BOOX live width now shares the complete pressure/tilt calculation through a guarded native PENCIL update bridge; the user confirmed much better matching before and after pen-up.
