param(
    [ValidateSet('doctor', 'build', 'run', 'capture', 'test')][string]$Command = 'doctor',
    [string]$Serial,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [ValidateSet('official', 'recovered')][string]$Renderer = 'official',
    [switch]$Lab,
    [switch]$FountainOnly,
    [switch]$BrushesOnly,
    [switch]$DocumentsOnly,
    [switch]$NotesOnly
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$adbPath = Join-Path $Sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adbPath)) { throw "ADB not found: $adbPath. Supply -Sdk." }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$variantArgs = @("-PbooxRenderer=$Renderer")
$applicationPackage = 'art.boox.probe'
if ($Lab -or $Renderer -eq 'recovered') {
    $variantArgs += '-PbooxLab=true'
    $applicationPackage += '.lab'
}

function Invoke-Adb {
    & $adbPath @script:deviceArgs @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
function Invoke-Build {
    & "$PSScriptRoot\gradlew.bat" @script:variantArgs @args
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
}
$deviceArgs = @()
if ($Command -eq 'doctor') {
    & "$env:JAVA_HOME\bin\java.exe" -version
    Invoke-Adb version
    Invoke-Adb devices -l
    Write-Output "SDK: $Sdk"
    Get-ChildItem "$Sdk\platforms", "$Sdk\build-tools" -Name
    exit
}
if ($Command -eq 'build') { Invoke-Build :app:assembleDebug :app:lintDebug; exit }

$devices = & $adbPath devices
if ($LASTEXITCODE -ne 0) { throw 'Unable to list devices' }
if (-not $Serial) {
    $authorized = @($devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($authorized.Count -ne 1) { throw 'Connect one authorized device, or supply -Serial.' }
    $Serial = ($authorized[0] -split '\s+')[0]
}
$deviceArgs = @('-s', $Serial)
if ($Command -in @('run', 'test')) {
    $runningApp = & $adbPath @deviceArgs shell pidof $applicationPackage
    if ($LASTEXITCODE -eq 0 -and $runningApp) {
        # Package replacement can kill the process without onPause. Visit Home first so it saves.
        Invoke-Adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
    }
}
switch ($Command) {
    'run' {
        Invoke-Build :app:assembleDebug
        Invoke-Adb install -r app/build/outputs/apk/debug/app-debug.apk
        Invoke-Adb shell pm enable --user 0 $applicationPackage
        $launch = Invoke-Adb shell am start -W -n "$applicationPackage/art.boox.probe.MainActivity"
        $launch
        if (($launch -join "`n") -match 'Error:|Error type') { throw 'App launch failed; inspect device package state.' }
    }
    'test' {
        Invoke-Build :app:assembleDebug :app:assembleDebugAndroidTest
        Invoke-Adb install -r app/build/outputs/apk/debug/app-debug.apk
        Invoke-Adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
        Invoke-Adb shell pm enable --user 0 $applicationPackage
        $instrumentArgs = @('-w')
        if ($DocumentsOnly) { $instrumentArgs += @('-e', 'documentsOnly', 'true') }
        if ($NotesOnly) { $instrumentArgs += @('-e', 'notesOnly', 'true') }
        if ($BrushesOnly) {
            if (!($Lab -or $Renderer -eq 'recovered')) { throw '-BrushesOnly requires -Lab or -Renderer recovered.' }
            $instrumentArgs += @('-e', 'brushesOnly', 'true')
        }
        if ($FountainOnly) {
            if (!($Lab -or $Renderer -eq 'recovered')) { throw '-FountainOnly requires -Lab or -Renderer recovered.' }
            $instrumentArgs += @('-e', 'fountainOnly', 'true')
        }
        $output = Invoke-Adb shell am instrument @instrumentArgs "$applicationPackage.test/art.boox.probe.RasterInstrumentation"
        $output
        $expected = if ($NotesOnly) { 'PASS: BOOX notes pen checks' } elseif ($DocumentsOnly) { 'PASS: portable document checks' } elseif ($BrushesOnly) { 'PASS: brush engine checks' } elseif ($FountainOnly) { 'PASS: fountain renderer checks' } else { 'PASS: bitmap' }
        if (($output -join "`n") -notmatch $expected) { throw 'Device checks failed' }
    }
    'capture' {
        $destination = Join-Path $PSScriptRoot ('captures\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Invoke-Adb shell screencap -p /sdcard/boox-art-capture.png
        Invoke-Adb pull /sdcard/boox-art-capture.png "$destination\screen.png"
        Invoke-Adb shell rm /sdcard/boox-art-capture.png
        Invoke-Adb logcat -d -s BooxProbe BooxPerf AndroidRuntime | Set-Content "$destination\logcat.txt"
        Invoke-Adb shell run-as $applicationPackage cat files/strokes.jsonl | Set-Content "$destination\strokes.jsonl"
        Invoke-Adb shell run-as $applicationPackage cat files/document-state.json | Set-Content "$destination\document-state.json"
        foreach ($property in @('ro.product.model', 'ro.build.version.sdk', 'ro.build.display.id')) {
            "$property=$(Invoke-Adb shell getprop $property)" | Add-Content "$destination\device.txt"
        }
        Write-Output "Saved capture: $destination"
    }
}
