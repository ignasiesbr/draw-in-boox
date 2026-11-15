# Draw in Boox

A drawing app for BOOX e-ink tablets, built around pen input, layers and editable
drawings. Developed and tested on the **BOOX Go 10.3 running Android 12**, with
BOOX native live ink where supported.

**[Download an APK](https://github.com/ignasiesbr/draw-in-boox/releases)** ·
**[Local development](docs/DEVELOPMENT.md)** ·
**[Report an issue](https://github.com/ignasiesbr/draw-in-boox/issues)**

This is an experimental app. The current release is an ARM64, debug-signed preview;
other BOOX models and firmware versions have not been verified. Android 10 or newer
is required, but Android version alone does not guarantee BOOX pen compatibility.

## Install

1. Open [Releases](https://github.com/ignasiesbr/draw-in-boox/releases) and choose
   the newest preview. Download the `.apk` from **Assets**.
2. Open the APK on your BOOX tablet and allow installation from that source if
   Android asks. Complete the installation.
3. Launch **Draw in Boox**, shown with a black pencil on a white icon.

The published preview uses the recovered renderer and package ID
`art.boox.probe.lab`. Updates with the same package and signing key retain app data.
Save important work as `.boox` files before changing installations.

## Features

| Feature | What you can do |
| --- | --- |
| Pens and eraser | Draw with Marker, Calligraphy, Ink, Ballpoint and fine/coarse Pencil. The preview build also includes Fountain and Brush. Erase pixels on the selected layer. |
| Per-pen settings | Adjust size, eight gray shades and supported pressure/tilt options. Each pen remembers its settings. Tap the selected pen again to edit it. |
| Layers | Add, rename, duplicate, reorder, merge down or delete layers; toggle visibility and locking; adjust opacity and alpha lock. The layer limit depends on canvas size and available memory. |
| Undo and redo | Undo strokes, erasing, image import and layer edits. History holds up to 10 actions within a memory budget and is available during the current session. |
| Canvas sizes | Start with BOOX screen, square, A5, A4, landscape or custom pixel dimensions. The default is 2480 × 1860, in landscape. |
| View controls | Fit the page, zoom in 25 percentage-point steps from 75% to 400%, and rotate in 15° steps or enter an exact angle. Fit can show larger pages below 75%. |
| Movable controls | Move the drawing and navigation toolbars independently. Collapse the layers panel or place it on either side. Toolbar positions are remembered. |
| Drawing mode | Hide both toolbars and the layers panel for more drawing space, then restore them without changing the view. |
| Image import | Import JPG or PNG as a new layer, centered and fitted to the canvas. PNG transparency is preserved. |
| Editable files | Save and open portable `.boox` drawings containing layer pixels, layer properties and pen settings. |
| PNG export | Export the complete drawing, flattened over white, to `Pictures/Draw in Boox`. |
| Optional gestures | Enable two-finger pan, pinch zoom, twist rotation and Undo/Redo shortcuts. Canvas gestures default off. |

## Start drawing

Use the pen on the canvas and tap toolbar buttons with a finger or pen. Select a
tool on the horizontal drawing toolbar; tap it again to open its settings, then
choose **Apply**. Settings also contains New canvas, Files, Clear, Refresh and
additional view controls. Clear asks before clearing the selected layer.

The vertical navigation toolbar contains **Fit**, **Zoom in**, **Zoom out** and
**Rotate left/right**. To move either toolbar, hold its dotted grip with a finger
or pen for about half a second, then drag. Pen hover over the grip also supports
hold-and-drag; lifting the pen drops the toolbar.

Tap the **four-corners icon** between Redo and Settings to enter Drawing mode.
Use **Show controls** at the top right, or Android Back, to bring the controls back.

### Work with layers

Select a layer by tapping its name or thumbnail. Long-press its name to rename it;
long-press its thumbnail to adjust **Opacity** or **Alpha lock**. Alpha lock limits
painting to existing nontransparent pixels. Erasing reveals lower layers or paper.

### Enable gestures

Open **Settings > Gestures > Enable canvas gestures**, choose the shortcuts you
want, and apply the changes.

- **Pan:** drag with two fingers.
- **Zoom:** pinch with two fingers. Once a gesture starts panning, lift both
  fingers and start a fresh pinch to zoom.
- **Rotate:** enable **Rotate: two-finger twist**, twist to select an angle, and
  lift to apply it.
- **Undo:** two-finger tap, when its shortcut is enabled.
- **Redo:** two-finger double tap, when its shortcut is enabled.

Toolbar buttons work with gestures disabled. View rotation affects the display
and pen coordinates; it does not rotate the saved artwork or PNG export.

## Save, reopen and share

Open **Settings > Files**:

- **Save a copy…** creates an editable `.boox` file using Android's file picker.
  Each save creates a new copy. Transfer it to another device running the app to
  continue editing.
- **Open drawing…** restores a `.boox` drawing with its layers and pen settings.
  It opens upright and fitted to the screen, with fresh Undo/Redo history.
- **Import image…** adds a JPG or PNG above the selected layer. Placement is
  fitted and centered; interactive image move/crop/resize is not implemented.
- **Export PNG** creates a full-resolution, flattened image for sharing. The
  current zoom, pan and view rotation do not change the exported pixels.

The app also keeps a private restart checkpoint when it pauses. Use **Save a
copy…** for drawings you want to keep independently of that checkpoint. Undo/Redo
history is not saved in either portable files or across app restarts. The app
does not provide automatic cloud synchronization; available save locations depend
on the file providers installed on your tablet.

## Current limitations

- **Preview and final ink can differ.** BOOX firmware draws live ink; the app
  commits the layer pixels. Some brushes, opacity, alpha lock and rotated page
  edges are corrected after pen-up. New pen matching is still being tested.
- **Gray and wide tools can feel slower.** The firmware crash guard uses
  app-rendered live ink for unsupported native-preview colors and widths after
  a system renderer crash was observed. Keep **Settings > Advanced > Firmware
  crash guard** enabled for normal use. See [native preview safety](docs/NATIVE_PREVIEW_SAFETY.md).
- **Validation is ongoing.** The release passed build, lint and local unit tests.
  Earlier device checks found unresolved pixel-comparison failures at certain
  zoom/rotation settings. Physical testing of toolbar dragging and ordinary-fast
  and burst handwriting is still pending for the latest changes. See the
  [handoff](docs/HANDOFF.md) for the recorded results.
- This is a raster editor with a fixed-size canvas. Infinite canvas, vector
  editing and a built-in multi-document gallery are not implemented.

When reporting a problem, include the tablet model, firmware, APK version,
selected tool and size, zoom/rotation, and steps to reproduce it.

## Build and contribute

The app is written in Kotlin with the BOOX pen SDK. The published preview also
uses an editable Rust renderer for selected brushes. Windows PowerShell is the
documented development workflow.

For the default official-SDK build, after installing Android Studio and the SDK
components described in the [development guide](docs/DEVELOPMENT.md):

```powershell
git clone https://github.com/ignasiesbr/draw-in-boox.git
cd draw-in-boox
.\dev.ps1 doctor
.\dev.ps1 build
.\dev.ps1 run
```

The default build has a separate package from the published preview. To build the
preview's recovered renderer, follow [the additional native setup](docs/DEVELOPMENT.md#recovered-renderer-build).
The guide also covers device tests, local unit tests, diagnostics and troubleshooting.

Before changing pen input, rendering, brushes or history, read [AGENTS.md](AGENTS.md)
and [PERFORMANCE.md](docs/PERFORMANCE.md) to preserve the accepted drawing behavior.

| Documentation | Contents |
| --- | --- |
| [Development](docs/DEVELOPMENT.md) | Fresh-clone setup, variants, build/run/test and diagnostics |
| [Feature architecture](docs/FEATURES.md) | Where features live and how to extend them |
| [Brushes](docs/BRUSHES.md) and [BOOX pens](docs/BOOX_NOTES_PENS.md) | Width calculations, firmware integration and pen matching |
| [Save format](docs/SAVE_FORMAT.md) | Portable `.boox` file contract |
| [Handoff](docs/HANDOFF.md) | Development history, physical feedback and outstanding validation |
| [Plan](plan.md) | Milestones and future work |

The recovered renderer's source and LGPL-3.0 license notices are in
[`experimental/boox-renderer`](experimental/boox-renderer/README.md); its license
texts are also bundled in the recovered APK.
