# Provision "Strong mode" (device owner) for Behavioural Lock.
#
# Strong mode makes the app the ONLY lock on the phone — the system keyguard is
# disabled. That is stronger, but it requires the app to be a "device owner",
# which Android only allows on a freshly-reset phone that has never had an
# account added to it.
#
# If this script fails, that is normal and fine: the app still works in
# "compatible mode", where your normal phone PIN stays underneath the app's lock
# as a fallback. Strong mode is an optional upgrade, not a requirement.
#
# Usage:
#   .\provision-device-owner.ps1
#   (phone must be connected by USB with USB debugging enabled)

$ErrorActionPreference = "Stop"
$package = "com.block.behaviortlock"

Write-Host "`n=== Behavioural Lock - Strong Mode Setup ===`n" -ForegroundColor Cyan

Write-Host "1. Checking for connected device..." -ForegroundColor Yellow
$devices = & adb devices | Select-String "device$"
if (-not $devices) {
    Write-Host "`nERROR: No device found. Connect the phone by USB and enable USB debugging." -ForegroundColor Red
    Write-Host "Then run this script again.`n" -ForegroundColor Red
    exit 1
}
Write-Host "   Device found.`n" -ForegroundColor Green

Write-Host "2. Checking whether the app is installed..." -ForegroundColor Yellow
$installed = & adb shell pm list packages | Select-String $package
if (-not $installed) {
    Write-Host "`nERROR: App not found. Build and install it first:" -ForegroundColor Red
    Write-Host "  .\gradlew.bat :app:assembleDebug" -ForegroundColor White
    Write-Host "  adb install -r app\build\outputs\apk\debug\app-debug.apk`n" -ForegroundColor White
    exit 1
}
Write-Host "   App is installed.`n" -ForegroundColor Green

Write-Host "3. Attempting to set the app as device owner..." -ForegroundColor Yellow
Write-Host "   (This only succeeds on a freshly factory-reset phone.`n" -ForegroundColor DarkGray

$result = & adb shell dpm set-device-owner "$package/com.block.behaviortlock.AdminReceiver" 2>&1

if ($result -match "Success|set to") {
    Write-Host "`n   SUCCESS - Strong mode is available!`n" -ForegroundColor Green
    Write-Host "   Open the app > Settings > turn on 'Strong mode'.`n" -ForegroundColor Green
} else {
    Write-Host "`n   Could not become device owner (this is expected on most phones).`n" -ForegroundColor Yellow
    Write-Host "   Reason: $result`n" -ForegroundColor DarkGray
    Write-Host "   The app still works WITHOUT strong mode:" -ForegroundColor Cyan
    Write-Host "     * Grant device admin permission in Settings when the app asks." -ForegroundColor White
    Write-Host "     * Your normal phone PIN stays as the safety fallback." -ForegroundColor White
    Write-Host "     * Strong mode would additionally disable the system lock." -ForegroundColor White
}

Write-Host "`n=== Done ===`n" -ForegroundColor Cyan