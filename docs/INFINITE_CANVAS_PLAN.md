# Expandable canvas implementation plan

Planning date: 2026-10-28. Proposal only; no renderer or input behavior changed.

## Feasibility and recommendation

Feasible, but this is a document-storage and rendering project, not just a change
to pan limits. Use sparse raster tiles with a bounded memory cache and disk backing.
Keep canonical raster artwork: replaying old strokes would depend on brush engines
and vendor behavior and would complicate erasing, imported images and compatibility.

Start with the current finite page size. When panning approaches an edge, extend
the logical paper bounds in tile-aligned chunks, including left/up into negative
coordinates. Do not move existing artwork or allocate blank pixels for the newly
exposed paper. Allocate layer tiles only when an edit needs them. This gives the
requested growing-page experience without repeatedly resizing every layer.

“Infinite” means practically expandable within documented coordinate and storage
limits, not unlimited RAM, disk or undo. Keep existing finite documents supported;
offer expandable mode explicitly for new drawings or conversion.

## What exists today

| Area | Current implementation | Required change |
| --- | --- | --- |
| Artwork | `DrawingDocument.kt`: one full ARGB bitmap per `RasterLayer`; multilayer composite can also be full size | Sparse per-layer tile store and bounded composition |
| Size limits | `CanvasSizes`: maximum side 8192, pixel limit min(12 million, heap/20) | Retain finite-mode limits; separate world bounds from allocation limits |
| Layer limits | `LayerLimits` estimates cost from full page size | Budget actual resident tiles, while retaining metadata/layer-count limits |
| Navigation | `DocumentViewport.kt`: Float coordinates, finite paper clipping and pan clamps | Signed world coordinates, growing bounds, locally rebased rendering |
| Brushes/history | `StrokeEdit` draws directly to `Canvas(document.layer)`; history uses 128px patches | Route edits through tile-aware transactions |
| History memory | Ten actions, min(32 MiB, heap/8); bounded asynchronous compression | Preserve limits initially; adapt patch addressing and tile existence |
| Presentation | Immutable worker snapshots; rotated seeds can copy the full document | Viewport-bounded snapshots at every rotation and in fallback paths |
| Persistence | Private checkpoint and portable files encode full-size layer PNGs | Versioned tiled persistence with incremental local backing |
| Export/import | Flattened full-page PNG; image import constrained by page | Explicit finite export region; bounded image-to-tile import |

An 1860 × 2480 ARGB layer occupies about **17.6 MiB** of raw pixels. Four layers
take about **70.4 MiB**, before composite, history, screen buffers, geometry and
temporary allocations. Doubling both dimensions multiplies those figures by four:
about **281.5 MiB** for those four layers alone. Resizing also temporarily retains
old and new allocations. Repeated full-bitmap enlargement is unsuitable here.

## Proposed architecture

### Coordinates and paper growth

- Separate paper bounds, content bounds, viewport and optional export bounds.
  Visiting empty space expands paper metadata, not artwork storage.
- Use signed integer tile keys and Double world viewport coordinates. Convert to
  Float only after subtracting a nearby render/stroke origin. Android Canvas and
  vendor geometry must never receive enormous absolute world coordinates.
- Define checked coordinate limits, safe multiplication and floor division for
  negative tile coordinates. Do not use the current positive-only tile division.
- Freeze the stroke-local origin and transform for each native session. Panning
  closes/reconfigures input through the existing lifecycle path; it cannot change
  the coordinate system of a stroke already arriving.
- Grow bounds from the inverse-transformed visible region plus a margin, coalesced
  with navigation. Include rotated corners and stroke footprints. Keep the point
  under the fingers stable; growth must not recenter or refit the page.
- Panning/growth is not an undo action. Undo does not shrink paper. Provide Home
  and Fit content; use the initial page for empty-content Fit. Avoid making a huge
  visited-but-empty area the default Fit target. Keep a deliberate minimum zoom.

### Tiles and brush rendering

- Introduce a raster-storage interface with finite-bitmap and tiled implementations.
  Address tiles by `(layerId, tileX, tileY)` and track revisions separately.
- Benchmark 256px tiles first against 128px and 512px: ARGB raw sizes are 256 KiB,
  64 KiB and 1 MiB respectively. Storage tiles need not equal history patch size.
- Missing tiles are transparent. Paper white is a rendering background, not stored
  layer pixels. Erasing empty space or painting alpha-locked empty space allocates
  no persistent tile. Prune fully transparent tiles when safe.
- Prepare stroke geometry once, identify its affected tiles and rasterize with
  local transforms/clips. Do not restart brush spacing, texture randomness or
  vendor geometry at each tile. Spatially select prepared geometry to avoid
  replaying every dab against every touched tile.
