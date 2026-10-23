param(
    [string]$Ndk = "$PSScriptRoot\..\.tools\android-ndk-r28c",
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:RUSTUP_HOME = Join-Path $workspace '.tools\rustup'
$env:CARGO_HOME = Join-Path $workspace '.tools\cargo'
$env:PATH = "$env:CARGO_HOME\bin;C:\mingw64\bin;$env:PATH"
$manifest = Join-Path $workspace 'experimental\boox-renderer\Cargo.toml'
$cargo = Join-Path $env:CARGO_HOME 'bin\cargo.exe'
$rustup = Join-Path $env:CARGO_HOME 'bin\rustup.exe'
if (!(Test-Path -LiteralPath $cargo)) { throw 'Workspace-local Rust is missing; see experimental/boox-renderer/README.md.' }
$compiler = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd'
if (!(Test-Path -LiteralPath $compiler)) { throw "NDK compiler missing: $compiler" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $compiler
$toolchain = '1.96.1-x86_64-pc-windows-gnu'
& $rustup target add --toolchain $toolchain aarch64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Android Rust target setup failed' }
if ($Test) {
    & $cargo "+$toolchain" test --locked --manifest-path $manifest --lib
    if ($LASTEXITCODE -ne 0) { throw 'Recovered renderer host tests failed' }
}
& $cargo "+$toolchain" build --locked --manifest-path $manifest --release --target aarch64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Recovered renderer Android build failed' }

$artifactRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.onyx.android.sdk\onyxsdk-pennative\1.0.5'
$originalAar = @(Get-ChildItem -LiteralPath $artifactRoot -Recurse -Filter '*.aar')
if ($originalAar.Count -ne 1) { throw 'Resolve the official app dependencies first; expected one pennative 1.0.5 AAR.' }
$destination = Join-Path $workspace '.tools\recovered-renderer'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::OpenRead($originalAar[0].FullName)
$bindings = Join-Path $destination 'pennative-bindings.jar'
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $bindings, $true)
} finally { $archive.Dispose() }
$jniDirectory = Join-Path $destination 'jni\arm64-v8a'
$licenseDirectory = Join-Path $destination 'assets\licenses\recovered-renderer'
New-Item -ItemType Directory -Path $jniDirectory, $licenseDirectory -Force | Out-Null
$nativeLibrary = Join-Path $workspace 'experimental\boox-renderer\target\aarch64-linux-android\release\libneopen_jni.so'
Copy-Item -LiteralPath $nativeLibrary -Destination (Join-Path $jniDirectory 'libneopen_jni.so') -Force
foreach ($license in @('LICENSE.txt', 'GPL-3.0.txt')) {
    Copy-Item -LiteralPath (Join-Path $workspace "experimental\boox-renderer\$license") -Destination $licenseDirectory -Force
}
Get-FileHash -LiteralPath $nativeLibrary, $bindings -Algorithm SHA256
