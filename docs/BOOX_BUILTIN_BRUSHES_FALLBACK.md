# Adding BOOX built-in brushes with the official SDK

Fallback plan, 2026-10-23. This route does not depend on the recovered/community
SDK experiment. Known working app baseline: `29c7373` (earlier baseline `388add9`).
The baseline APK is saved locally under `.tools/baselines/29c7373/app-debug.apk`.

Implementation follow-up: the experiment now contains a working Lab-only Fountain
adapter (`BooxFountainGeometry.kt`) that also passes device checks against the
original SDK using `dev.ps1 test -Lab -FountainOnly`. This is a concrete starting
point for the official-only route; geometry matching to firmware still needs pen
testing. See RECOVERED_RENDERER_EXPERIMENT.md for the source/original comparison.

## Scope and evidence

Keep official `com.onyx.android.sdk:onyxsdk-pen:1.5.4.4`. Its TouchHelper exposes:

| Native style | ID | Bitmap-side candidate | Priority |
| --- | --- | --- | --- |
| PENCIL | 0 | Existing app-width round renderer | Preserve existing tools |
| FOUNTAIN | 1 | NeoFountainPenWrapper / available fountain NeoPen APIs | First new family |
| MARKER | 2 | NeoMarkerPenWrapper | After fountain/brush |
| NEO_BRUSH | 3 | NeoBrushPenWrapper | First new family |
| CHARCOAL / CHARCOAL_V2 | 4 / 6 | Corresponding NeoCharcoal wrappers + texture resources | Later |
| DASH | 5 | Separate line/selection behavior | Not an art brush initially |
| SQUARE_PEN | 7 | Final geometry support requires investigation | Later |

These are verified symbols in the pinned library, not a guarantee that Go103
firmware implements every style identically or safely. Square pen does not imply
an arbitrary angled chisel. Our current Marker is an app-defined monoline tool,
not a promise to reproduce BOOX native MARKER semantics.

Official examples:

- [NewBrushScribbleShape](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/shape/NewBrushScribbleShape.java): copies raw points and computes geometry with NeoBrushPenWrapper.
- [BrushScribbleShape](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/shape/BrushScribbleShape.java): current upstream fountain NeoPen renderer.
- [Pen SDK guide](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md): TouchHelper lifecycle and completed raw point lists.

Upstream master may use APIs newer than our pinned artifact. Inspect local
signatures and bytecode; do not copy example calls without checking availability.

## Implementation sequence

1. Add one distinct built-in preset, e.g. `boox_fountain` or `boox_brush`. Preserve
   Ink/Tilt/Marker/Eraser settings, stored IDs, rendering and live-width response.
2. Introduce a small compiled-brush rendering boundary around PressureBrush.
   Capture engine ID/version, validated settings, native style and a conservative
   footprint. Keep mutable vendor renderer state scoped to one stroke and close
   native handles in finally blocks. Do not create a parallel document/history.
3. Pair the chosen native style with its matching bitmap geometry helper. Existing
   NeoBrushPenWrapper exposes computeStrokePoints/drawStroke; NeoFountainPenWrapper
   exposes computeStrokePoints/drawStroke and MIN_FOUNTAIN_PEN_WIDTH. Verify the
   meaning/order of each float parameter and pressure units against the pinned
   implementation, including zero-pressure lists, dots and one-point strokes.
4. Define one dynamics owner per preset. Existing tools retain PENCIL plus our
   pressure/tilt width updates. The new vendor-driven preset uses the vendor's
   pressure response and fixed configured base width, without LivePreviewWidth
   applying a second pressure curve. Additional tilt requires verified vendor
   support and a matching final renderer; hide unsupported controls initially.
5. Capture PressureRange with the stroke. Adapt PenSample to the SDK TouchPoint
   format using the pressure convention required by that helper. Never normalize
   once in capture and then accidentally normalize again inside the SDK. Preserve
   event timestamps, inclination and raw axes where meaningful.
6. Map view positions into document coordinates exactly once. Set live base width
   to document width times captured zoom. Render final pixels in document units.
   If a vendor renderer ignores Canvas transforms, explicitly transform its input
   points; test this rather than compensating twice. Offline geometry must not
   accidentally send screen/live rendering commands.
7. Keep native setup paused: set style/color/base width and verified parameters,
   explicitly enable the input reader, enable rendering last. Capture the same
   native configuration for handoff. Do not mutate shaders at pen-down/move just
   because the new renderer exposes extra setters.
8. Integrate via StrokeEdit and existing layer composition. Preserve alpha lock,
   group opacity, clipping and before/after tile undo. Evaluate geometry before
   mutating pixels, derive conservative affected tiles including vendor smoothing,
   caps and overshoot, and never assume base width alone bounds every engine.
   Android/app fallback uses the same final engine, not generic round ink.
9. Keep commit/history immediate and screen reconciliation asynchronous after the
   450ms quiet gap. Preserve cancellation epochs, bounded work, immutable worker
   snapshots and lifecycle flushes. Avoid per-stroke native resets on pen-up.
10. Expose only size and genuinely supported options through the selected-tool
    dialog. Start with black and conservative small widths. Keep the gray/wide
    crash workaround; new SDK styles do not prove that firmware race is fixed.
    Gray stays gray in app preview, per the user's explicit preference.

## Acceptance before keeping the preset

- Fixed sample lists: pressure ramps, sparse fast strokes, sudden pressure changes,
  dots/endpoints, reverse direction, tile/canvas edges, zoom and rotated documents.
- Exact raster alpha-lock and undo/redo checks, including no-op strokes and lower
  layers; footprint checks against all changed pixels.
- Real SDK open/close/reopen and switch-back to the accepted Ink/Tilt tools.
- Bounded memory, native-handle cleanup and per-stroke CPU/queue timings.
- User compares real native feedback to final output at ordinary-fast and very-fast
  cadence. SDK renderers are a matching candidate, not proof of identical firmware
  smoothing/texture. Final screenshots and injected events alone cannot prove this.

Native alpha/opacity/upper-layer/rotated-edge approximations may be corrected after
the writing pause; the user accepts the alpha-mask preview difference. Material tip,
width or texture changes at reconciliation require investigation or explicit product
acceptance. Test the original accepted tools again before replacing their behavior.

## Relative difficulty and rollback

A new named preset and native style selection are small changes. Matching the final
renderer, dynamics, footprint and lifecycle is the substantial work. Fountain and
Brush are narrower experiments than implementing arbitrary custom textured brushes;
Charcoal adds resource/cache and memory requirements.

If the recovered SDK experiment fails, rebuild from `29c7373` in a separate worktree
or reinstall its saved APK, then implement this plan with official dependencies.
Preserve current drawing checkpoints before installing. Native style names and
community claims are not a reason to remove the existing firmware crash guard.
