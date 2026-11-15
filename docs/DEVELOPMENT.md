# Local development

Run commands from the repository root in **Windows PowerShell**. Windows is the
documented workflow for Android Studio, USB ADB and the native renderer toolchain.
The Gradle wrapper is included; a separate Gradle installation is unnecessary.

## Prerequisites

- Git and Android Studio with its bundled Java runtime. This workspace was
  validated with Studio's JBR 25; Java/Kotlin bytecode targets Java 17.
- In Android Studio's SDK Manager, install **Android SDK Platform 37**,
  **Android SDK Build-Tools 36.0.0**, and **Android SDK Platform-Tools**.
- For installation and device tests, a BOOX tablet with Developer options and
  **USB debugging** enabled. Connect it by USB and accept its authorization prompt.
  Hardware pen behavior requires a real BOOX device; an emulator cannot validate it.
- Internet access for the first Gradle build, including the BOOX Maven repositories.

Build configuration is pinned in the repository: AGP 9.2.1, Gradle 9.4.1,
compile SDK 37, minimum Android API 29, target API 32 and ARM64 native libraries.
Compiling against SDK 37 does not require the tablet to run that Android API level.

## First build

```powershell
git clone https://github.com/ignasiesbr/draw-in-boox.git
cd draw-in-boox
```

Open the repository in Android Studio and let it configure the Android SDK path
in `local.properties`. This file is machine-specific and ignored by Git. A manual
Windows example, using forward slashes, is:

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

The helper script uses `C:\Program Files\Android\Android Studio\jbr` when
`JAVA_HOME` is unset. If Studio is installed elsewhere, set the runtime path for
your PowerShell session:

```powershell
$env:JAVA_HOME = 'D:\Android Studio\jbr'
```

Then check the tools, build the APK and run lint:

```powershell
.\dev.ps1 doctor
.\dev.ps1 build
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A tablet is
not required to build, but `dev.ps1` expects ADB to be installed even for `build`.

With one authorized tablet connected:

```powershell
.\dev.ps1 run
```

`run` builds, installs and launches the app. If it is already running, the script
visits Home first so it can checkpoint its drawing before package replacement.
It does not uninstall the app or clear its data.

For a different SDK location or multiple devices:

```powershell
.\dev.ps1 doctor -Sdk 'D:\Android\Sdk'
.\dev.ps1 run -Sdk 'D:\Android\Sdk' -Serial 'YOUR_DEVICE_SERIAL'
```

`-Sdk` selects ADB and the SDK inspected by `doctor`. Gradle uses `local.properties`
separately, so update both when using a nondefault SDK location.

## Choose a build

All variants display the name **Draw in Boox**. Their package IDs determine which
installation and private drawing data they use.

| Build | Helper argument | Package ID | Purpose |
| --- | --- | --- | --- |
| Official SDK | No extra argument | `art.boox.probe` | Default build; no Rust setup needed |
| Official SDK with Lab tools | `-Lab` | `art.boox.probe.lab` | Compare Lab tools using the original SDK renderer |
| Recovered renderer | `-Renderer recovered` | `art.boox.probe.lab` | Current published preview; adds the editable Rust renderer |

Fountain and Brush require a Lab build. The custom Studio Pencil/Charcoal presets
are currently hidden from the toolbar; the visible fine/coarse Pencils use the
original BOOX SDK. Notes-style pens retain the original BOOX native library in all
variants. The recovered library has separate JNI names and does not replace firmware.

Official Lab and recovered builds share a package and replace one another when
signed with the same key. The default official build can coexist with them.
Every build writes to the same local APK output path, so copy an APK elsewhere
before building a different variant if you need both files.

## Recovered renderer build

The recovered build needs a source-built native library in `.tools`, which is
ignored by Git and absent from a fresh clone. First complete an official build
above to populate the BOOX dependency cache.

The native build script expects this tool layout:

| Tool | Expected location |
| --- | --- |
| Rustup and Cargo | `.tools/cargo/bin/rustup.exe` and `.tools/cargo/bin/cargo.exe` |
| Rust toolchain | `1.96.1-x86_64-pc-windows-gnu`, in `.tools/rustup` |
| Android NDK r28c | `.tools/android-ndk-r28c`, or pass its root with `-Ndk` |
| MinGW-w64 GCC for host tests | `C:\mingw64\bin\gcc.exe` |

Create `.tools`, obtain the Windows `rustup-init.exe` installer from
[the Rust installation page](https://www.rust-lang.org/tools/install), and save it
as `.tools/rustup-init.exe`. From the repository root:

```powershell
New-Item -ItemType Directory -Force .tools | Out-Null
```

After saving the installer, install the workspace-local toolchain:

```powershell
$env:RUSTUP_HOME = Join-Path $PWD '.tools/rustup'
$env:CARGO_HOME = Join-Path $PWD '.tools/cargo'
& .\.tools\rustup-init.exe -y --no-modify-path --profile minimal --default-toolchain 1.96.1-x86_64-pc-windows-gnu
```

Install/extract NDK r28c at the path above and provide MinGW-w64 for the GNU host
toolchain. The script adds the Android Rust target, runs host tests with `-Test`,
builds the ARM64 shared library, and copies it and its license texts into Gradle's
native/assets input directories:

```powershell
.\scripts\build-recovered-renderer.ps1 -Test
.\dev.ps1 build -Renderer recovered
.\dev.ps1 run -Renderer recovered
```

If the NDK is elsewhere, use its installation root:

```powershell
.\scripts\build-recovered-renderer.ps1 -Test -Ndk 'D:\Android\android-ndk-r28c'
```

Rebuild the native library after changing Rust sources; Gradle does not invoke
Cargo automatically. The script remaps local source paths to generic paths in
compiled Rust diagnostics so APKs do not disclose the developer's home directory.
See [the renderer README](../experimental/boox-renderer/README.md)
and [experiment notes](RECOVERED_RENDERER_EXPERIMENT.md) for provenance and the
firmware boundary. Historical packaging instructions in those notes are superseded
by their opening update: keep `libboox_art_engine.so` isolated beside the original
`libneopen_jni.so`.

## Tests

Run local JVM tests without a tablet. When calling Gradle directly, set `JAVA_HOME`
first if needed; `dev.ps1`'s automatic Java selection does not apply to direct calls.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

After native setup, the published preview's variant can be built and checked together:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -PbooxRenderer=recovered
```