- Use bounded padding where filtering requires neighbor pixels, but commit each
  canonical pixel once. Test seams at edges and four-tile corners for every brush.
- Preflight memory/history and prepare rollback state before mutation. Commit one
  stroke as one atomic artwork/history action, even across many tiles. Bound
  pathological stroke sizes; reject before mutation if a transaction cannot fit.

### Memory and backing storage

Use one accounting policy across resident layer tiles, composite caches, history,
pending immutable snapshots, raw compression jobs, decode/encode buffers and
brush scratch. Include pinned old tile versions and temporary copy-on-write
allocations. Managed-heap estimates alone do not capture native/vendor memory;
measure process memory on Go103 and preserve substantial headroom.

- Pin the writable viewport working set and stroke footprint while editing.
  Prefetch a small margin after panning; cancel obsolete prefetch by generation.
- Evict least-recently-used clean, unpinned tiles. Compress/write dirty immutable
  revisions on a bounded background worker, outside pen callbacks. Evict a dirty
  revision only after its backing write is acknowledged.
- Never confuse absent tiles with existing tiles that are unloaded or failed to
  load. Show a loading state for unavailable artwork. Before reopening pen input,
  ensure the writable visible region is resident and has transaction headroom.
  Fast panning into uncached content may therefore have a brief readiness delay.
- Under pressure, discard disposable composites and reduce prefetch first, then
  evict eligible tiles/history according to policy. If pinned data cannot fit,
  pause new editing explicitly; do not silently discard unsaved artwork.
- Disk full/write failure keeps dirty data pinned, reports the failure and blocks
  further edits before the reserved headroom is exhausted. Bounded write queues
  need explicit backpressure; disk backing is not permission for unbounded RAM.
- Keep local backing data separate from portable exports. Use immutable tile
  revisions plus atomic manifest generations or an equivalent transactional store.
  Publish references only after their blobs are durable. Background writes must
  never publish an obsolete revision over a newer edit.
- Define crash recovery: restore the last durable generation, with any newer
  in-memory edits potentially lost on abrupt process death. Flush at the existing
  save/close/session boundaries without introducing per-pen-up disk waits.
- Bound the tile index itself: a disk index with cached pages is preferable to
  retaining every tile descriptor forever. Garbage collection must retain data
  referenced by current artwork, history, snapshots and in-progress saves.

Do not choose a final cache budget before measurement. The target is a memory
plateau as explored/drawn area grows, subject to viewport, layer and edit limits.

### Layers and undo/redo

- Preserve visibility, order, locking, opacity and alpha lock. Apply group opacity
  after composing the pending stroke with its layer, as today; overlapping stroke
  pieces must not each receive layer opacity independently.
- Keep the existing ten-action/byte-bounded history policy for the first release.
  Canvas area and undo depth are separate features. Deep disk-backed undo can
  follow later, with its own storage quota and latency expectations.
- Store exact before/after pixel patches with signed world addresses and tile
  existence information. Undo of the first mark on a tile restores absence;
  redo recreates it. Preserve immediate undo while compression is pending,
  no-op redo preservation, branching cancellation and failed-edit rollback.
- Keep history patches independently bounded; do not pin every tile touched by
  old actions in RAM. Undo into unloaded artwork first loads required tiles under
  an editing gate, then applies the complete action atomically.
- Add/reorder/visibility/opacity operations mostly change metadata. Duplicate can
  share immutable tile revisions until edited. Delete/Clear can retain a prior
  versioned layer-map root for undo rather than capturing the entire world.
  Account retained blobs against storage/history quotas even when off heap.
- Merge touches the union of occupied tiles, preserving opacity semantics. Run
  large merges/imports as bounded staged operations with editing paused, then
  atomically publish. Failure retains the original layers. Do not accumulate all
  merged output or rollback pixels in memory.

### Presentation and zoom

- Compose only visible tiles into bounded immutable frame pixels. Retain the
  surface worker's ownership, dirty-region union, epochs and bounded pending work.
- Remove full-document snapshot allocation for expandable documents, including
  rotated, cache-seed, empty-intersection and synchronous fallback paths. Preserve
  the finite renderer's existing fallback until the replacement is validated.
- Cache composites by tile and layer revisions; metadata changes invalidate them
  lazily. Hidden layers need not be resident, but must remain preserved on disk.
- At distant zoom levels use bounded cached overview tiles; otherwise a viewport
  can intersect thousands of full-resolution source tiles. This is required before
  Fit content over a huge drawing ships. Overview pixels are disposable previews;
  canonical edits and export always use original pixels.

