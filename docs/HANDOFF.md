# Research and handoff

Current source experiment: branch `experiment/recovered-boox-native`, stable app
checkpoint `29c7373` and separate fallback guide commit `688ac5f`. Normal app is
`art.boox.probe`; trial is `art.boox.probe.lab`, labeled BOOX Art Lab with backend
in its title. Trial adds Fountain Lab (wave icon). It uses firmware FOUNTAIN for
live ink and modern NeoPenNative geometry for final pixels, with a vendored editable
Rust renderer replacing only libneopen_jni.so. The official input reader remains:
the recovered reader's session JNI arguments are incompatible with the pinned Java.

The source renderer's 23 Rust tests, source Lab build/lint and final full device
suite pass. The normal official app build/lint and official Lab targeted Fountain
checks pass. Offline fixture coverage differs (official 2,487 vs source 3,671
painted pixels); these are not matching firmware preview measurements. Physical
feedback is still required. Final source fixture geometry took ~43ms and draw ~7ms
on a cold 100-sample check; the total includes file writing/scans, not pen latency.
Existing strokes recorded on Lab are separate from the user's stable drawing.
See RECOVERED_RENDERER_EXPERIMENT.md for build/compare/rollback commands and the
firmware boundary. BOOX_BUILTIN_BRUSHES_FALLBACK.md is the official-only escape route.

Alpha-lock follow-up: user accepts native ink outside the alpha mask during input,
corrected by the committed composite. Compatible black paint already uses native
feedback under alpha lock; keep that behavior and the accepted 450ms reconciliation.
Status now identifies alpha-lock commit behavior. AlphaLockChecks verifies native-
source commits preserve all alpha values and undo/redo; AsyncSurfaceChecks opens
the real SDK while alpha lock is enabled. Gray/wide crash guards remain independent.
User chose to keep gray preview rather than black native feedback that changes to
gray on commit. Retain gray app rendering; do not introduce a black proxy for gray.
Build/lint and full device tests pass for the alpha-lock follow-up.

Current UI revision: `history-controls-v1` (pen pipeline remains native-eraser-v1).
Undo/Redo now appear as arrow icons on the brush toolbar. Settings independently
toggles undo two-finger tap and redo two-finger double tap, with existing gestures
enabled by default and persisted outside artwork. Applying cancels pending history
taps. Manual actions remain enabled by history state; pan/pinch/rotation remain
available. See FEATURES.md, HistoryTapChecks and SettingsChecks.
Build/lint and the full device suite pass for these controls and gesture toggles.

Current experiment: `native-eraser-v1`. User requested committing the current way
first; baseline commit is `388add9`. A white PENCIL preview is now being trialed
for <=30px erasers (document and screen) on exactly one full-opacity unlocked layer
with alpha lock off. Artwork/history still use CLEAR. Other cases retain app preview;
gray/wide paint restrictions remain. The input-mode button compares BOOX/Android.
Physical pen feedback is pending. See NATIVE_PREVIEW_SAFETY.md and NativeEraserChecks.
Build/lint and the full device suite pass. Real SDK opens white at 24px/1x and
returns to black successfully with reader/render flags true. The first fixture
attempt correctly fell back at 1.92x (46px screen width); the test now explicitly
uses 100% to exercise the native branch. This does not validate physical white ink.

Current build: `eraser-toolbar-v1`. Toolbar contains Ink/Tilt/Marker/Eraser; tapping
the selected icon opens its settings. Separate size/settings/shade actions are
removed. Eraser is fixed round, 1–128px, default 32px, clearing selected-layer alpha
with tile undo/redo; hidden/locked/alpha-locked layers are protected. Its live preview
isolates the selected layer and always uses the app renderer. Native compatibility
and reader-enable fixes from shader-safe-v2 remain. Stroke diagnostics are schema 8
with `operation=paint|erase`. See BRUSHES.md for implementation and controls.
Build/lint and the full device suite pass, including before-lift screen visibility,
lower-layer isolation at full/partial opacity, exact undo/redo, alpha-lock protection,
export, preference preservation and first-tap/selected-tap/cancel UI behavior.

User feedback: wide Marker still feels slower after the crash workaround. Documented
as a current limitation at the user's request; further performance work is deferred.
The inspected stroke was black, 100.8px, Android/app preview at 1x. Software snapshot,
surface-lock and pen-up history timings are in PERFORMANCE.md; evidence is saved in
`captures/20261023-151950`. Do not bypass the native crash guard to restore speed.

Current startup follow-up: `shader-safe-v2` explicitly enables the SDK input reader
after paused parameter configuration and before rendering is enabled. This fixes
v1's `created=true input=false render=true` regression. Real SDK open/reopen tests
and the full suite pass; logs confirm all flags true. Gray/wide brushes remain on
app live preview under the crash workaround below.

Latest revision is `shader-safe-v1`: a large light-gray Marker after zoom triggered
a native SurfaceFlinger `updatePainterShader`/Scudo allocator-race abort (not a
confirmed app OOM). Gray/wide brushes now use app-rendered live preview; native is
limited to black/up-to-30px declared footprints. Native configuration is performed
while paused, and fixed-width brushes have no contact-time setter. Android live
snapshots include pending strokes and coalesce to one in flight; pen-up still
commits immediately and displays asynchronously. Full tests pass, including visible
gray ink before pen-up in PixelCopy. See NATIVE_PREVIEW_SAFETY.md. Keep the app
stopped after installing this workaround until the user is ready to test.

