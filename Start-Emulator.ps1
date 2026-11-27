# Starts the phone emulator (AVD HomescreenReader_API36).
# -Cold starts without the saved snapshot.
param([switch]$Headless, [switch]$Cold)
. "$PSScriptRoot\Enter-Android.ps1"
$emulatorArgs = @('-avd', 'HomescreenReader_API36', '-gpu', 'software')
$emulatorWindowStyle = 'Normal'
if ($Cold) { $emulatorArgs += '-no-snapshot-load' }
if ($Headless) {
    $emulatorArgs += @('-no-window', '-no-audio')
    $emulatorWindowStyle = 'Hidden'
}
Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList $emulatorArgs -WindowStyle $emulatorWindowStyle
