# Native preview fitting

Physical feedback, 2026-10-24: on `brush perf v1`, Pencil preview was much thicker
than committed ink; Charcoal was thicker and lighter. Fountain and Brush worked
well. Capture `captures/20261024-100014` preserves the session. The user chose to
preserve final appearance and use BOOX input as much as possible, including at 13.3px.

`preview fit v1` adds preview-only settings to Pencil and Charcoal:

- Fast preview thickness, 25–100%, initially 50%.
- Fine grain (CHARCOAL_V2) or Coarse grain (CHARCOAL). Both initially use Fine;
  Charcoal's previous Coarse option remains available for comparison.

These are starting points for physical comparison, not measured calibration.
They change only paused native setup. Rust geometry, grain masks, pressure, tilt,
shade and final bitmap/history are unchanged. There are no contact-time setters
or second pressure curves on these vendor-dynamic previews. Fountain, Brush and
the original tools keep their existing native parameters and final rendering.

The guarded dry-preview envelope now follows configured preview size:
`base = final size * preview factor`, `envelope = base * 2.5`. Retain 2.5 even
when app dynamics are off, since firmware owns its own dynamics. The envelope
still must be <=30px, keeping configured native bases <=12px. A 13.3px final Pencil
at 50% configures 6.65px native base / 16.625px envelope, so remains native.
At 50%, eligibility extends through 24px; 25% permits up to 48px. Larger final
sizes do not send a wider native configuration. Gray/eraser rules are unchanged.
This remains a compatibility policy, not a measured physical firmware footprint.

## Runtime crash-guard setting

User request, 2026-10-24: make the workaround optional so native gray/wide behavior
can be investigated rather than assuming the observed crash happens frequently.
On 2026-10-25 the user requested a persistent runtime control at the bottom of
Settings. **Advanced > Firmware crash guard** replaces the compile-time constant:

- `true` (default): enforce native gray and width/footprint limits.
- `false`, then Apply: allow native painting with the selected shade and wide
  configured width, and remove the eraser's crash-related document/screen width
  caps. This does not force Android fallback for those crash-related reasons.

Eraser still requires one visible/unlocked full-opacity layer and alpha lock off:
these rules keep white feedback consistent with CLEAR semantics. Reader readiness,
paused native setup, constant-brush setter suppression, final brush appearance,
preview-fitting settings and the asynchronous handoff remain active in either mode.
The preference is saved per app, applies immediately without restarting/rebuilding,
and supports both official and recovered builds. Cancel discards the checkbox draft.
ProbeApplication/MainActivity initialize the cached policy from AppSettingsStore.
Apply requires idle input, closes/publishes the old native session, saves settings,
updates NativePreviewPolicy.firmwareCrashGuardEnabled, and schedules pen setup
after layout. No preference reads occur in pen callbacks. Stroke diagnostics capture
the mode before queuing serialization, so a later toggle cannot relabel old strokes.

Startup and document/stroke diagnostics record the flag. Guard-off mode appends
`[guard off]` to its heading. One captured SurfaceFlinger abort establishes an
incident, not its recurrence rate. Guard-off eligibility is tested without sending
gray/wide parameters to actual firmware; device tests keep the default guard on.
Runtime tests cover default-on, persistence/reload, Apply/Cancel and the actual
Activity settings handler. They restore the original mode before yielding UI and
do not open gray/wide guard-off sessions. Current header/log is guard settings v1 /
guard-settings-v1. The former source-code switch descriptions are historical.
Recovered build/lint, the targeted settings checks and the clean-session full
Go103 suite pass. An earlier full run stalled in CanvasDialogChecks.startActivitySync
before settings testing; restarting that test process resolved the harness stall.

The verified TouchHelper API exposes style, width and color, but no separate
textured-ink darkness setter. StrokeConfig has an opaque params array whose
meaning is not verified; this change does not guess at its contents. No native
gray or alpha-color workaround is used to change tone. Native texture/darkness
can still differ; exact app preview is available through existing Android pen mode.

Preference schema 3 preserves sizes/dynamics/shades from schemas 1–2 and adds
per-tool scale/texture. Missing or invalid fields fall back to the new preview
defaults. Diagnostics retain point schema 9, record settingsVersion 3 and include
configured base, declared native envelope and actual style ID.

Build/lint and full Go103 suite pass. Checks cover 13.3px real SDK open/reopen
for both textures, reader readiness, guard boundaries, gray rejection, unchanged
other tools, preference migration/controls and exact final-pixel equality across
preview factors/textures. Physical fitting still needs user feedback.
