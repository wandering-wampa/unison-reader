# Starts the phone emulator, or with -Watch the Wear OS one (AVD UnisonWatch_API37, made once with
#   avdmanager create avd -n UnisonWatch_API37 -k "system-images;android-37.0;android-wear-signed;x86_64" -d wearos_large_round
# ). With both running, adb's -e no longer says which one: name it (-s emulator-5554 is whichever started first).
# -Cold starts without the saved snapshot (the Wear emulator's network stays dead after airplane mode until a cold start).
param([switch]$Headless, [switch]$Watch, [switch]$Cold)
. "$PSScriptRoot\Enter-Android.ps1"
$emulatorArgs = @('-avd', $(if ($Watch) { 'UnisonWatch_API37' } else { 'HomescreenReader_API36' }), '-gpu', 'software')
$emulatorWindowStyle = 'Normal'
if ($Cold) { $emulatorArgs += '-no-snapshot-load' }
if ($Headless) {
    $emulatorArgs += @('-no-window', '-no-audio')
    $emulatorWindowStyle = 'Hidden'
}
Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList $emulatorArgs -WindowStyle $emulatorWindowStyle