Latest brush increment: Marker (former Monoline, stable `monoline` ID) supports 1–128px; new defaults are 32px/constant width and old preferences survive. Eight opaque gray shades are shared across tools via a toolbar swatch and Brush settings, with Apply/Cancel. Native preview requests the same ARGB as the captured bitmap brush. Constant Marker uses a single stroked Path (`round-marker` v1), measured at 1.77ms vs 49.72ms for the prior renderer on 1,000 samples/128px. Preference schema 2 reads v1; stroke logs are schema 7 with captured gray/ARGB/engine metadata. Full device suite and build/lint pass. The accepted `async-handoff-v1` pipeline is preserved. Physical native-gray/large-width appearance is pending; see BRUSHES.md for the older manufacturer color limitation and validation recipe.

**Accepted performance baseline (2026-10-23):** the user physically tested `async-handoff-v1` and reported an **"incredible difference"**, asking that the approach be preserved for future work. The combined background presentation, 450ms quiet gap, stable snapshots and cancellation policy is now accepted. Read AGENTS.md and PERFORMANCE.md before changing input/rendering boundaries. Earlier notes saying physical feedback is pending are superseded by this acceptance; exact end-to-end latency and broad device/brush coverage remain unmeasured.

Current revision: `penPipeline=async-handoff-v1`. User confirmed the 150ms batch policy was much better for very fast writing, but normal-fast cadence could collide with its reset. Final reconciliation now waits 450ms and SurfaceHolder lock/draw/post runs on a worker using immutable snapshots. Pixel/history commits and native width control remain immediate on UI. Native reset still uses UI-only SDK View methods and retries after writing resumes during a worker frame. Epoch invalidation, bounded pending work and session-close flush protect lifecycle/order. Small snapshot UI median was 0.66ms; surface-lock waits moved off UI. Targeted and full device suites pass, including PixelCopy comparisons with documented edge rounding tolerance. See PERFORMANCE.md; real pen feedback is still required.

Current installed experiment is identified by `penPipeline=quiet-handoff-v1` in Launch logs. Real handwriting showed per-stroke native pause/resume remained costly despite partial drawing. `StrokePresentation` now keeps pixel/history commit immediate and waits for a 150ms quiet gap before one combined display/native handoff; new contact cancels it. Session changes publish committed pending pixels. Full tests/build/lint pass; attached-SurfaceView six-stroke benchmark reduced median commit 25.81→3.96ms and total work 150.77→37.34ms (excludes native transitions/quiet wait/pen latency). Physical fast-writing feedback and final appearance still need verification. See PERFORMANCE.md before modifying lifecycle/timers.

Latest lag follow-up: actual handwriting capture 20261023-122730 showed full-canvas presentation and native resume dominating (~50–60ms combined handoff), plus queued short strokes with zero width updates. Upright commits now repaint a conservative dirty rectangle; rotated canvases retain full frames for exact pixel preservation. Native handoff keeps the digitizer reader alive, toggles only rendering and restores black/base width/PENCIL. Begin width is inline on UI to avoid queued end callbacks cancelling it. Full device suite passes, including partial/full frame comparisons and a real SurfaceView benchmark (40.86ms full vs 15.41ms dirty medians, not pen latency). Retest real fast-writing/preview continuity; see PERFORMANCE.md.

Fast-writing performance follow-up: stroke JSON/file work now runs on a bounded background worker; history reuses compression buffers across each transaction, and brush geometry reuses the previous radius. BooxPerf logs break down history, surface, UI queue, diagnostics, SDK resume and live width scheduling/setter time. Final on-device synthetic history/brush medians improved about 15–18%; build/lint, full instrumentation and isolated benchmark pass. Real fast handwriting and panel latency remain to be checked. See PERFORMANCE.md; `dev.ps1 capture` now includes BooxPerf.

Latest increment: the initial brush toolbar is implemented (Ink, Tilt, Monoline, per-preset settings and captured brush commits). Build/lint and the full Go103 instrumentation suite, including BrushChecks, pass. Physical preview/pen-up comparison of the new presets is still pending. See BRUSHES.md for current ownership and restrictions; older research sections below are historical.

Prepared 2026-10-22; updated 2026-10-23. This file distinguishes recommendations from verified device behavior. Windows setup and the initial Kotlin probe now run on the tablet; see `PROBE.md` for current commands and limitations.

## Current resume point

The user physically tested the shared live pressure/tilt width implementation and reported "much better". This is the accepted starting point for the brush engine. Both bitmap geometry and native preview use `PressureBrush.width(sample)`; preview multiplies by captured zoom and sends the result through `LivePreviewWidth`. Firmware PENCIL constant-width mode avoids a second, independent FOUNTAIN pressure curve. Begin queues immediately; move updates coalesce over 2ms with max(0.1px,1%) jitter suppression; stroke epochs/session guards cancel stale work. Rendering remains native and fast, with no reopen/redraw per sample. Build/lint and full device checks pass. [BRUSHES.md](BRUSHES.md) explains the fix, numerical width example, extension points, lifecycle and physical regression checks. Exact native/bitmap cap/smoothing equivalence and measured latency remain unverified.

