# Cuts an app release: bumps the version, builds the phone APK and the watch APK, commits and tags it, pushes, and
# publishes both as a GitHub release with their SHA-256. Run from a clean, pushed master.
#
# Usage:
#   .\Release.ps1 -Version 0.8.0 -DryRun          # checks, builds with the new version, prints the hash; changes nothing
#   .\Release.ps1 -Version 0.8.0                  # the release; notes default to the app's commits since the last tag
#   .\Release.ps1 -Version 0.8.0 -Notes 'Since 0.7.0: ...'   (or -NotesFile notes.md)
#
# The APKs are the debug-signed sideload builds, as before: they install over earlier versions and keep the library,
# positions and sign-in. The watch app (wear/) takes its version from the phone app's, so one bump covers both.
# The server's own version label (Version.NAME in Main.kt, what /api/health answers) is set in the same commit, so
# the code at a tag always calls itself by that tag's version.
# The server is deployed on its own with Deploy-Server.ps1.
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$Notes,
    [string]$NotesFile,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1" | Out-Null

$gradleFile = 'app\build.gradle.kts'
$serverFile = 'server\src\main\kotlin\dev\unison\server\Main.kt'
$tag = "v$Version"
function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function RunGit { $out = & git.exe @args 2>&1; if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed: $out" }; $out }

# ---- Checks: nothing is changed until all pass.
Step 'Checks'
# A dry run reports what would stop a release and carries on; the release itself stops.
function Refuse($why) { if ($DryRun) { Write-Warning "A release would stop here: $why" } else { throw $why } }
$branch = RunGit rev-parse --abbrev-ref HEAD
if ($branch -ne 'master') { Refuse "Release from master (on $branch)." }
if (RunGit status --porcelain) { Refuse 'The working tree has changes; commit or stash them first.' }
RunGit fetch --quiet --tags origin | Out-Null
if ((RunGit rev-parse HEAD) -ne (RunGit rev-parse origin/master)) { Refuse 'master is not the same as origin/master; push or pull first.' }
if (RunGit tag --list $tag) { throw "Tag $tag already exists." }

$gradle = Get-Content $gradleFile -Raw
$server = Get-Content $serverFile -Raw
if ($server -notmatch 'const val NAME = "[^"]+"') { throw "The server's version label (const val NAME) was not found in $serverFile." }
$code = [int]([regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value)
$current = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if ([version]$Version -le [version]$current) { throw "Version $Version is not above the current $current." }
$previousTag = (RunGit tag --list 'v*' --sort=-v:refname | Select-Object -First 1)
if (-not $DryRun) {
    & gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh is not signed in (gh auth login).' }
}
Write-Host "$current (code $code) -> $Version (code $($code + 1)); previous tag $previousTag"

# ---- Notes: given, from a file, or the app's own commits since the previous tag.
if ($NotesFile) { $Notes = Get-Content $NotesFile -Raw }
if (-not $Notes) {
    $since = if ($previousTag) { "$previousTag..HEAD" } else { 'HEAD' }
    $subjects = RunGit log $since --no-merges --format=%s -- app core
    if (-not $subjects) { throw "No app changes since $previousTag; nothing to release." }
    $Notes = "Since $($previousTag.TrimStart('v')):`n" + (($subjects | ForEach-Object { "- $_" }) -join "`n")
}

# ---- Bump and build. A failure or a dry run puts the version file back.
Step 'Build'
$bumped = $gradle -replace 'versionCode = \d+', "versionCode = $($code + 1)" -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
Set-Content $gradleFile $bumped -NoNewline
Set-Content $serverFile ($server -replace 'const val NAME = "[^"]+"', "const val NAME = `"$Version`"") -NoNewline
try {
    # The packager patches the last APK in place: after a change of edition (or any big removal) the file keeps its old size unless it starts fresh.
    Remove-Item 'app\build\outputs\apk\debug\app-debug.apk', 'app\build\intermediates\incremental\packageDebug' -Recurse -Force -ErrorAction SilentlyContinue
    & .\gradlew.bat :app:assembleDebug :wear:assembleDebug --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'App build failed.' }
    $outDir = 'build\release'
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $apk = Join-Path $outDir "UnisonReader-$Version.apk"
    Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' $apk -Force
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash
    $size = (Get-Item $apk).Length
    Write-Host "$apk  $('{0:N0}' -f $size) bytes`nSHA-256 $hash"
    $wearApk = Join-Path $outDir "UnisonReader-wear-$Version.apk"
    Copy-Item 'wear\build\outputs\apk\debug\wear-debug.apk' $wearApk -Force
    $wearHash = (Get-FileHash $wearApk -Algorithm SHA256).Hash
    $wearSize = (Get-Item $wearApk).Length
    Write-Host "$wearApk  $('{0:N0}' -f $wearSize) bytes`nSHA-256 $wearHash"
} catch {
    Set-Content $gradleFile $gradle -NoNewline
    Set-Content $serverFile $server -NoNewline
    throw
}
if ($DryRun) {
    Set-Content $gradleFile $gradle -NoNewline
    Set-Content $serverFile $server -NoNewline
    Step 'Dry run: nothing committed, tagged, pushed or published. The release notes would read:'
    Write-Host $Notes
    Write-Warning "app\build\outputs\apk\debug\app-debug.apk now carries version code $($code + 1). Rebuild before installing it: a later build with code $code would not install over it."
    return
}

# ---- Commit, tag, push.
Step 'Commit and tag'
RunGit add $gradleFile $serverFile | Out-Null
RunGit commit -q -m "UnisonReader $Version" | Out-Null
RunGit tag -a $tag -m "UnisonReader $Version" | Out-Null
RunGit push -q origin master | Out-Null
RunGit push -q origin $tag | Out-Null
Write-Host "Pushed master and $tag."

# ---- Publish, then check the uploaded file is the one built.
Step 'Publish'
$body = @"
The current apps, built from master at this tag. Debug-signed sideload builds: ``UnisonReader-$Version.apk`` for a phone (Android 15 and later, ARM64 and x86_64) and ``UnisonReader-wear-$Version.apk`` for a watch (Wear OS 6 and later).

Install over any earlier version with ``adb install -r UnisonReader-$Version.apk`` or by opening the file on the phone; the library, positions and sign-in are kept.

$($Notes.Trim())

SHA-256 of UnisonReader-$Version.apk: $hash
SHA-256 of UnisonReader-wear-$Version.apk: $wearHash
"@
$notesPath = Join-Path $env:TEMP "unison-release-$Version.md"
Set-Content $notesPath $body
& gh release create $tag $apk $wearApk --title "UnisonReader $Version" --notes-file $notesPath --verify-tag --latest
if ($LASTEXITCODE -ne 0) { throw "Publishing failed. $tag is pushed; publish by hand with: gh release create $tag $apk $wearApk --title `"UnisonReader $Version`" --notes-file $notesPath" }

$check = Join-Path $env:TEMP "unison-release-check-$Version"
if (Test-Path $check) { Remove-Item -Recurse -Force $check }
& gh release download $tag --pattern "UnisonReader-$Version.apk" --pattern "UnisonReader-wear-$Version.apk" --dir $check
if ($LASTEXITCODE -ne 0) { throw 'Published, but the download check failed; compare the hash on the release page by hand.' }
$published = (Get-FileHash (Join-Path $check "UnisonReader-$Version.apk") -Algorithm SHA256).Hash
if ($published -ne $hash) { throw "The published APK's hash $published differs from the built $hash." }
$publishedWear = (Get-FileHash (Join-Path $check "UnisonReader-wear-$Version.apk") -Algorithm SHA256).Hash
if ($publishedWear -ne $wearHash) { throw "The published watch APK's hash $publishedWear differs from the built $wearHash." }
Remove-Item -Recurse -Force $check

Step "Released UnisonReader $Version"
Write-Host "Tag $tag. Phone: $('{0:N0}' -f $size) bytes, SHA-256 $hash. Watch: $('{0:N0}' -f $wearSize) bytes, SHA-256 $wearHash. The downloaded copies match."
Write-Host "For the verification log: Release $Version. Version $Version (code $($code + 1)) built from master at tag $tag and published as a GitHub release with UnisonReader-$Version.apk ($('{0:N0}' -f $size) bytes, SHA-256 $hash) and UnisonReader-wear-$Version.apk ($('{0:N0}' -f $wearSize) bytes, SHA-256 $wearHash)."
