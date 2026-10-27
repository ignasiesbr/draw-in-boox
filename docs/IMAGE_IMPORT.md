# Importing reference images

Settings → Files → Import image… opens Android's document picker for JPG/JPEG
and PNG files. Import adds a selected, visible, unlocked raster layer above the
selected layer, at 100% opacity. Existing layers, document size and viewport stay
intact. The file name becomes the layer name (without its extension).

The image is scaled to fit inside the document and centered, preserving aspect
ratio without cropping. Smaller images can be enlarged. Unfilled margins remain
transparent; PNG transparency survives. Android ImageDecoder handles JPEG EXIF
orientation and converts to sRGB. Colors remain in the artwork even on the
monochrome display. The current import rasterizes placement; interactive move,
crop and resize controls are not part of this feature.

Long-press the imported layer's thumbnail to change its 0–100% opacity in the
existing layer settings dialog. Opacity fades the whole layer without modifying
its original pixels. The layer supports drawing, erasing, visibility, lock,
reordering, duplication and merging like other raster layers. Import and opacity
changes are separate undoable actions under the existing history count/byte
budget. Large actions that exceed the history budget retain artwork but clear
history, following the existing structural-edit policy. Redo uses captured pixels
and does not reopen the original image URI.

Imported pixels and layer settings persist in the internal checkpoint and
portable `.boox` files using existing raster-layer formats. Export PNG includes
the image with its layer opacity. No format version change is needed, and no
long-term URI permission is needed after decoding.

## Ownership and limits

`DocumentActions` owns picker request 103 and reuses Save/Open's input freeze,
busy modal, deferred early result and worker-destruction coordination. Image
preparation stages at most 64 MiB in the private cache, validates actual decoded
MIME type and limits source dimensions to 100 megapixels. Unsupported or corrupt
images fail before touching artwork. The file worker uses ImageDecoder's target
size so decoded dimensions stay inside the document; it never reads mutable layer
pixels. A conservative heap check reserves decoder pixels, the new layer and
bounded history. The existing canvas-dependent layer limit is checked before
opening the picker, on result and at insertion.

The UI copies the independent decoded image into a normal ARGB_8888 layer through
`LayerCommand.ImportImage` and `EditHistory.executeLayer`. Source pixels are always
recycled after the command. Structural history capture errors now restore the
pre-command stack and retained pixels, including failures after inserting a layer.
An explicit import checkpoints once while input is closed. Checkpoint failure is
reported while keeping the imported layer in memory. Cancellation/decode failure
retains existing artwork/history and resumes input. Destruction waits for decode
to return before recycling the frozen document and detached image.

The saved firmware crash guard and native width/color policy are unchanged.
Existing layer-change callbacks close/reopen native input and invalidate screen
caches as needed; the accepted 450ms handoff and per-stroke work stay intact.
An import/checkpoint can take time while the modal is open; no new physical pen
responsiveness result is claimed.

## Validation

`dev.ps1 test -Renderer recovered -ImagesOnly` runs small isolated fixtures for
JPEG/PNG decode, EXIF rotation, aspect ratio and placement, transparent margins,
PNG alpha, opacity composition without pixel changes, import/opacity undo and
redo after recycling the decoded source, checkpoint/portable round-trips,
unsupported/corrupt input, rejected layer edits and layer limits. Activity checks
exercise real asynchronous import results, results before surface startup,
cancellation, failed decode, input resumption and destruction during decode.
These checks also run in the full device suite. Test storage is isolated from
the user's artwork.

Go103 validation (2026-10-25): focused image import and full recovered suites pass;
official/recovered builds and lint pass. The actual Downloads picker imported a
PNG into its own layer, and the existing thumbnail settings visibly faded it at
50% opacity. Undo removed the test layer; the resulting checkpoint's metadata and
all layer PNG bytes matched the pre-test backup. Captures/logs are retained in
`captures/image-import-v1`. Cloud providers and physical pen timing are unmeasured.

Decoder API reference:
[Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder).
