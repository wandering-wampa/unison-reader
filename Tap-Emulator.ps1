# Drives the emulator's real controls from a fresh UI dump, without touching the Windows mouse or keyboard.
# Usage:
#   .\Tap-Emulator.ps1 -List                         # every text and content description on screen
#   .\Tap-Emulator.ps1 -Text "Continue reading"      # tap the first node whose text is exactly this
#   .\Tap-Emulator.ps1 -Desc "Open audiobook player" # ... whose content description is exactly this
#   .\Tap-Emulator.ps1 -Match 'Chapter 012'          # ... whose text matches this regular expression
#   .\Tap-Emulator.ps1 -Text "Stay here" -Serial <serial>   # another device
# Prints what it tapped, or "not found"; dumps can lag a screen that is still opening, so wait a second or two first.
param([string]$Text, [string]$Desc, [string]$Match, [switch]$List, [string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { . "$PSScriptRoot\Enter-Android.ps1" | Out-Null }
adb -s $Serial shell uiautomator dump /sdcard/ui.xml | Out-Null
$xml = (adb -s $Serial shell cat /sdcard/ui.xml) -join ''
if ($List) {
    [regex]::Matches($xml, '(text|content-desc)="([^"]+)"') | ForEach-Object { [System.Net.WebUtility]::HtmlDecode($_.Groups[2].Value) } | Select-Object -Unique
    return
}
$pattern = if ($Text) { 'text="' + [regex]::Escape($Text) + '"' } elseif ($Desc) { 'content-desc="' + [regex]::Escape($Desc) + '"' } elseif ($Match) { 'text="[^"]*(?:' + $Match + ')[^"]*"' } else { throw 'Give -Text, -Desc, -Match or -List.' }
$m = [regex]::Match($xml, $pattern + '[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) { Write-Output "not found: $Text$Desc$Match"; return }
$x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
$y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
adb -s $Serial shell input tap $x $y
Write-Output "tapped $Text$Desc$Match at $x,$y"