Tilt input is confirmed in real BOOX schema-5 strokes. The earlier tilt-only bridge delivered moves and width updates but still used a different firmware pressure response; it is superseded by shared full-width calculation. Do not restore the old fountain-only configuration based on older session notes below. The pressure-only PENCIL failure in early development occurred before the app supplied dynamic pressure width.

The user confirmed alpha lock works and requested a commit of the complete layer milestone before moving on. [LAYERS.md](LAYERS.md) now describes the accepted implementation, controls, architecture and regression coverage.

Alpha lock added to Layer settings beside opacity. Shared checked icon, draft Apply/Cancel and one combined undoable metadata action. It preserves existing per-pixel alpha while painting (including soft edges), persists/duplicates/restores, and disables Clear until unlocked. Android pending ink isolates the layer before SRC_ATOP so paper/lower layers do not become its mask. BOOX live foreground preview can temporarily extend beyond the mask; final commits are correctly constrained. Full device suite and build/lint pass. The user confirmed opacity works nicely. An initial new test incorrectly used app-private storage and replaced the disposable current drawing with a blank 32x32 canvas; test isolation is fixed and the user was informed. A normal blank BOOX-size canvas was subsequently created for physical testing. See FEATURES.md for implementation details.

Layer opacity follow-up: long-press a thumbnail for Layer settings, separate from name long-press rename. A monochrome 0-100% slider and percentage label edit a draft; Apply produces one undoable metadata command, Cancel changes nothing. Opacity is nondestructive, duplicated/persisted, and honored by thumbnails/composition/PNG and Android pending group preview. Merge bakes both opacities while preserving appearance and resets retained opacity to 100%; undo restores both layers. Existing v2 checkpoints default missing opacity to 100. Zero opacity still permits painting; locked/hidden layers permit opacity metadata changes. BOOX live fountain ink remains full black until pen-up composition. See [FEATURES.md](FEATURES.md) for extension details. Integrated build/lint and full device instrumentation passed, including group alpha, merge appearance and undo/redo, export/checkpoint compatibility and real thumbnail-modal Apply/Cancel. Tests allow window focus to settle between dialogs. The slider has an explicit black track because the BOOX theme hid its stock track. Latest APK reopened with the modal ready for physical testing; screenshot: layer-opacity.png.

The user physically confirmed the layer UI/settings update works very well. Read [FEATURES.md](FEATURES.md) before changing layers, icons, settings, navigation or preview integration; it maps extension points, invariants, known failed approaches and regression checks.

Layer UI follow-up: the user confirmed the layer engine works well. The panel now defaults to the right; Settings (gear) offers left/right with Apply/Cancel, stored separately from documents. `AppSettings`/`AppSettingsStore` and `SettingsDialog` are the extension points for future preferences. Moving the panel keeps the canvas surface attached and explicitly reconfigures BOOX screen coordinates after layout, even if dimensions do not change. Active rows have a 2dp black border. Long-press a name to rename (trimmed, nonblank, single-line, max 80 characters); rename is an undoable metadata command and is allowed on locked/hidden layers. Shared `EinkIconButton`/`UiIconDrawable` provide native monochrome vector icons, accessible labels/tooltips and checked-state black borders without animation or elevation. Add/duplicate/merge/delete use one compact toolbar row; visibility/lock/up/down share each layer's bottom row. See [e-ink UI conventions and primary-source research](EINK_UI.md). Integrated build/lint and full device instrumentation passed, including real left/right switching and restoration, unchanged canvas identity/width, panel collapse/expand, isolated long-press rename validation/undo, and settings commit/cancel/persistence. Latest APK reopened; `layers.png` updated. The user subsequently reported this build works very well; a systematic ghosting/edge matrix remains unmeasured.


Multi-layer feature implemented; the panel build is installed and opened for physical testing. See `LAYERS.md` for the implementation plan. Stable-ID layers are ordered bottom-to-top; the collapsible panel (right by default, configurable) lists topmost first and supports add/select, lock/visibility, independent duplicate, merge down, delete and up/down reorder. Hide leaves a layers icon; preference survives restart. Count is limited by memory (7 on the BOOX preset, 3 on A4; at most 64 for tiny canvases). Locked/hidden active layers reject pen/Clear and keep BOOX hardware ink closed; navigation/history still work. Destructive delete/merge requires unlocked layers, and merge requires both visible. Pixel history resolves its recorded layer ID; structural history retains metadata and compressed tiles only for changed/inserted/removed layers. Undo restores selection and pixels. Composite caching with dirty regions keeps navigation fast; thumbnails read source bitmaps without allocating copies. Internal checkpoint v2 saves layer PNGs, names/order/flags/selection and reads v1/legacy PNG. Public save/open/sharing stays deferred. Full layer/device/UI tests pass; maximum BOOX-layer test used ~163MiB native heap, cold composite 104ms and cached full-document draw 8.8ms. After USB reconnection on 2026-10-23, the final malformed-viewport/duplicate-ID checkpoint checks and the full device/UI suite passed. The latest APK was installed and reopened. Screenshot: `layers.png`. BOOX raw input was verified enabled at panel-adjusted origin 412,552 (view 2068x1182). Hardware live ink remains a temporary foreground preview above layer content until pen-up; final composition is correct.

After opening the fallback build, live logs from the running app confirmed two-finger tap recognition and actual `Undo: Stroke` / `Redo: Stroke` operations (21:59:20–21:59:30). A double tap produced Redo directly. This confirms input delivery and action execution on the tablet; perceived responsiveness/ghosting still depend on user feedback.

