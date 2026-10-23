# Editable BOOX native renderer trial

User physical feedback after trying commit `f4bde6c`: "its good". This is positive
validation of the Fountain trial's feel/result at the tested configuration. Exact
native/raster pixel equivalence, other native types, and arbitrary custom live
brush rendering remain unverified. Preserve this behavior when extending the trial.

2026-10-23. Branch `experiment/recovered-boox-native`. Working app checkpoint:
`29c7373`; saved APK `.tools/baselines/29c7373/app-debug.apk`. The independent
official-SDK fallback guide is `BOOX_BUILTIN_BRUSHES_FALLBACK.md` (commit `688ac5f`).

## What is actually being tested

The Lab app uses the official pen SDK 1.5.4.4, its Java integration and input reader.
It substitutes only `onyxsdk-pennative:1.0.5`'s app-side `libneopen_jni.so` with a
source-built Rust implementation. The original pennative Java bindings are retained.
The Rust file is vendored in `experimental/boox-renderer/src/lib.rs`, from upstream
commit `c750d5e5ea30553063962f7bf31909e6d3d6f8ff`, with its LGPL/GPL license files.
The current Rust algorithms are unchanged from that snapshot; they are now editable
and built from this repository. Normal builds still resolve official binaries.

This is deliberately not a blind whole-SDK replacement. Inspection found that the
upstream recovered raw reader declares nativeRawReader/nativeRawClose with session
arguments, while our official RawInputReader declares no-argument JNI methods. It
also expects an onRawInputDeviceInfo callback absent in the pinned class. Replacing
that .so alone would violate the JNI contract. Leave the accepted reader in place.

The trial adds a Lab-only Fountain preset. BOOX FOUNTAIN supplies live feedback;
BooxFountainGeometry feeds normalized document-space samples into the modern
NeoPenNative API (type 2), consumes only real ink, and paints its size-coded points
with the SDK's PenUtils. No prediction enters artwork/history. Vendor pressure is
the only width owner for this preset; LivePreviewWidth does not double-apply it.
The existing Ink/Tilt/Marker/Eraser behavior and firmware crash guards remain.

Native handles are destroyed in finally blocks. Geometry is prepared before history
capture and cached for drawing; generated segment widths/caps determine affected
tiles and dirty bounds. This matters because current-point width applies at both
endpoints. The source bounds and output count are checked. Lab renderer failures
report an error, retain artwork and reconcile transient feedback. UI-thread bitmap/
history ownership, worker snapshots and 450ms quiet-gap handoff remain unchanged.

## Firmware boundary

The editable Rust .so computes app-side geometry. It does not replace BOOX's
SurfaceFlinger painter, shaders or firmware. Its modern API's repaint argument is
not a custom-geometry upload path to that service in this implementation. Native
FOUNTAIN feedback is still firmware-owned. Changing Rust pressure/shape mathematics
can therefore change the final result without changing the firmware's live stroke.
Do not claim arbitrary custom brushes gain hardware preview automatically.

The frozen 100-sample comparison already differs: official library painted 2,487
pixels, source renderer 3,671 at the initial configuration. This is an offline
fixture result, not native preview measurement. Both APIs passed replay/dot/history
checks, but pixel-equivalent behavior is not established. Real pen comparison is
the purpose of this trial. Source modern type-2 rendering selects its configurable
renderer; its behavior differs from legacy recovered-value defaults.

## Rebuild and compare on Windows

Workspace-local build tools used here:

- Rust 1.96.1, x86_64-pc-windows-gnu, under `.tools/rustup` and `.tools/cargo`;
  existing `C:/mingw64/bin/gcc.exe` provides host linking. No global PATH change.
- Android NDK r28c under `.tools/android-ndk-r28c`; Windows archive SHA1
  `086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d`, verified against Google's repository.
- Rust target aarch64-linux-android, NDK API 29, arm64-only APK like the baseline.
- Cargo.lock pins the JNI crate and remaining dependencies. Output/binaries are
  ignored; the source, lock, provenance and licenses are tracked.

```powershell
# Build editable native code and run its 23 pure Rust tests.
.\scripts\build-recovered-renderer.ps1 -Test

# Build/lint, run the full app device suite, install/open the source renderer Lab.
.\dev.ps1 build -Renderer recovered
.\dev.ps1 test -Renderer recovered
.\dev.ps1 run -Renderer recovered

# Same Lab preset and Java adapter, original BOOX renderer for comparison.
.\dev.ps1 test -Lab -FountainOnly
.\dev.ps1 run -Lab

# Original app and official SDK; no Lab preset or shared Lab storage.
.\dev.ps1 build
```

Lab package: `art.boox.probe.lab`, with its own preferences, document and history.
Launcher label identifies `BOOX Art Lab (recovered)` or `(official)`. Switching Lab
backends replaces only this Lab package and preserves its drawing; the regular app
is separate. `dev.ps1` checkpoints the target app before replacement. Test APKs and
instrumentation target the correct package. Launch logs record renderer and lab.

`scripts/build-recovered-renderer.ps1` extracts the resource-free Java bindings
from the pinned original AAR, builds Rust, and supplies JNI/assets directly. A
repacked local AAR hit AGP resource-transform failures; no Gradle cache files were
patched. The original SDK input reader and other native dependencies stay official.

## Evidence and physical test

Rust host tests: 23 passed. Source Lab full device suite passed after final
footprint/recovery refinements. Official Lab
targeted Fountain checks passed. Normal official app and source Lab build/lint pass.
Native SDK open/close/reopen, white-eraser switch-back and Fountain style setup are
included in the Lab device checks; injected events do not validate raw pen rendering.

BooxFountainChecks validates repeatability, dots, gray, conservative raster bounds,
abrupt pressure, complete tile undo and exact redo. It saves per-backend PNG fixtures
in Lab cache. `fountainLab` log records separate geometry and raster times; total
includes reference scans/PNG writing and is not pen latency. PNGs/logs are captures,
not checked-in generated assets. APK copies are under `.tools/recovered-renderer/apks`.

On the tablet, select Fountain Lab (wave icon), black and a small size. Compare
slow pressure ramps, dots, ordinary-fast words and very-fast bursts before/after
lifting. Check for changes in width/endpoints/smoothing after the quiet gap. Try
Ink/Tilt afterward to confirm their accepted feedback still feels good. Gray stays
on app preview and must not silently become black. Do not widen the crash guard.
