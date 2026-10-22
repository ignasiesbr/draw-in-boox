# BOOX Art

Native Kotlin raster drawing prototype targeting the original BOOX Go 10.3, Android 12 (API 32).

## Resume here

Read [the probe guide](docs/PROBE.md) for commands, code structure, diagnostics, and troubleshooting. Read [the handoff](docs/HANDOFF.md) for accepted decisions, device context, validation history, and next steps.

The feature sequence, milestone status, and implementation considerations are in [plan.md](plan.md). The fixed-size document, canvas-size selector, and button zoom are implemented. Detailed UI design is deferred.

Arbitrary-angle view rotation is also implemented with ±15° steps, an exact angle control, and reset. It rotates the display/input coordinates, not document pixels or PNG exports. Rotation tests pass, and the user confirmed live preview and stroke retention through zoom. Temporary preview spill across angled paper edges is accepted. The pre-rotation working version is saved in Git as commit `8d85b0f`, tag `baseline-before-view-rotation`.

The prototype builds and runs on the tablet. BOOX live pressure sensitivity and button-zoom alignment are physically confirmed; the canvas accepts pen input while buttons accept fingers. Pressure is normalized to 0–1 and controls a 1–12 document-pixel brush. New canvases support BOOX, square, A5/A4, landscape, and custom sizes. Document dimensions survive view resizing, and PNG export always uses the full document. Finger pinch/pan, multiple layers, undo, and a recorded-stroke replay command are not implemented yet.

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