History gesture fallback: the user could not activate three-finger undo. The touch driver advertises 32 multitouch slots, so no hardware three-touch limit was established. Switched to two-finger single/double taps, loosened join/total timing to 250/450ms, and added `History touch`/accepted/rejected/cancelled logs. The recognizer observes the same events as CanvasGestures without consuming downs/moves; it consumes only a completed valid tap. Navigation begin cancels tap state/timers. Combined virtual-time tests now run the exact tap-before-navigation arbitration for stationary taps, redo, pan, pinch and a small twist below per-finger touch slop. All device checks and build/lint pass; app opened for physical testing. Draw a fresh stroke after update/restart because session history is not persisted.

Initial session history is implemented. The user reported three-finger taps did not work; the current fallback uses two-finger taps and still needs physical confirmation. Up to 10 raster edit actions (strokes and Clear), capped at min(32MiB, heap/8) compressed storage. `DocumentEdit` declares affected regions and applies edits; `EditHistory` captures lossless before/after 128px tiles, storing only changed tiles. This also supports future fill/cut raster mutations without replaying tools. New edits discard redo; no-ops do not. Navigation is excluded. New canvas/process restart resets the stack; pause-time checkpoint saves current pixels but not history. Undo/Redo toolbar buttons use the same guarded BOOX close/render/reopen lifecycle as navigation. Two-finger single tap waits the platform double-tap timeout (~300ms) before undo; two consecutive valid chords redo directly without intermediate undo. Chord join is <=250ms, total duration <=450ms, no motion beyond touch slop; pen/navigation, extra contacts and lifecycle changes cancel candidates/timers. `HistoryChecks` and virtual-time `HistoryTapChecks` pass alongside all existing device checks; build/lint pass. User confirmed the previous pinch implementation works. Keep testing physical responsiveness, gesture recognition and pressure preview after history changes.

Deferred pinch zoom is implemented; physical validation is pending. Levels 1–7 are 10%, 25%, 50%, 100%, 150%, 250%, 400%. `ZoomLevels` selects the closest absolute scale by logarithmic distance from starting zoom times finger-span ratio. Pinch requires 15% distance change and touch slop, with a starting span of at least 48dp. The first deliberate pinch/twist locks its selection mode; panning can precede either. Shared `GestureIndicator` displays the zoom level/percentage upright or the rotation target as a rotated badge. During either selection the document stays still; normal release applies once, cancellation/focus loss/third contact discards. Zoom is anchored at the midpoint where selection started and preserves angle; bounds can limit anchoring. BOOX raw input is suspended once per session and restored afterward, preserving its responsive fountain preview. Build/lint and device tests pass, including all existing rotation/snap/pan checks plus zoom levels/limits, deferred release, rotated anchoring, mode ownership, cancellation, pen priority, post-zoom pen coordinates, unchanged pixels and persistence. No new dependency or zero/reset snap for zoom.

Upright snap added to deferred twist selection: enter 0 degrees when the unrounded normalized target is within +/-4 degrees, stay snapped until outside +/-7 degrees, and commit exactly the displayed 0 on release. Snap state resets on gesture begin/end/cancel. Toolbar exact-angle entry remains unsnapped. Tests cover approach from both directions, jitter/release, cancellation/session isolation and bearing wraparound. Physical confirmation of snap feel is pending.

Two-finger twist selection is implemented and the user confirmed it works well. A twist of at least 5 degrees (contacts at least 48dp apart) takes over from panning, freezes document display updates, and shows a whole-degree target angle. Lifting either finger applies that angle once; cancellation/focus loss/third contact discards it. The latest UX change rotates the entire badge, including text and background, to preview final paper orientation; its fixed 280dp square refresh area contains the 240x96dp badge at every angle. Physical confirmation of this badge improvement is pending. `GestureIndicator` is a separate transparent overlay above SurfaceView, coalesces digit updates to about 30Hz, and requests per-view DU refresh through the adapter with ordinary Android refresh as fallback. Document pixels and export orientation never rotate. Build/lint and device tests passed for gesture selection; badge update build/lint pass. No latency equivalence with BOOX pen ink is claimed.

Two-finger panning is implemented; physical validation is pending. `CanvasGestures` separates navigation recognition from pen input and viewport math, using Android MotionEvent and ViewConfiguration without a dependency. It tracks a stable finger pair and a midpoint movement threshold, then suspends BOOX raw input for the gesture and reconfigures it once on completion. One finger never pans or paints. Pen strokes take priority; lifting either finger, a third contact, cancellation, focus loss or surface changes end navigation. Lift navigation contacts before starting a fresh pen stroke. Translation preserves angle/scale, is bounded to keep paper reachable, works even below screen size, and persists in existing viewport metadata; Fit recenters. Automated coverage includes pointer-index changes, BOOX-mode routing, cancellation/focus loss, extra contacts, pen priority, rotated post-pan pen mapping and restore. Deferred two-finger rotation and pinch zoom are implemented (see above); inertia remains deferred.

