$ErrorActionPreference = 'Stop'
# Puts the Android build tools on PATH for this terminal. Dot-source it: . .\Enter-Android.ps1
# The tools folder holds a JDK 21 (jdk-21*), the SDK (sdk), and the Gradle cache, AVDs and adb keys beside them.
# It is found from UNISON_ANDROID_TOOLS, or else as the folder above `sdk.dir` in local.properties.
$toolRoot = $env:UNISON_ANDROID_TOOLS
if (-not $toolRoot) {
    # A git worktree has no local.properties of its own: fall back to the main checkout's.
    $candidates = @($PSScriptRoot)
    $common = & git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -eq 0 -and $common) { $candidates += (Split-Path -Parent $common) }
    foreach ($folder in $candidates) {
        $properties = Join-Path $folder 'local.properties'
        if (-not (Test-Path -LiteralPath $properties)) { continue }
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$' } | Select-Object -First 1
        if ($line -and $line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') { $toolRoot = Split-Path -Parent ($Matches[1] -replace '\\:', ':' -replace '\\\\', '\' -replace '/', '\'); break }
    }
}
if (-not $toolRoot -or -not (Test-Path -LiteralPath $toolRoot)) { throw 'Android tools not found: set UNISON_ANDROID_TOOLS, or sdk.dir in local.properties (see docs/BUILD.md).' }
$jdk = Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'jdk-21*' | Sort-Object Name | Select-Object -Last 1
if (-not $jdk) { throw "No JDK 21 (a folder named jdk-21*) in $toolRoot." }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $toolRoot 'avd'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\emulator;$env:Path"
Write-Host 'Android development tools enabled for this terminal.'
