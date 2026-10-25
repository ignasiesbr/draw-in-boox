# BOOX pens alongside the editable engine

Checkpoint before this work: `42747a3` (portable save/open included). Pen revision:
`notes pens v2` / `notes-pens-v2`. The later UI marker is `floating-toolbar-v1`;
see HANDOFF.md. BOOX pens now share the floating bottom toolbar with Eraser,
Undo, Redo and Settings; Studio tools and the former top rows are hidden.

The BOOX pens toolbar reuses Marker, Ink (regular pen), Fountain and Brush, and
adds Calligraphy, Ballpoint, Pencil fine and Pencil coarse. The hidden Studio
tools retain their saved settings and engines. Existing IDs, artwork and rendering
algorithms remain intact. The BOOX row scrolls horizontally on narrow screens.

New pens always use original SDK bitmap renderers, including in recovered builds:

| Preset | Firmware style | Final SDK renderer |
| --- | --- | --- |
| Calligraphy | SQUARE_PEN (7) | NeoSquarePen |
| Ballpoint | PENCIL (0) | NeoBallpointPen's constant-width path |
| Pencil fine | CHARCOAL_V2 (6) | NeoCharcoalPenV2 |
| Pencil coarse | CHARCOAL (4) | NeoCharcoalPen |

Firmware styles and geometry type IDs are separate namespaces. Ballpoint follows
the SDK path implementation; do not substitute textured NeoBallpointInkPen/type 8
without a verified corresponding firmware style. New controls expose size/shade;
they do not attach app pressure-width updaters to vendor dynamics.

## Matching configuration, not just brush names

Physical first-pass feedback (2026-10-25): Calligraphy and **both** new Pencil
textures were much thicker in native preview than in final pixels. Capture
`captures/20261025-113157` contains the session. The first SDK-default implementation
is not accepted as a match.

Inspection of the installed Notes application established interoperability details:

- `SquarePenShape.getPenRenderStrokeWidth` supplies **2 * selected width** to
  NeoSquarePen, while the firmware receives selected width. Our first pass missed
  this factor. Notes also configures nib ratio, angle and smoothing.
- `NoteRenderUtils.loadTiltConfig` reads textured style parameters [0] (enabled)
  and [1] (scale). Our first pass left tilt disabled. Both styles on the Go103
  returned `[1, 3]`; style 7 returned `[2, 10, 45, -1]`.
- Calligraphy takes the current firmware nib ratio/angle and valid smoothing;
  -1 smoothing uses the SDK default 0.6. Parameters are read once per compiled
  brush through `android.onyx.ViewUpdateHelper.getStrokeParameters`, outside input
  callbacks. There are no new firmware setters or changes to global parameters.
- Textured commits now enable captured firmware tilt and scale. Raw tilt axes
  are rotated into document coordinates; Android inclination/azimuth is converted
  into axis degrees for the same SDK geometry path.

The inspected Notes APK is local/ignored (`.tools/knote2-device.apk`), SHA256
`CD7D4F82AC4D26EB27FFFF85F3792A69FC25F961D6A3737A4AF6573055902EB7`.
No Notes code or binaries are bundled into the app. Dependencies remain the pinned
official pen 1.5.4.4 / pennative 1.0.5. The installed Notes app uses an older JNI
contract, so these configuration findings do **not** establish identical native
implementations. V2 physical matching is still pending user confirmation.

The public [BOOX charcoal example](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/shape/CharcoalScribbleShape.java)
also pairs the two firmware textures with their corresponding SDK wrappers and
reads tilt configuration. It is a useful reference, not a pixel-parity guarantee.

## Coexistence and performance boundaries

Recovered builds package both original `libneopen_jni.so` and isolated
`libboox_art_engine.so`. EditablePenNative selects the existing tools' backend;
RecoveredPenNative has a distinct JNI namespace. No BOOX JNI symbol is exported
by the isolated library, preventing load-order-dependent backend selection.
The Rust algorithms are unchanged. Official builds omit the isolated library and
retain the same original SDK route. Build with `scripts/build-recovered-renderer.ps1`.

BooxNotesGeometry batches at most 128 input points per JNI call and caches prepared
results for bounds/drawing. Handles close in finally. Only real ink is retained;
prediction is discarded. Results are limited to 2,048 chunks, 16 MiB of textures
and one million path values, with 100,000 input points. These checks bound retained
output; the vendor can allocate a batch before returning it. Generated texture
bitmaps are private to UI rendering and never enter the surface worker as mutable
document state. History uses actual prepared bounds and retains failure rollback.

CHARCOAL V1 emits no stationary dot; an isolated 0.01 document-pixel endpoint
offset requests a tip from that same vendor renderer. Stored input is unchanged.
Ballpoint stationary taps use the corresponding round cap.
Unchanged trailing endpoints are submitted once to the SDK. Repeating the last
MOVE as UP changed square-nib smoothing and caused an app-preview jump; the
regression check compares both inputs exactly. Changed pressure/tilt is retained,
and original recorded samples are never rewritten.

The 450ms gap, native input reader, immutable surface snapshots, bounded background
presentation, cancellation and per-stroke history are unchanged. Existing crash
guard preference is preserved. New pens still fall back to app preview when the
guard rejects their color/declared footprint. App preview uses their final SDK
renderer. Native layer-opacity/masking/rotated-edge approximations still apply.
Matching settings does not establish native latency or physical pixel equivalence.

## Validation

Recovered build/lint and full Go103 device suite pass after the v2 correction,
including native open/reopen, rotated gray live/committed surface comparisons,
duplicate endpoint stability, exact regenerated pixels, history and alpha lock.
Rust host tests: 29 passed. Official build/lint and focused pen checks also pass. These verify app
behavior, not a native firmware pixel match. Stage costs and cold-start limits
are recorded in PERFORMANCE.md.

Run `dev.ps1 test -Renderer recovered -NotesOnly` for targeted pixels/bounds/dots,
gray/alpha lock, undo/redo, both-library coexistence and original-tool regression.
The full suite also checks actual native SDK open/reopen and app live/committed
SurfaceView comparisons, including the new pens. Physical checks must compare
light/heavy pressure, tilt, dots, slow/ordinary-fast/burst writing before and after
the quiet gap. Do not label native preview matching as accepted based on these
automated checks alone.
