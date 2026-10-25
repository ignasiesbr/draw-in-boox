# Portable drawings (.boox), version 1.0

The Files button offers **Save a copy…**, **Open drawing…**, and **Export PNG**.
Save creates an editable `.boox` file through Android's document picker, in local
storage or any installed document provider. Copy that file to another device and
open it from Files to continue editing. Cloud availability depends on installed
providers; the app does not implement synchronization. Save a copy always creates
a new file, rather than overwriting an earlier drawing. Keep the file until the
save confirmation appears. New canvas and Open offer Save & continue, explicit
discard, and Cancel. Cancelling a picker or failing a save never continues with
replacement. A failed Open retains the current artwork and internal checkpoint.

## Container and pixels

A `.boox` file is a ZIP archive (UTF-8 JSON and entry names) with MIME type
`application/vnd.boox-art.document`. File signatures and the manifest identify
the format; opening does not depend on a provider reporting the MIME type correctly.
Required entries:

```text
manifest.json
layers/<layer-id>.png       one PNG for every layer, including hidden/empty layers
```

Each image is full document size, with 8-bit sRGB RGBA pixels and transparent
background. Pixels are canonical artwork, after all drawing/erasing operations.
Layer opacity is metadata applied to the layer as a group; it is never baked into
these pixels. PNGs preserve actual raster artwork regardless of which brush engine
or firmware rendered it. No stroke replay or brush implementation is needed.
Android's existing premultiplied raster representation remains the editing model.
The ZIP uses compression level zero because PNGs already compress their pixels.

This is an example manifest (IDs are illustrative):

```json
{
  "format": "art.boox.document",
  "version": { "major": 1, "minor": 0 },
  "requiredFeatures": ["raster-layers-v1"],
  "id": "document-id",
  "width": 1860,
  "height": 2480,
  "ppi": 300,
  "selectedLayerId": "ink-id",
  "layers": [
    {
      "id": "ink-id",
      "type": "raster",
      "name": "Ink",
      "visible": true,
      "locked": false,
      "opacity": 100,
      "alphaLocked": false,
      "pixels": "layers/ink-id.png"
    }
  ]
}
```

`layers` is ordered **bottom to top**. IDs are stable across save/open, unique in
their scope, and match `[A-Za-z0-9_-]{1,128}`. Width/height are positive integer
document pixels; optional PPI is a positive integer describing physical size.
Opacity is an integer percent, 0–100 inclusive. All layers and their original
transparency survive, including locked, hidden, alpha-locked and zero-opacity layers.

Current tool settings, app preferences, viewport position/zoom/rotation, and undo/
redo history are omitted. Opening selects the stored layer (or the bottom layer
when selection is absent), resets the view to upright Fit, and clears session
history. The application's current brush preferences remain independent.

## Compatibility rules for future writers

- Never change an existing field's units, type, meaning or default within major 1.
  Readers accept any nonnegative minor version in the major they implement.
- Add optional fields with documented defaults and increase the minor version.
  New readers must still read earlier files using these defaults. Current defaults:
  layer type `raster`, name `Layer N`, visible `true`, locked `false`, opacity `100`,
  alphaLocked `false`, absent selection means bottom layer, absent PPI means unknown,
  absent requiredFeatures means no additional requirements.
- Readers ignore unknown optional JSON fields and unreferenced ZIP entries.
  These may be discarded on re-save; **do not place essential artwork or settings
  in optional fields unless their loss is acceptable**.
- Any new feature whose loss changes artwork or editing behavior MUST add a stable
  token to `requiredFeatures`. An old reader rejects an unknown token before loading
  pixels. Examples include masks, non-normal blend modes, vector layers or an
  essential future brush-data payload. A new reader can understand the feature and
  provide defaults for older documents. Current token: `raster-layers-v1`.
- Use a new major version for incompatible container/manifest semantics. Add an
  explicit migration reader rather than changing the interpretation of old files.
  Unsupported major versions produce a readable error and leave the editor intact.