Rotation regression follow-up: user reported angled strokes disappearing on zoom. Captured logs showed hardware preview without raw begin/end or stroke commits at nonzero angles. Separating polygon masks from raw input restored recording, but the user reported loss of live preview. The latest adapter removes the large stepped exclusion masks entirely, using the paper's bounding rectangle and an off-view clearing exclusion for both raw input and preview. Raw strokes starting outside document bounds are rejected; bitmap clipping handles edge crossings. Hardware preview may temporarily spill across angled paper edges until pen-up. Exact polygon preview clipping remains unresolved. User physically confirmed the latest change works and accepted temporary preview spill across angled paper edges. Preserve both live preview and recording in further fixes.

The prototype is installed and working. User confirmed BOOX pen mode is responsive, pen-only canvas/finger-operated UI behavior is acceptable for now, live pressure sensitivity works, and button zoom/pen alignment seems to work well. The first planned milestone now includes a fixed-size single-layer document, canvas-size selector, and button zoom. Build/lint and expanded device instrumentation pass.

For day-to-day development use `PROBE.md`, including its code map and SDK initialization requirements. The roadmap is [../plan.md](../plan.md): document/viewport separation, finger navigation and initial bounded undo/redo are implemented; the pen system, layers, durable save/open and toolbar customization remain later work. Replay and timing instrumentation remain follow-up work. Detailed UI design is deferred. Exact hardware-preview/bitmap shape equivalence, a systematic physical orientation/edge test matrix, and ghosting remain open checks; document cropping on view resize has been fixed and tested.

## User objective and accepted decisions

- Build an Android drawing app for a BOOX Go 10.3 tablet.
- Use raster/pixel drawing rather than SVG. This means bitmap artwork, not necessarily a pixel-art-only editor.
- Eventually support layers, multiple pens, undo/redo, saving, and other drawing features.
- Prioritize an easy developer loop and sufficient device context for the coding agent to inspect behavior and improve it.
- User accepted native Kotlin, a custom drawing surface, BOOX pen integration, ADB, scripted diagnostics, and stroke replay.
- Windows setup is complete. Current scope is the tiny Kotlin pen compatibility probe; full drawing-app features remain deferred.
- After identifying WSL, we agreed to move development directly to Windows. No Android implementation exists to migrate.

## Why Kotlin

The recommendation is native Kotlin with a custom Android drawing View and a bitmap engine. Keep BOOX-specific pen/refresh integration behind an adapter so device-specific behavior is isolated.

Raster persistence does not by itself ensure responsive e-ink pen feedback. Investigate the BOOX hardware pen path separately from bitmap rendering.

Expo remains feasible using a custom Kotlin native view/module and a custom development build. Expo Go cannot contain arbitrary BOOX native integration. Interface-only JavaScript changes can benefit from Fast Refresh, but native engine changes still require rebuilding. For this single-platform, device-specific project, maintaining Android plus Expo/React Native would add complexity. If revisited, keep stylus processing and bitmap updates native; expose high-level controls to JavaScript.

Sources:
- https://docs.expo.dev/develop/development-builds/introduction/
- https://docs.expo.dev/modules/native-view-tutorial/

## Reference repositories

### Boox-EinkDraw

https://github.com/steffest/Boox-EinkDraw

Its documented approach combines BOOX hardware stroke preview with persistent bitmap rendering. It supports layers and several hardware brush styles. The README identifies Note Air4C as the tested target, describes zoom-related preview mismatch, and warns that a bundled native pen library depends on device/firmware compatibility. Treat it as a reference, not proof of Go 10.3 support. Verify licensing and library compatibility before copying code or binaries.

### Official Onyx demo

https://github.com/onyx-intl/OnyxAndroidDemo

The official SDK examples cover TouchHelper drawing and e-ink refresh control. Use these as the starting point for a small compatibility probe on the actual tablet. Its documentation is not a guarantee that every example or SDK version fits the installed firmware. Resolve and pin dependencies after checking the device and a build.

The e-ink UI guidance recommends grayscale, large controls, and limited animation:
https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Eink-Develop-Guide.md

BOOX product reference:
https://shop.boox.com/products/go103

## Windows setup decision

Use a Windows-local project folder, Windows Android Studio and SDK, and Windows ADB. Open the editor/agent in Windows rather than a WSL remote session. Avoid splitting initial builds and device control between operating systems.

WSL can access USB through usbipd-win, but that adds forwarding and connection management. It was not configured:
https://learn.microsoft.com/en-us/windows/wsl/connect-usb

Android Studio installation:
https://developer.android.com/studio/install

Device setup and possible Windows OEM driver requirement:
https://developer.android.com/studio/run/device

Do not reuse the earlier Ubuntu apt instructions in Windows. No system packages were installed in the previous session.

## First milestone: Windows toolchain and USB

1. Check existing Windows Android Studio, SDK Platform Tools, Java, and ADB before installing duplicates.
2. Install Android Studio for Windows if absent. Complete its setup wizard with Android SDK and Platform Tools. An emulator is optional for this first physical-device milestone.
3. On BOOX, the documented path is Home > Apps > top-right hamburger menu > App Management > USB Debug Mode. Firmware may move this setting. If absent, inspect available settings rather than assuming ordinary Android menu paths.
4. Connect using a data-capable USB cable; keep the tablet unlocked.
5. Run Windows ADB: `adb devices -l`. If ADB is not on PATH, locate it under the SDK's `platform-tools` folder. The usual SDK location is `%LOCALAPPDATA%\Android\Sdk`, but verify it.
6. Accept the tablet's USB debugging authorization dialog. Select always allow for this development computer if desired. Run the command again; the status should be `device`.
7. If `unauthorized`, inspect the tablet dialog. If no device is listed, inspect cable, USB port, Windows Device Manager, and any needed ADB driver. Do not assume that a charging cable carries data.

