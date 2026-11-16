# Force Android Runtime to AOT-compile Nuvio One on a connected device.
#
# This is the optional extra step from the community "make sideloaded Nuvio faster"
# guide. The app already ships baseline profiles, so this is only for users who want
# the full `speed` compilation on a specific TV.
#
# Usage:
#   .\scripts\adb-speed-compile.ps1 [-Package app.onetube.tv] [-Serial 192.168.1.50:5555]
#
# The package defaults to the release build. Use app.onetube.tv.test for a test
# build or com.nuvio.livetv for a debug build.

param(
    [string]$Package = "app.onetube.tv",
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Adb = $env:ADB
)

if (-not $Adb) { $Adb = "adb" }

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb not found. Install Android platform-tools or set the ADB environment variable."
    exit 1
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

& $Adb @adbArgs get-state *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Error "No device. Connect the TV and run: adb connect <tv-ip>:5555"
    exit 1
}

Write-Host "Compiling $Package. This can take a minute on a TV."
& $Adb @adbArgs shell cmd package compile -m speed -f $Package
if ($LASTEXITCODE -ne 0) {
    # Older devices only have the pm wrapper.
    & $Adb @adbArgs shell pm compile -m speed -f $Package
}

Write-Host ""
Write-Host "Dexopt state:"
& $Adb @adbArgs shell dumpsys package dexopt | Select-String -Pattern $Package -Context 0, 1
Write-Host ""
Write-Host "Done. Re-run this after every app update."
