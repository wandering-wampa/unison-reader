param([string]$Prefix = 'sync')
# Captures the library, book details, and sync sheet of the active book from the emulator
# using only ADB input; no desktop automation. Screenshots land in ignored test-results/.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1"
New-Item -ItemType Directory -Force test-results | Out-Null
function Shot([string]$Name) { & adb -e exec-out screencap -p > "test-results/$Prefix-$Name.png"; Write-Host "captured $Prefix-$Name.png" }
& adb -e shell input keyevent KEYCODE_WAKEUP
& adb -e shell wm dismiss-keyguard
& adb -e shell am force-stop dev.homescreenreader
& adb -e shell am start -n dev.homescreenreader/.MainActivity | Out-Null
Start-Sleep 3
Shot 'library'
# Open details of the hero book (tap the hero cover) by accessibility text through uiautomator dump.
& adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
$xml = [xml](& adb -e shell cat /sdcard/ui.xml)
function Tap([string]$Desc) {
    $node = $xml.SelectSingleNode("//node[starts-with(@content-desc,'$Desc')]")
    if (-not $node) { throw "No node starting with '$Desc'" }
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2; $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        & adb -e shell input tap $x $y
    }
}
Tap 'Cover of'
Start-Sleep 2
Shot 'details'
& adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
$xml = [xml](& adb -e shell cat /sdcard/ui.xml)
Tap 'Open Reading & listening'
Start-Sleep 2
Shot 'sheet'
& adb -e shell input keyevent KEYCODE_BACK