BOOX-specific USB setting source:
https://booxsupport.zendesk.com/hc/en-us/articles/8569441562004-App-Management-Settings

ADB documentation:
https://developer.android.com/tools/adb

## Collect context once connected

Record only relevant device properties: model, Android release/API level, build/firmware identifier, CPU ABI, display size, and density. Example ADB commands:

```text
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.product.cpu.abilist
adb shell wm size
adb shell wm density
```

Try a screenshot via a temporary device file, then pull it. This avoids binary-redirection differences between PowerShell versions:

```text
adb shell screencap -p /sdcard/boox-art-setup.png
adb pull /sdcard/boox-art-setup.png .
adb shell rm /sdcard/boox-art-setup.png
```

Inspect the actual screenshot. Device access is not verified until commands succeed. If multiple devices are connected, select the intended serial explicitly.

## Observation and debugging strategy

- ADB for installation, launch, shell commands, screenshots, logs, and artifact transfer.
- scrcpy for desktop mirroring/control: https://github.com/Genymobile/scrcpy
- UI Automator for toolbar/dialog smoke tests: https://developer.android.com/training/testing/other-components/ui-automator
- Debug instrumentation should capture brush settings, layer state, stroke samples, timestamps, render timings, app build, and device/firmware context together.
- Recorded strokes should be replayable into the same bitmap engine for repeatable visual and rendering checks.
- Screenshots/mirroring cannot establish physical e-ink latency or ghosting. Whether hardware preview appears in captures must be checked. Real stylus tests and occasional external camera video are still needed. Synthetic replay does not test the digitizer or palm rejection.

Current PowerShell command interface:

| Command | Purpose |
| --- | --- |
| `.\dev.ps1 doctor` | Java, ADB, connected devices, installed SDK platforms/build tools |
| `.\dev.ps1 build` | Build debug APK and run Android lint |
| `.\dev.ps1 run` | Checkpoint a running app, build, install, launch |
| `.\dev.ps1 capture` | Screenshot, relevant logs, stroke samples, selected device properties |
| `.\dev.ps1 test` | Checkpoint a running app, build/install app and tests, run device instrumentation |

Replay, rendering-time instrumentation, app-state snapshots, and richer capability reports remain planned. There is no `replay` command yet. `PROBE.md` documents the actual output and limits of each implemented command.

## Later implementation sequence

1. Tiny compatibility probe: one surface, one pen, pressure logging, bitmap commit, refresh control, PNG export. Validate stroke handoff/alignment, palm behavior, suspend/resume, and whether device-specific binaries are necessary.
2. Repeatable capture/replay and timing instrumentation.
3. Separate BOOX adapter from bitmap engine; add layers, undo/redo, persistence. Favor changed-region updates; consider tiles as document size/memory requirements justify them.
4. Expand brushes and zoom after preview-to-bitmap handoff is reliable. Hardware preview styles may not match arbitrary custom brushes.

## State at handoff

### Arbitrary-angle view rotation follow-up 2026-10-22

- User clarified rotation is for the drawing view, not permanent document rotation, and requested arbitrary angles. Implemented ±15° controls, an exact/fractional angle dialog, and reset. Final UI and finger-rotation gestures are deferred.
- Before editing, initialized local Git and committed the working document/zoom baseline as `8d85b0f`, tagged `baseline-before-view-rotation`. Author identity was provided by the user and set only for this repository. No remote was configured or pushed. Rotation work is separate from that baseline.
- `ViewTransform` now has forward/inverse rotation and a shared display matrix. Viewport Fit/clamping uses rotated bounds; Android hit testing uses the actual paper polygon. Both pen paths commit inverse-mapped document coordinates. Exports retain dimensions and pixel orientation.
- View angle is additive viewport metadata in archive version 1; older checkpoints default to 0°. Stroke schema 4 includes angle; schema 3 remains unrotated document coordinates. `document-state.json` also reports angle.
- BOOX supports rectangular region exclusions. Rasterize the polygon into a Region and exclude pixel rectangles outside it from both raw input and firmware preview. At 15° there were 1570 exclusions and ~946 ms setup; smooth interactive rotation performance is not solved. Returning upright sends an off-view sentinel exclusion because SDK calls with empty lists do not clear native/firmware state.
- Input is disabled during viewport transitions. Pen reopening is deferred until heading/control changes have had a display frame, to avoid stale zoom/angle labels while the canvas updates.
- Device tests passed arbitrary/fractional/negative angles, forward/inverse and Canvas-matrix agreement, Fit bounds, clipping inclusion/exclusion samples, center preservation, invalid-angle rejection, rotated Android pen mapping, gray-margin rejection, rotation refusal during a stroke, angle persistence, and unchanged PNG pixel orientation. Existing document/pressure/input checks also passed.
- Initial rotation build installed and opened at 15° with BOOX input enabled. User has been asked to physically check nib alignment, pen-up handoff, angled-edge clipping, exact angle, and reset. Do not mark this hardware behavior confirmed until feedback arrives.
- Final rotation build/lint, expanded device tests, installation, and launch passed after the UI-refresh ordering fix. Latest capture: `captures/20261022-175421/`; screenshot (screenshot omitted). Heading correctly shows 15° and the fitted zoom. BOOX mask setup measured 923 ms; physical validation is still pending. Rotation changes are not part of the pre-rotation baseline commit.

