# E-ink UI conventions

These conventions guide this app's controls on the monochrome BOOX Go 10.3. They are product decisions informed by the sources below, rather than a claim that every e-ink device behaves alike.

## Shared icon controls

- Use a white background and simple black outline icons. Keep glyphs readable without color, gradients, elevation, or shadows.
- Use a solid black border for a selected toggle and for the active layer. Reserve enough inset for the border so selection does not move neighboring controls. A 2dp border is our starting choice; inspect it on the tablet.
- Keep icon touch targets at least 48 by 48dp, including at the screen edge. A smaller glyph inside that target makes a toolbar compact without shrinking the usable hit area. Do not overlap hit targets.
- Use the same reusable icon-button component for ordinary actions and toggles. A toggle exposes its checked state to accessibility services as well as its visible border. A momentary action, such as moving a layer up, does not remain selected after activation.
- Make state and action distinguishable: an eye represents a visible layer and a closed lock represents a locked layer. Labels and accessibility state must agree with the actual document state. Do not indicate a hidden or unlocked layer only through a faint tint.
- Give icon-only controls a meaningful `contentDescription` and a tooltip. Repeated layer controls include the layer name, for example “Lock Sketch” or “Move Sketch up.” Do not append “button”; Android announces the role. Keep the selected/checked state available separately.
- Keep disabled controls visually distinct and noninteractive. Do not mistake a disabled outline or faded glyph for a selected black border.

The exact border width, icon size, use of a selected outline, and tooltip policy are app conventions. They should be reused across future toolbars and settings rather than implemented separately by each screen.

## Layer panel and settings

The panel can live on either side, with right as the default. Preserve the user's side preference independently from document data. Collapsing leaves an accessible expand control on the chosen side. Use the same selection border on either side.

Keep layer names at least 14sp. Long-pressing the name opens rename; retain a visible dialog title, a labeled text field, and explicit confirmation/cancel actions. Accessibility should describe the long-press action so renaming can be discovered without guessing the gesture.

Settings should use a dedicated settings model and one settings dialog entry point, with labeled sections as options grow. Avoid placing application preferences in artwork checkpoints or adding a separate preference mechanism for every feature.

## Refresh and feedback

- Prefer immediate, discrete state changes to animated expansion, sliding drawers, ripples, pulsing selection, or animated icon transitions. Hide/show the panel in one layout change.
- Update controls when state changes; avoid periodic redraws. Preserve the drawing engine's BOOX hardware ink path when adding UI feedback.
- Keep feedback local where practical. A toggle updates its glyph/border; a rename updates the row. Do not force a full-screen cleanup refresh for every tap.
- Keep explicit refresh available for accumulated ghosting. Changing BOOX update modes is a device integration decision and requires physical testing; a faster mode can trade away detail or increase ghosting.
- Our deferred angle/zoom indicators are a deliberate interaction choice: show a small changing target value, then apply the document transform once on release. Do not add continuous canvas transforms solely for visual polish.

## Review on the tablet

Check selected and unselected layers, locked/unlocked and visible/hidden states, disabled reorder boundaries, icon recognition, and long-press rename. Check that targets remain easy to tap with a finger at both edges and that repeated toggles do not leave confusing ghost borders. Verify canvas pen alignment after opening, closing, or moving the panel. Automated state checks complement this physical review.

## Sources and scope

- [Onyx's e-ink development guide](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Eink-Develop-Guide.md) recommends a monochrome palette, avoiding transparency over text/images and animation, text of at least 14sp, and larger controls at screen edges (48dp). It recommends page-based loading where practical; our layer list may still scroll when needed.
- [Android accessibility for Views](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views) recommends 48dp touch targets, meaningful descriptions for non-text controls, and descriptions that identify repeated controls uniquely. The app adopts the larger target for all icon buttons, including those away from the edge.
- [Microchip's ePaper fundamentals](https://developerhelp.microchip.com/xwiki/bin/view/software-tools/mgs/dev-kits/epd-ug/fundamentals/) describes slow updates and recommends event-driven localized updates, clear feedback, high contrast, strong icon outlines, and avoiding subtle shading or unnecessary animation. This is general e-paper guidance, not a timing specification for our BOOX tablet.
- [Onyx's update-mode reference](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Update-Mode.md) documents the quality/ghosting tradeoffs between modes and distinguishes partial and full updates. Our existing adapter remains responsible for device-specific refresh behavior.

Reviewed 2026-10-23. Manufacturer guidance includes older SDK/device examples; test behavior on the actual Go 10.3 rather than assuming identical firmware behavior.
