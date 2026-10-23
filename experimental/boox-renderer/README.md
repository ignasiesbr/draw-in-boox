# Editable BOOX brush renderer experiment

`src/lib.rs` is copied from hbmartin/onyx-android-sdk commit
`c750d5e5ea30553063962f7bf31909e6d3d6f8ff`,
`onyxsdk-pen/native/onyx-neo-pen/src/lib.rs`. Upstream copyright/license:
LGPL-3.0-only; LICENSE.txt and incorporated GPL-3.0.txt are included.

BOOX Art modifications (2026-10-23): modern type 1 consumes every pressure sample
without history cloning. `src/dry_brush.rs` adds source-only types 11/12 for Pencil/
Charcoal, distance spacing, normalized inclination/azimuth, bounded seven-float dab
records and isolated prediction. Types 1–10 retain their existing encodings. See
docs/BRUSH_ENGINE.md for the app adapter and firmware-preview limitations.

This crate builds an app-side Android native renderer, not a replacement for the
BOOX SurfaceFlinger firmware. Keep the official input reader: upstream's current
reader JNI uses session arguments missing in our pinned official Java class.

Build on this Windows workspace with `scripts/build-recovered-renderer.ps1`.
The script uses workspace-local Rust 1.96.1 (GNU host) and Android NDK r28c,
builds arm64 only, extracts the pinned official pennative Java bindings and supplies
this .so in place of the original renderer. Original Java contracts and the other
SDK dependencies are retained. The experiment uses
a separate app ID/storage and label; the normal build uses official binaries.

Source changes must be tested against both the original renderer and this build.
Rendering parity and real-pen performance remain experimental until verified.
See docs/BOOX_BUILTIN_BRUSHES_FALLBACK.md for the independent official-SDK route.