For device instrumentation, keep the tablet awake and unlocked. Save important
drawings as `.boox` files before testing. Run one instrumentation session at a time.

```powershell
.\dev.ps1 test                         # default official build
.\dev.ps1 test -Renderer recovered     # recovered build
.\dev.ps1 run -Renderer recovered      # reopen after tests
```

`test` builds and installs the app and test APK, runs instrumentation, and leaves
the app stopped. Available focused suites use one flag per invocation:

```powershell
.\dev.ps1 test -Renderer recovered -HistoryOnly
.\dev.ps1 test -Renderer recovered -GesturesOnly
.\dev.ps1 test -Renderer recovered -DocumentsOnly
.\dev.ps1 test -Renderer recovered -ImagesOnly
.\dev.ps1 test -Renderer recovered -NotesOnly
.\dev.ps1 test -Renderer recovered -BrushesOnly
.\dev.ps1 test -Renderer recovered -FountainOnly
```

`-BrushesOnly` and `-FountainOnly` require a Lab build. Local JVM reports are in
`app/build/reports/tests/testDebugUnitTest/`; lint reports are under
`app/build/reports/`.

The published preview passed APK/test-APK compilation, lint and 10 local history
compression tests. Earlier focused Go103 suites passed, but full device validation
is not green: partial/full-frame comparison at 0°/25% zoom and asynchronous live-zoom
comparison at 37° have recorded failures. See [HANDOFF.md](HANDOFF.md) before
interpreting results. Automated checks do not establish physical pen responsiveness.

## Diagnostics

Capture the running variant after reproducing an issue:

```powershell
.\dev.ps1 capture -Renderer recovered
```

For an official build, omit `-Renderer recovered`; use `-Lab` for official Lab.
The command writes `captures/yyyyMMdd-HHmmss/` with a screenshot, filtered logcat,
stroke diagnostics, document-state metadata and device information. These are
diagnostics, not a complete editable drawing backup. The directory is ignored by Git.

For live input and performance logs:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -v threadtime -s BooxProbe BooxPerf AndroidRuntime
```

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No authorized tablet | Run `dev.ps1 doctor`; enable USB debugging, accept the tablet prompt and check the cable. Use `-Serial` if several devices are connected. |
| SDK or Java not found | Check `local.properties`, `JAVA_HOME` and the helper's `-Sdk` path. Install Platform 37 and Build-Tools 36.0.0. |
| Dependency resolution fails | Check network access to Google, Maven Central and BOOX's Maven repositories listed in `settings.gradle.kts`. |
| Recovered library missing | Complete an official build, install the native toolchain and run `scripts/build-recovered-renderer.ps1` before Gradle. |
| APK update signature mismatch | A fresh machine's debug key differs from the published APK's key. Use the separate default package for development, or use the original signing key for an in-place update. Export `.boox` copies before replacing an installation. |
| Surface/layout test timeout | Wake and unlock the tablet, ensure no other instrumentation is running, and retry the focused check. |
| Pen preview differs after lifting | Check the selected tool, size, zoom and firmware crash guard; read [native preview safety](NATIVE_PREVIEW_SAFETY.md) and [brush notes](BRUSHES.md). |

## Where to make changes

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/art/boox/probe/` | Kotlin UI, document model, pen input, history and presentation |
| `app/src/main/res/` | Launcher icon and Android resources |
| `app/src/test/` | Local JVM tests |
| `app/src/androidTest/` | BOOX/device instrumentation checks |
| `experimental/boox-renderer/` | Editable Rust brush geometry and native tests |
| `scripts/build-recovered-renderer.ps1` | Native library build and packaging inputs |
| `dev.ps1` | Build, install, device test and capture helper |

Before changing input, brushes, history, screen presentation or lifecycle handling,
read [AGENTS.md](../AGENTS.md), [PERFORMANCE.md](PERFORMANCE.md) and the relevant
[feature architecture](FEATURES.md). Preserve native live ink, per-stroke artwork
commits, immutable bounded screen work and the accepted 450ms quiet-gap handoff.
Keep the saved firmware crash guard unchanged during unrelated work. Record new
physical feedback and remaining limits in the performance notes and handoff.
