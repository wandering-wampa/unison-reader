param([switch]$ImportFixtures, [switch]$FullSync, [string[]]$Modes=@('core','reader','playback'), [string]$ServerUrl='', [string]$ServerToken='', [string]$Serial='')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1"
# Which device: -Serial, or the one emulator that is running. With a watch emulator up as well there are two, and
# the phone's has to be named (adb devices lists them).
if (-not $Serial) {
    $running = @(& adb devices | Where-Object { $_ -match '^(emulator-\d+)\s+device' } | ForEach-Object { $Matches[1] })
    if ($running.Count -ne 1) { throw "$($running.Count) emulators are running ($($running -join ', ')): say which with -Serial." }
    $Serial = $running[0]
}
New-Item -ItemType Directory -Force test-results | Out-Null
& .\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed.' }
& adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
& adb -s $Serial shell pm grant dev.homescreenreader android.permission.POST_NOTIFICATIONS
& adb -s $Serial shell input keyevent KEYCODE_WAKEUP
& adb -s $Serial shell wm dismiss-keyguard
& adb -s $Serial shell run-as dev.homescreenreader mkdir -p files
function Push-TestFile([string]$Local,[string]$Name,[string]$Target=$Name) {
    & adb -s $Serial push $Local "/data/local/tmp/$Name"
    if ($LASTEXITCODE -ne 0) { throw "Missing or unreadable fixture: $Local" }
    & adb -s $Serial shell run-as dev.homescreenreader cp "/data/local/tmp/$Name" "files/$Target"
    if ($LASTEXITCODE -ne 0) { throw "Fixture staging failed: $Name" }
    & adb -s $Serial shell rm "/data/local/tmp/$Name"
}
if ($ImportFixtures) {
    Push-TestFile 'test-fixtures/books/11_22_63 - Stephen King.epub' 'fixture.epub'
    Push-TestFile 'test-fixtures/audiobooks/11-22-63.zip' 'fixture.zip'
}
Push-TestFile 'vendor/libmobi/tests/samples/sample-cp1252.mobi' 'sample.mobi'
Push-TestFile 'vendor/libmobi/tests/samples/sample-obfuscated-fonts.mobi' 'sample.azw3'
if ($Modes -contains 'formats') {
    & adb -s $Serial shell run-as dev.homescreenreader mkdir -p files/tones
    Push-TestFile 'test-results/audio/tones.zip' 'reader-tones.zip' 'tones/tones.zip'
}
if ($Modes -contains 'shelves') {
    & python Generate-SafFixture.py shelves
    if ($LASTEXITCODE -ne 0) { throw 'Shelf fixture creation failed.' }
    foreach ($name in @('shelf-ebook.epub','shelf-solo.zip','shelf-pair.zip','shelf-pair.epub')) { Push-TestFile "test-results/$name" $name }
}
if ($Modes -contains 'saf') {
    & python Generate-SafFixture.py
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic EPUB creation failed.' }
    & adb -s $Serial push test-results/Reader-SAF.epub /sdcard/Download/Reader-SAF.epub
    if ($LASTEXITCODE -ne 0) { throw 'Downloads fixture staging failed.' }
}
if ($FullSync) { & adb -s $Serial shell run-as dev.homescreenreader touch files/sync-full } else { & adb -s $Serial shell run-as dev.homescreenreader rm -f files/sync-full }
foreach ($mode in $Modes) {
    $extra=@()
    if ($ServerUrl) { $extra += @('-e','serverUrl',$ServerUrl,'-e','serverToken',$ServerToken) }
    $result=& adb -s $Serial shell am instrument -w -e mode $mode @extra dev.homescreenreader.test/dev.homescreenreader.ReaderInstrumentation
    $result | Tee-Object -FilePath "test-results/$mode.txt"
    if (($result -join "`n") -notmatch "PASS: $mode" -or ($result -join "`n") -match 'FAIL:') { throw "Emulator test failed: $mode" }
}
