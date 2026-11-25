param([string]$Prefix = 'shelves')
# Captures the three-shelf library and the sync overview through ADB only. Run after
# `Test-Emulator.ps1 -Modes shelves` has staged the sample books; `-Modes cleanup` removes them.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1"
New-Item -ItemType Directory -Force test-results | Out-Null
$pkg = 'dev.homescreenreader'
function Shot([string]$Name) { & adb -e exec-out screencap -p > "test-results/$Prefix-$Name.png"; Write-Host "captured $Prefix-$Name.png" }
function Dump() { & adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null; return [xml](& adb -e shell cat /sdcard/ui.xml) }
function Tap([string]$Desc) {
    $xml = Dump
    $node = $xml.SelectSingleNode("//node[@content-desc='$Desc']")
    if (-not $node) { $node = $xml.SelectSingleNode("//node[@text='$Desc']") }
    if (-not $node) { throw "No node '$Desc'" }
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        & adb -e shell input tap (([int]$Matches[1] + [int]$Matches[3]) / 2) (([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}
& adb -e shell input keyevent KEYCODE_WAKEUP; & adb -e shell wm dismiss-keyguard
& adb -e shell am force-stop $pkg
& adb -e shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep 4
Shot 'library-top'
& adb -e shell input swipe 540 1900 540 700 400; Start-Sleep 1
Shot 'library-shelves'
& adb -e shell input swipe 540 1900 540 700 400; Start-Sleep 1
Shot 'library-bottom'
& adb -e shell input swipe 540 700 540 2100 300; Start-Sleep 1
& adb -e shell input swipe 540 700 540 2100 300; Start-Sleep 1
Tap 'Sync overview for 11/22/63'; Start-Sleep 2
Shot 'overview-synced'
& adb -e shell input swipe 540 1900 540 600 400; Start-Sleep 1
Shot 'overview-chapters'
& adb -e shell input keyevent KEYCODE_BACK; Start-Sleep 2
# The paired sample: start its sync from the shelf and open its overview right away.
& adb -e shell input swipe 540 1900 540 700 400; Start-Sleep 1
Tap 'Sync overview for shelf-pair'; Start-Sleep 1
Shot 'overview-pair-before'
Tap 'Sync now for shelf-pair'; Start-Sleep 1
Shot 'overview-pair-syncing'
Start-Sleep 8
Shot 'overview-pair-done'
& adb -e shell input keyevent KEYCODE_BACK; Start-Sleep 2
Shot 'library-after'