### Document and button-zoom milestone delivered 2026-10-22

- `DrawingDocument.kt`: fixed dimensions, one transparent raster layer, stable document/layer IDs, canvas presets and limits, flattened PNG export, atomic single-document archive. Old `canvas.png` checkpoints migrate at their actual pixel dimensions and remain untouched.
- `DocumentViewport.kt`: document/view transforms, centered zoom, Fit and 100%, view resize preservation, bounded scale, and visible paper region. Brush sizes are document pixels; hardware preview width is multiplied by zoom.
- `CanvasSizeDialog.kt`: BOOX 1860×2480, square 2048×2048, A5/A4 at a 300 ppi reference, landscape 2560×1440, custom dimensions, orientation swap, and memory estimate. New canvas requires discard confirmation or PNG export first. Current drawing is disposable for development per user instruction.
- Measured Go103 Java heap limit: 256 MiB. Initial custom limit: 12 million pixels, maximum 8192 on either side; a pixel/working-buffer budget is also computed from the runtime heap. Every preset allocated and exported successfully on the tablet; A4 instrumentation reported roughly 91.5 MB native heap at export completion.
- `document.boox` is an atomic ZIP checkpoint containing `document.json` and `layer.png`, with IDs, dimensions, optional print-reference ppi, and viewport center/scale/fit. It is the current single-document recovery mechanism; there is no multi-document save/open UI yet. PNG export uses full document dimensions, not viewport size. PNG does not currently embed print-resolution metadata.
- Stroke schema 3 stores document-coordinate samples, document/layer IDs, view dimensions, and viewport transform. Schema 2 samples used view coordinates; schema 1 also used raw pressure. Do not mix these formats during future replay work.
- Capture now also includes `document-state.json` with latest active document/viewport configuration, input mode, heap limit, and canvas limit.
- Device checks passed presets/exports, size limits, legacy migration, viewport transform round-trips, tiny-canvas zoom bounds, pixel preservation across view resizing, checkpoint identity/ppi/viewport, corrupt-checkpoint preservation, and actual selector validation, in addition to existing pressure/palm/cancellation checks.
- Installed and physically tested: user reports zoom/pen alignment seems to work well. Initial screenshot showed correctly committed ink on a fitted document. The functional toolbar has New canvas, −, +, Fit, and 100%; final UI is deferred. Pinch/pan is not enabled.
- This firmware again reported the probe package as disabled after replacement (`enabled=3`). `dev.ps1 run/test` now explicitly enables only `art.boox.probe` for user 0 after installation. Device tests and subsequent launch succeed.
- Final build/lint, device tests (including selector UI validation), install, launch, and capture completed. Latest capture: `captures/20261022-173858/`; screenshot saved to (screenshot omitted). App left in BOOX mode with the current 1860×2480 document fitted to the view. No finger pan/pinch yet.

### Kotlin probe implemented 2026-10-22

Pressure/input follow-up:

- User reports the input/palm behavior seems okay for now. They then observed fixed-width live BOOX ink, with pressure-dependent width appearing only after pen-up. Inspection of the pinned SDK bytecode identified the cause: `SFTouchRender.openDrawing()` resets the style to pencil, overriding our earlier fountain-style setting.
- Fixed the ordering: `resumePreview()` now enables input, then applies color, width, and fountain style. It is called after opening and after each bitmap handoff. Build/lint passed; installed and launched successfully with `Hardware preview: fountain width=12.0` in logs. User physically retested and confirmed live pressure sensitivity now works as expected. Exact preview-to-bitmap brush-shape equivalence has not been measured.

- User explicitly wants pen-only canvas input, with finger taps retained for buttons. Android filters every accepted pointer by stylus tool type and tracks pointer IDs across mixed palm/pen events. BOOX explicitly selects the raw digitizer/SF renderer and excludes AppTouchRender's finger drawing path; it does not disable the touchscreen globally.
- Added shared pressure normalization and a tapered round bitmap brush with linear width 1–12 pixels. BOOX firmware reports maximum 4095. Android queries its pressure motion range, with a 0–1 fallback. Status shows percentages; schema 2 logs include normalized and raw samples, axis range provenance, and brush parameters. Schema 1 log entries retain their previous raw units and four-element sample format.
- BOOX hardware preview now uses fountain style at width 12. Its firmware algorithm may differ from the shared bitmap brush; physical handoff shape/alignment still needs assessment.
- Added atomic pause-time checkpoints, cancellation of pending Android strokes on focus/lifecycle/mode changes, generation checks against stale BOOX callbacks, and explicit surface teardown handling. Fixed status height prevents stroke statistics from resizing the canvas. `dev.ps1 run/test` visits Home before replacing a running app to checkpoint the drawing.
- Build and lint passed. Expanded on-device instrumentation passed pressure normalization/clamping, pixel-width differences, PNG, edge clipping, mixed palm+pen pointer changes, non-pen rejection, cancellation, pause/mode interruptions, and checkpoint restoration.
- ADB Home/return and sleep/wake exercised pause/resume. Checkpoint hashes matched across the initial cycle; logs show raw input reopened with updated region/origin. BOOX drawing continued afterward with normalized pressure reaching 100%, and sampled strokes had zero out-of-bounds points. Screenshots confirm variable-width committed ink. These automated checks do not establish physical palm behavior or edge preview alignment.
- Pressure follow-up capture: `captures/20261022-165751/` (ignored by Git), with a saved screenshot at (screenshot omitted). User subsequently said input/palm behavior seems okay for now and confirmed the live-pressure fix. Systematic near-edge alignment and exact preview/bitmap shape comparison are still open.