- Keep artwork files independent from diagnostic stroke schemas, native firmware,
  app build markers, and preferences versions. Future optional tool presets belong
  in a separately described field, with an explicit default when absent.

## Safety, ownership and restart

`DocumentFile.kt` owns the portable codec. Reads validate manifest limits, versions,
required features, dimensions, PPI, layer count, IDs, paths, flags, opacity, selection,
ZIP CRCs and every PNG's type/dimensions before allocating layer pixels. Duplicate
archive names are rejected. No paths are extracted. Partial layer allocations are
recycled after failure. Input is capped at 512 MiB, manifest at 1 MiB, entries at
1024, and layer sizes/counts at the existing canvas and heap limits. A drawing too
large for the destination device is rejected as a whole, preserving every layer
in its original file. It is never silently flattened or truncated.

`DocumentActions.kt` closes pen input, freezes editing behind a noncancelable modal,
and performs archive encoding, provider I/O, decoding and import checkpointing on
one worker per explicit operation. There are no per-stroke saves or JSON additions
to the pen-input path. Frozen bitmaps stay alive through Activity destruction until
the writer finishes; the surface is then disposed. Other workers keep their
existing immutable screen snapshots and session/epoch guards.
If Android recreates the Activity while a picker is open, its result waits for
the first document-load attempt. Save then uses the restored checkpoint; Open
can also replace a failed checkpoint. This avoids racing initial checkpoint reads
against import writes or silently dropping an early Save result.

Saving first completes an atomic local staged archive, then copies it to a newly
created provider file. A provider failure attempts to delete that incomplete new
copy. Providers do not universally support atomic transactions; an interruption
during that last copy can leave an incomplete new file, but cannot overwrite a
previous save. Imported files are staged without modifying the original, decoded
fully, and written to the atomic internal checkpoint before editor replacement.

The existing private `DocumentStore` checkpoint remains a separate v2 restart
format and still reads its v1 predecessor and legacy `canvas.png`. It retains
viewport state for same-session restart convenience. This private format is not
the portable file contract; its version number is unrelated. Opening a portable
file creates a fresh checkpoint with no stored viewport. Flattened PNG export
remains a separate operation for sharing images.

## Verification

`DocumentFileChecks` exercises multilayer lossless pixel/metadata round-trips,
Unicode names, full alpha range, hidden/locked/zero-opacity layers, defaulted older
manifests, future additive minors, unsupported required features/major versions,
malformed fields, missing/wrong-size/corrupt pixels, invalid paths, input limits,
and preservation of an existing file after an interrupted atomic write. Its
Activity tests exercise the actual asynchronous Save/Open result handlers, fresh
history/view, restart checkpoint, and failed import/output preservation using an
isolated editor and local URI streams. Run via `dev.ps1 test` or the focused
`dev.ps1 test -DocumentsOnly`.

Go103 validation (2026-10-25): the focused portable suite and the full recovered
Lab suite pass. Standard and recovered app builds/lint pass. An actual Android
Downloads picker save created `drawing-1792914218388.boox`; pulling the file to the
host confirmed the manifest, original IDs, dimensions, layer PNG and ZIP checksums.
The save returned to the artwork with its confirmation and native input reopened.
Captures: `captures/20261025-084345` (picker and downloaded archive) and
`captures/20261025-084439` (confirmation). A second actual save preserved the current
drawing as `drawing-1792915179878.boox`, then Open selected that copy from Downloads.
`captures/20261025-090126` shows the Open picker; `captures/20261025-090139` contains
the reopened artwork, confirmation, diagnostics and downloaded file. IDs, dimensions,
layer settings and pixels survived; the view reset from 100% to Fit (~42.46%), history
was empty, and real SDK input reopened. The capture's final optional device-property
query lost its ADB connection after the screen, logs and document state were saved;
those saved artifacts and the subsequent successful file pull verify the file flow.
Updated focused checks also pass for picker results arriving before startup and
for opening over a corrupt private checkpoint. The final full recovered suite and
both app builds/lint pass after this lifecycle fix. Cloud upload and a second
physical device remain untested.