### Saves, compatibility and export

- Introduce a new portable major version for signed tiled artwork and logical
  bounds, with an explicit required feature. Do not reinterpret v1 full-layer PNGs
  as tiles or silently omit offscreen content for old readers.
- Read existing portable v1 and private checkpoints and migrate into tiles. Keep
  original files intact. Support new finite and expandable documents explicitly.
- Open large documents by validating/staging their index and decoding only needed
  tiles. Preserve entry/path/CRC/dimension checks with limits suitable for a tiled
  format, including decoded size, tile counts and total disk usage.
- Save from a pinned document revision, streaming tiles with bounded buffers.
  Preserve atomic local staging and current provider-failure behavior. The first
  version may keep explicit Save's existing editing freeze to simplify ownership.
- Export requires a finite region: current view, selection, initial page or content
  bounds. Enforce output pixel limits before allocation. Very large output needs
  a streaming encoder or multiple pages/files; do not create one enormous Bitmap.
- Persist logical bounds and private viewport state. Keep portable history omitted
  initially, matching today's behavior. Specify portable opening position rather
  than blindly fitting all visited paper.

## Implementation sequence and acceptance gates

1. **Measure and isolate storage.** Record matched baseline stage times and memory
   for representative brushes, layers, pan/zoom/rotation and history. Introduce the
   storage abstraction backed by today's finite bitmap. Gate: existing pixel,
   lifecycle and device checks pass with unchanged physical writing behavior.
2. **Prove tiled editing on a fixed page.** Implement tile coordinates, brush routing,
   transaction rollback, tiled history and layer semantics within current size
   limits. Gate: tiled versus existing canonical pixels match for all brushes,
   alpha lock, erasing, imports and layer actions, especially tile seams. Compare
   commit cost to the baseline before introducing growth.
3. **Bound rendering and memory.** Add viewport composition at every angle,
   cache budgets, revision ownership, disk paging and failure/backpressure handling.
   Gate: draw/pan across many cache capacities, return to evicted artwork and undo
   across it without pixel loss or increasing resident memory. Test slow/full disk.
4. **Enable growing paper.** Add signed world navigation, logical growth, native
   clipping/session mapping, Home and Fit content with bounded overview rendering.
   Gate: left/up growth and rotation preserve positions and brush widths; panning
   through empty space creates no persistent raster tiles; physical pen works
   correctly immediately after navigation finishes and input becomes ready.
5. **Complete file/lifecycle support.** Add tiled portable format, legacy migration,
   restart recovery, bounded import/export and structural-operation staging.
   Gate: large multilayer round trips, interrupted writes, failed imports and
   reopen restore correct pixels/metadata; originals remain intact on failure.
6. **Trial on Go103, then release.** Keep expandable mode opt-in during validation.
   Run both renderer builds/lint and relevant/full device suites. Compare ordinary
   fast writing and bursts, cold-cache pan-to-write, long textured strokes, immediate
   Undo/Redo, many layers, rotation, low memory, Home/return and process death.
   Update PERFORMANCE.md and HANDOFF.md with actual results and remaining limits.

Steps 3 and 5 share a persistence foundation: build local transactional backing in
step 3, then portable packaging/migration and full lifecycle validation in step 5.
The end of step 2 is an internal feasibility checkpoint, not a shippable infinite
canvas. Do not expose unrestricted growth before paging and recovery work.

## Performance invariants

Preserve the accepted 450ms quiet-gap handoff, per-stroke artwork/history commits,
immutable worker snapshots, bounded queues, session cancellation and synchronous
fallback. Native SDK View calls remain on UI; configure while paused and explicitly
enable the reader before rendering. Keep the saved firmware crash guard unchanged.
Retain current app-width PENCIL behavior and vendor-family dynamics independently;
do not add another pressure curve or reconnect vendor JNI to the recovered engine.

Tile loading, serialization and file writes must not enter ordinary pen-up work.
Physical responsiveness remains a device acceptance criterion; pixel tests and
CPU measurements alone cannot establish it.

## Scope and decision

This is a substantial, multi-milestone refactor across storage, brushes, history,
viewport, presentation and file handling. A calendar estimate before the tiled
brush prototype and memory measurements would be unreliable. The largest risks
are exact textured-brush seams, transaction memory, rotated composition, and cold
tile readiness without disturbing native input.

Recommended first deliverable: a fixed-size tiled prototype with one complete
stroke/history/presentation path, then all brush and layer parity checks. Proceed
to expandable paper only after that proves comparable pixels and pen performance.
Keep deep persistent undo, arbitrary giant single-image export and unrestricted
zoom-out outside the initial scope.