- Initial project created: native Kotlin activity, raster SurfaceView, separate BOOX adapter, fixed-width black pen (since replaced by the pressure brush above), pressure/sample logs, refresh, clear confirmation, PNG export through MediaStore, and pause-time bitmap checkpoint.
- Build pins: AGP 9.2.1, Gradle 9.4.1, compile SDK 37, target SDK 32 (Android 12), min SDK 29. Installed JBR 25 builds it successfully. Full drawing-app features are not implemented.
- `dev.ps1` provides doctor, build/lint, run, test, and capture commands; see `PROBE.md`.
- APK installed and launched on the Go103. Device instrumentation passed bitmap pixel/PNG/clear/finger-rejection checks. These do not test physical pen behavior or e-ink latency.
- User verified Android pen mode draws but has noticeable delay. Real MotionEvents with varying pressure were recorded. Exported PNG was pulled and visually verified to contain the strokes.
- Initial BOOX mode reported input enabled but delivered no strokes. Logs showed blocked firmware reflection and empty mapped regions. The official demo's Application initialization was missing; `ProbeApplication` now initializes ResManager/RxBaseAction and HiddenApiBypass 4.3 before SDK use. After reinstall, hidden-API denial and empty-region errors were absent. Five physical BOOX strokes were recorded and committed, and the screenshot shows the resulting artwork. User confirms BOOX mode works well and feels much snappier than Android pen mode.
- Pressure changes in both paths. Initial captured BOOX values reached 4095; Android MotionEvent samples were approximately 0–1. User observed the different scales; the normalization follow-up above now maps both to 0–1 and applies variable width.
- The SDK still probes optional firmware classes (`android.onyx.handwriting.PenConfig`, `PenButton`, `StrokeConfig`) that are absent on this firmware. This alone does not establish incompatibility; investigate based on actual callbacks/ink.
- SDK initialization success is not proof of working hardware input. Do not mark pen integration verified without actual BOOX callback strokes and a physical preview test.
- (screenshot omitted) is a probe screenshot; its current drawing/status may precede the most recent rebuild. UI Automator returned a null root on the first attempt.
- Core probe milestone is working: build/install, Android and BOOX drawing, pressure-sensitive live preview and bitmap commit, pressure logs, and PNG export. Next: recorded-stroke replay and timing instrumentation; retain the physical alignment/ghosting checklist while developing further. Palm/input behavior is provisionally accepted by the user. Refresh control is implemented but its physical ghosting behavior still needs assessment.

### Windows setup verified 2026-10-22

- Android Studio is installed at `C:\Program Files\Android\Android Studio` (build `AI-262.9437.185.2621.16467767`). Its bundled OpenJDK 25.0.3 successfully runs this project's Gradle 9.4.1 / AGP 9.2.1 build.
- SDK location: `C:\Users\YOUR_NAME\AppData\Local\Android\Sdk`. Installed platform: `android-37.0`; Build Tools: `36.0.0`.
- Windows ADB runs from `platform-tools\adb.exe`, version `37.0.1-15733141`. Java and ADB were not on the agent process PATH; use absolute paths for now.
- USB debugging is authorized: `adb devices -l` reports one `Go103` device with status `device`. Use an explicit device serial when invoking ADB, especially if another device is connected later.
- Verified model: `Go103`; Android 12, API 32; firmware/build `2026-05-19_22-31_4.2-rel_0519_c76f35ce8`.
- CPU ABIs: `arm64-v8a,armeabi-v7a,armeabi`; physical display: 1860 x 2480; density: 300 dpi.
- Windows setup is complete. The Kotlin probe is described above; BOOX hardware input and live pressure response have now been physically validated.

### Historical state before Windows setup

- Original workspace: `/home/YOUR_NAME/Workspace/boox-art` in WSL.
- No reference repository was cloned; no Android project, SDK, ADB, or scrcpy was installed by this session.
- No tablet connection, firmware, screenshot, native SDK compatibility, or build result has been verified.
- Original workspace initially contained only environment-managed hidden directories. Copy only README.md and docs; do not copy those hidden directories into Windows.
- Attempt to create `/mnt/c/Users/YOUR_NAME/Projects/boox-art` failed because the Windows mount is read-only in this agent session. The user must perform the final copy from a Windows terminal.
# Brush toolbar increment — 2026-10-23

Implemented initial Ink/Tilt/Monoline toolbar with persistent per-preset size/pressure/tilt settings and Apply/Cancel dialogs. Existing Tilt remains the default; opaque round native-width preview is retained. Brush snapshots now travel through Android preview/commit and BOOX session/list commit; schema-6 logging reads the captured brush. Toolbar changes reject contact-to-handoff activity. Size remains bounded to 1–12 document pixels, at most 30 with tilt. Textured/soft/opacity brushes, erasing, public preset import/export and toolbar customization are deferred. See BRUSHES.md and BRUSH_ENGINE_PLAN.md. Automated checks and physical pen verification are separate: new presets still need before/after pen-up comparison on the tablet.
