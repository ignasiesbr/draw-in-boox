# Native pen renderer crash workaround

2026-10-23, safety revision `shader-safe-v2`, retained in `penPipeline=eraser-toolbar-v1`.
Erasers always use app preview, independently of size or stored ink shade.

## Native eraser investigation (2026-10-23)

The pinned 1.5.4.4 TouchHelper exposes `setEraserRawDrawingEnabled(boolean, int)`
and raw erasing callbacks. Bytecode forwards the former to Device; these APIs
alone do not establish layer-aware live pixel removal. The app currently ignores
physical eraser callbacks; adding those is separate from accelerating the selected
toolbar eraser's preview.

Onyx's [moving-eraser example](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/scribble/ui/ScribbleMoveEraserDemoActivity.java)
disables native rendering on erase begin, clears a transparent bitmap with CLEAR,
and redraws bitmap/background during moves. Its [EraseRenderer](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/render/EraseRenderer.java)
also renders content in-app, with optional erase-circle/track feedback. These are
evidence for app-managed erasing, not proof that all firmware lacks native erasing.

Native white PENCIL feedback is a possible optimization when the erased result is
white paper. It would hide lower-layer content until reconciliation in general
layered artwork, and would bypass alpha lock unless gated. White is mentioned in
the older official Scribble API, but this app has physically accepted only black/
narrow native feedback. White/wide stroke fidelity and stability are unverified.
Do not silently paint white into artwork, claim native erase-channel APIs reveal
our layers, or expand the crash guard on the basis of SDK method names alone.

Startup follow-up: v1 omitted the explicit reader-enable call after opening SDK
resources, producing `created=true input=false render=true`. v2 configures parameters
while paused, calls `setRawInputReaderEnable(true)`, then enables rendering last.
`openRawDrawing()` alone does not set TouchHelper's input-enabled flag. A real
attached-surface SDK open/close/zoom/reopen test now asserts `adapter.open()` succeeds
and `surface.rawInput` is true, supplementing the mocked ordering test. Full device
tests pass and startup logs confirm all three flags true. Do not infer native reader
readiness from app-level `inputEnabled` or renderer state alone.

## Evidence

Saved incident: `captures/crash-20261023-143728/crash.txt` and `logcat.txt`.
At 14:36:30 SurfaceFlinger's `touchConsumer` thread aborted with
`Scudo ERROR: race on chunk header`. The native stack includes
`PainterBase::updatePainterShader`, `PainterBase::moveTo` and `PenManager::moveTo`.
This is a system-process allocator race, not evidence of a Java app OOM. An app
exception handler cannot catch another process's SIGABRT.

The selected brush was a constant-width 100.8px Marker, light gray `#CCCCCC`.
Pinch zoom changed scale from 1 to 0.5, configuring a 50.4px native width.
About 1.27 seconds later, the first pen-down callback ran. The app's redundant
initial width setter immediately preceded the native abort. The SDK forwards
setStrokeWidth to both its native reader and EpdController. This makes contact-time
shader mutation a plausible trigger, not a proven complete explanation of the
firmware race. Earlier successfully drawn gray strokes did not establish safety.

## Application workaround

- `NativePreviewPolicy` allows opaque black brushes with a declared maximum
  rendered footprint up to 30 document pixels. This is the previously accepted
  compatibility envelope, not a published firmware width limit.
- Every gray brush and wider brush uses Android stylus capture and app-rendered
  live ink. The policy runs before creating TouchHelper or opening native input.
  Marker still supports 128px and all eight shades; they are not sent to the
  unverified native gray/wide shader path.
- `NativePreviewConfiguration` pauses drawing, sets color/width/PENCIL, explicitly
  enables the reader, then enables drawing last. This happens after SDK open, which initializes defaults.
  It supersedes the older enable-then-configure sequence.
- `LivePreviewWidth.forBrush` returns no updater for a constant-width brush.
  Its width is set during paused configuration, not redundantly at pen-down.
  Supported dynamic black brushes retain shared pressure/tilt widths and 2ms
  coalescing. Do not remove their dynamic width response to fix a constant brush.
- The app renderer coalesces live moves to one snapshot in flight and paints via
  the existing background surface worker. Full snapshots include pending strokes
  at their correct layer position, with group opacity and alpha lock. Pixel/history
  commit remains immediate on pen-up and its final screen update is asynchronous.

The toolbar input mode shows Android pen for app-rendered brushes, with status
`App preview · gray or wide brush`. Diagnostics include actual preview source and
`nativePreviewSupported`. No preferences, colors, widths or artwork are discarded.
The accepted native/background/450ms path remains in use for compatible black ink.

## Verification and limits

`NativePreviewSafetyChecks` covers the compatibility matrix, paused configuration
order/failure behavior, no fixed-width contact updater, retained dynamic pressure/
tilt response, and gray 100.8px drawing through the app path at 0.5 zoom/-22 degrees.
`AsyncSurfaceChecks` additionally injects a gray/wide stylus stroke into an attached
surface and uses PixelCopy to verify it is visible before pen-up, while layer pixels
and undo remain uncommitted until lift. The full device suite passes.

Injected MotionEvents do not exercise the vendor's raw digitizer touchConsumer.
Do not intentionally re-enable gray/wide native rendering to reproduce a crash
that restarts the display service. The actual firmware race requires a vendor fix;
the application workaround avoids the observed unverified path. Real-pen testing
of app-preview responsiveness and the original shade/size/zoom case is still needed.
Software grayscale/wide preview may feel different from native black ink.
