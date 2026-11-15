# Layer system

Implemented and physically accepted by the user on 2026-10-23, including opacity and alpha lock. See [FEATURES.md](FEATURES.md) for maintenance details and [EINK_UI.md](EINK_UI.md) for shared UI conventions. Portable save/open is implemented; see [SAVE_FORMAT.md](SAVE_FORMAT.md).

## Controls and behavior

The collapsible panel defaults to the right; the gear opens app Settings to choose left/right. Panel side and expansion are preferences, independent of artwork/history. The panel now overlays a full-workspace canvas and wraps its content height; long lists scroll above the bottom toolbar. Collapse leaves only a 52x60dp expand control. Space beneath either panel state stays canvas. Moving/resizing the overlay preserves surface dimensions, origin and viewport, and explicitly reconfigures BOOX input after layout. Native input excludes only its measured rectangle and the toolbar rectangle, captured at session open.

Rows list topmost first. Tap a name/thumbnail to select; a solid black border marks the selection. Shared monochrome icon controls provide Add/Duplicate/Merge down/Delete at the top and Visibility/Lock/Up/Down on each row. Checked toggles have black borders; controls have at least 48dp touch targets, labels and tooltips. Hide leaves an expand icon.

Long-press a name to rename (trimmed, nonblank, single-line, up to 80 characters). Long-press a thumbnail for Layer settings:

- Opacity: 0-100% slider with percentage readout. Source pixels remain unchanged; composition, thumbnails and PNG export apply group alpha.
- Alpha lock: paint only within existing pixel transparency, preserving soft-edge alpha. Blank layers stay blank. Clear is disabled until alpha lock is off. This differs from full layer lock, which blocks all painting.

Settings are drafts: Cancel changes nothing; Apply commits opacity and alpha lock as one undoable metadata action. The modal is the extension point for future layer properties; app preferences stay in the separate app-settings model.

Add inserts above selection. Duplicate copies independent pixels, opacity and alpha lock, but unlocks the new layer. Hidden/full-locked selections reject drawing and Clear while navigation/history remain available. Locked layers can be selected, renamed, reordered, hidden/shown, duplicated and have metadata settings changed. Delete requires unlocked and at least one surviving layer. Merge requires both neighbors visible/unlocked, retains the lower identity/name/alpha-lock flag, bakes both opacities into the merged pixels and resets retained opacity to 100%, preserving appearance. Alpha lock constrains painting/Clear, not explicit structural merge/delete operations.

## Architecture and invariants

1. `DrawingDocument` owns stable-ID transparent raster layers, ordered bottom-to-top, and selected ID. A memory-derived limit reserves history, composition, export and app overhead (7 layers on the BOOX preset, 3 on A4, hard cap 64 for small canvases).
2. A cached transparent composite is invalidated by changed regions/structure. Navigation reuses cached pixels; thumbnails read source bitmaps without copies. Android pending strokes render at their layer position, grouped before applying opacity. Alpha-locked pending strokes use an isolated layer even at 100% opacity so underlying artwork/paper cannot become their mask.
3. `LayerCommand` and `EditHistory` own mutation transactions. Pixel entries resolve recorded layer IDs and can use immediately available raw tile copies while bounded background compression runs. Structural entries retain metadata and synchronously compressed tiles only for inserted/removed/changed layers. Ten entries and the retained raw/compressed byte budget remain. Workers receive no live bitmap references and cannot reorder/resurrect history; see UNDO_COMPRESSION.md for admission, cancellation and fallback. Selection/view/settings-panel changes do not consume history; effective layer mutations do.
4. `PressureBrush` uses SRC_ATOP for alpha-locked strokes and resets its blend mode afterward. Both Android and BOOX commits share this path. Future painting/erasing/fill tools must honor the same constraint deliberately; generic history does not implement tool semantics.
5. Layer/viewport changes guard active drawing/navigation and close/reopen BOOX input through the surface/activity lifecycle. Captured transform/layer IDs and session generations reject stale callbacks.
6. Internal checkpoint v2 stores ordered metadata and one PNG per layer. Optional integer `opacity` defaults to 100; optional `alphaLocked` defaults to false. Read v1/legacy PNG, validate memory/dimensions/IDs/viewport/opacity, recycle partial loads on failure, and retain corrupt archives for diagnosis. Undo history is session-only.

BOOX live ink is a fast temporary foreground preview. It may appear above higher layers, at full black despite layer opacity, or outside the alpha mask/angled paper edge until pen-up; the committed composite is correct. Preserve live feedback and real stroke callbacks when investigating firmware clipping. These hardware-preview limitations are distinct from Android's directly composited preview.

## Verification

Build/lint and the full device instrumentation suite passed for the earlier column layout. The content-height overlay update adds sizing/scrolling and actual layout/exclusion checks; device execution and physical pen checks are pending. `LayerChecks` covers composition/order/cache, protection, duplicates, merges, history, migration and memory limits. `LayerRenameChecks`, `LayerOpacityChecks` and `AlphaLockChecks` cover their metadata and pixel semantics, persistence and undo/redo. `LayerPanelChecks` exercises real dialogs against isolated artwork plus panel collapse/expand and side changes. `SettingsChecks` covers app-preference defaults/fallback/persistence and modal Apply/Cancel. Keep test storage isolated from the actual document.

The user confirmed the layer engine, compact icon UI/settings, opacity and alpha lock work well. Screenshots: panel (screenshot omitted), opacity modal (screenshot omitted), alpha-lock modal (screenshot omitted). Physical ghosting/edge/preview equivalence still require ongoing tablet checks; screenshots and automated pixels do not measure them.
