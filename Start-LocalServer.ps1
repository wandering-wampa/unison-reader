# Runs UnisonServer on this machine against a throwaway library, for trying server and web changes
# before a deploy: http://localhost:8099, accounts `admin` and `reader` with generated passwords in
# build\local-server\credentials.txt. Seven ebooks and two short audiobooks of generated tones; nothing syncs (no speech model).
# -Fresh starts over with an empty database.
#
# -Demo runs it against a demo library of public-domain books instead, under build\demo-server: thirteen
# ebooks from Standard Ebooks and two LibriVox recordings, fetched on the first run (about 250 MB) and kept in
# build\demo-downloads. The two pairs are synced for real, so it needs ffmpeg and ffprobe on PATH and the
# speech model from Bootstrap.ps1. The README's pictures are taken from it. Its accounts are in
# build\demo-server\credentials.txt.
param([int]$Port = 8099, [switch]$Fresh, [switch]$NoBuild, [switch]$Demo)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Enter-Android.ps1" | Out-Null
$root = Join-Path $PSScriptRoot $(if ($Demo) { 'build\demo-server' } else { 'build\local-server' })
if ($Fresh -and (Test-Path $root)) { Remove-Item -Recurse -Force $root }
if ($Demo) {
    if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue) -or -not (Get-Command ffprobe -ErrorAction SilentlyContinue)) { throw 'The demo library syncs audiobooks: put ffmpeg and ffprobe on PATH first.' }
    # A git worktree has no model of its own: use the main checkout's.
    $model = Join-Path $PSScriptRoot 'app\src\main\assets\models\quartznet15x5-en.int8.onnx'
    if (-not (Test-Path $model)) { $model = Join-Path (Split-Path -Parent (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir)) 'app\src\main\assets\models\quartznet15x5-en.int8.onnx' }
    if (-not (Test-Path $model)) { throw 'The speech model is missing: run .\Bootstrap.ps1 first.' }
    python (Join-Path $PSScriptRoot 'server\local\make_demo.py') $root (Join-Path $PSScriptRoot 'build\demo-downloads')
    if ($LASTEXITCODE) { throw 'The demo library could not be built.' }
    $env:UNISON_MODEL = $model
} else {
    $epub = Get-ChildItem (Join-Path $PSScriptRoot 'test-fixtures\books') -Filter *.epub | Select-Object -First 1
    if (-not $epub) { throw 'Put an EPUB in test-fixtures\books first.' }
    python (Join-Path $PSScriptRoot 'server\local\make_fixture.py') $root $epub.FullName
    $env:UNISON_MODEL = Join-Path $root 'model.onnx'
}
if (-not $NoBuild) { & "$PSScriptRoot\gradlew.bat" :server:installDist --console=plain -q; if ($LASTEXITCODE) { throw 'Server build failed.' } }
# The scanner reads audio lengths through ffprobe. A machine without one gets a stand-in that understands the fixture's
# WAV files (server\local\ffprobe_stub.cs, compiled once with the C# compiler Windows carries); without either, the
# two fixture audiobooks are simply not listed.
if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) {
    $bin = Join-Path $root 'bin'; $stub = Join-Path $bin 'ffprobe.exe'; $source = Join-Path $PSScriptRoot 'server\local\ffprobe_stub.cs'
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if ((Test-Path $csc) -and (-not (Test-Path $stub) -or (Get-Item $stub).LastWriteTime -lt (Get-Item $source).LastWriteTime)) {
        New-Item -ItemType Directory -Force $bin | Out-Null
        & $csc /nologo /optimize "/out:$stub" $source | Out-Null
    }
    if (Test-Path $stub) { $env:Path = "$bin;$env:Path" }
}
$env:UNISON_EBOOKS = Join-Path $root 'ebooks'
$env:UNISON_AUDIOBOOKS = Join-Path $root 'audiobooks'
$env:UNISON_DATA = Join-Path $root 'data'
# The wanted list posts to a stand-in webhook that server\local\check_phase9.py listens on while it runs (nothing leaves this machine).
$env:UNISON_WANTED_WEBHOOK = 'http://127.0.0.1:8098/hook'
$env:PORT = "$Port"
& (Join-Path $PSScriptRoot 'server\build\install\server\bin\server.bat')
