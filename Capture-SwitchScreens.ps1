param([string]$Prefix = 'switch')
# Captures the reading screen's listen/pause/follow states through ADB only. Restores
# display size and font scale afterwards. Screenshots land in ignored test-results/.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1"
New-Item -ItemType Directory -Force test-results | Out-Null
$pkg = 'dev.homescreenreader'
function Shot([string]$Name) { & adb -e exec-out screencap -p > "test-results/$Prefix-$Name.png"; Write-Host "captured $Prefix-$Name.png" }
function Tap([string]$Desc) {
    & adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
    $xml = [xml](& adb -e shell cat /sdcard/ui.xml)
    $node = $xml.SelectSingleNode("//node[@content-desc='$Desc']")
    if (-not $node) { $node = $xml.SelectSingleNode("//node[@text='$Desc']") }
    if (-not $node) { throw "No node '$Desc'" }
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        & adb -e shell input tap (([int]$Matches[1] + [int]$Matches[3]) / 2) (([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}
function Visible([string]$Desc) {
    & adb -e shell uiautomator dump /sdcard/ui.xml | Out-Null
    return ((& adb -e shell cat /sdcard/ui.xml) -join '') -match [regex]::Escape("content-desc=`"$Desc`"")
}
function ShowChrome([int]$X, [int]$Y) {
    for ($i = 0; $i -lt 6; $i++) {
        if (Visible 'Reading options') { return }
        & adb -e shell input tap $X $Y; Start-Sleep 2
    }
    throw 'Reading controls did not appear'
}
$prefs = (& adb -e exec-out run-as $pkg cat shared_prefs/reading-v2.xml) -join ''
if ($prefs -notmatch 'name="active">([^<]+)<') { throw 'No active book' }
$book = $Matches[1]
& adb -e shell input keyevent KEYCODE_WAKEUP; & adb -e shell wm dismiss-keyguard
& adb -e shell am start-foreground-service -n "$pkg/.PlaybackService" --es command pause --es book $book | Out-Null
& adb -e shell am force-stop $pkg
& adb -e shell am start -n "$pkg/.MainActivity" --es book $book --ez read true | Out-Null
Start-Sleep 8
$size = (& adb -e shell wm size) -replace '.*: ', ''
$w = [int]($size -split 'x')[0]; $h = [int]($size -split 'x')[1]
ShowChrome ([int]($w / 2)) ([int]($h / 2))
Shot 'chrome-idle'
Tap 'Listen from this page'; Start-Sleep 3
Shot 'chrome-playing'
& adb -e shell input tap ([int]($w / 2)) ([int]($h / 2)); Start-Sleep 1
Shot 'dock-following'
# Turn two pages by tapping the right edge: browsing while narration continues.
& adb -e shell input tap ([int]($w * 0.92)) ([int]($h / 2)); Start-Sleep 1
& adb -e shell input tap ([int]($w * 0.92)) ([int]($h / 2)); Start-Sleep 2
Shot 'dock-browsing'
ShowChrome ([int]($w / 2)) ([int]($h / 2))
Shot 'chrome-browsing'
Tap 'Pause narration'; Start-Sleep 1
# Compact width and enlarged text.
& adb -e shell settings put system font_scale 1.5
& adb -e shell wm size 760x2400
Start-Sleep 5
ShowChrome 380 1200
Shot 'chrome-compact-idle'
Tap 'Listen from this page'; Start-Sleep 3
& adb -e shell input tap 380 1200; Start-Sleep 1
& adb -e shell input tap 700 1200; Start-Sleep 1; & adb -e shell input tap 700 1200; Start-Sleep 2
ShowChrome 380 1200
Shot 'chrome-compact-browsing'
Tap 'Pause narration'; Start-Sleep 1
& adb -e shell wm size reset
& adb -e shell settings put system font_scale 1.0
Start-Sleep 3
Write-Host 'restored display size and font scale'
