# Runs the Compose stack on this machine the way docs/SERVER.md describes doing it by hand: a folder with compose.yaml
# and a .env made from .env.example with only the two library folders and the image name filled in, and the image
# built here. For trying the deploy files in a real container before they go to a host. Everything it makes is in
# build\compose-test and the Compose project "unison-test"; it starts Docker Desktop when Docker is not running,
# and -Down stops it again if it did.
#
#   .\Test-Compose.ps1             the deploy path: builds the distribution here and the image from server/deploy/Dockerfile,
#                                  starts it, waits for the server, prints its health and setup state; leaves it running on port 8095
#   .\Test-Compose.ps1 -FromSource the clone path: builds the Dockerfile at the top of the repository from a clean export of HEAD
#                                  (the committed files, as a clone has them), then the same
#   .\Test-Compose.ps1 -Image ghcr.io/wandering-wampa/unison-reader:dev-68cea62
#                                  the published path: no build at all; pulls that image and runs it with compose.yaml and the .env, then the same
#   .\Test-Compose.ps1 -Down       remove the container, its image (a pulled one too) and the folder
#
# The libraries are the throwaway ones Start-LocalServer.ps1 makes (build\local-server) when they are there, else two empty folders.
param([switch]$Down, [switch]$NoBuild, [switch]$FromSource, [string]$Image, [string]$Ebooks, [string]$Audiobooks)
$ErrorActionPreference = 'Stop'
$stage = Join-Path $PSScriptRoot 'build\compose-test'
$marker = Join-Path $PSScriptRoot 'build\compose-test-started-docker'
function Test-Docker { & docker info *> $null; $LASTEXITCODE -eq 0 }

if ($Down) {
    if ((Test-Path $stage) -and (Test-Docker)) { Push-Location $stage; try { & docker compose -p unison-test down --rmi all --volumes } finally { Pop-Location } }
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    if (Test-Path $marker) { Remove-Item $marker; & docker desktop stop; Write-Host 'Docker Desktop stopped (this script had started it).' }
    Write-Host 'Removed.'; return
}

if (-not (Test-Docker)) {
    Write-Host 'Docker is not running: starting Docker Desktop.'
    New-Item -ItemType Directory -Force (Split-Path $marker) | Out-Null; Set-Content $marker ''
    & docker desktop start
    for ($i = 0; $i -lt 90 -and -not (Test-Docker); $i++) { Start-Sleep -Seconds 2 }
    if (-not (Test-Docker)) { throw 'Docker did not start.' }
}
if (Get-NetTCPConnection -State Listen -LocalPort 8095 -ErrorAction SilentlyContinue) { throw 'Port 8095 is in use on this machine.' }
if ($Image -and $FromSource) { throw '-Image and -FromSource do not go together.' }
if (-not $NoBuild -and -not $FromSource -and -not $Image) { . "$PSScriptRoot\Enter-Android.ps1" | Out-Null; & "$PSScriptRoot\gradlew.bat" :server:installDist --console=plain -q; if ($LASTEXITCODE) { throw 'Server build failed.' } }

if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
if ($Image) {
    # The published image needs only the compose file, as a stranger takes it from the repository.
    Copy-Item (Join-Path $PSScriptRoot 'server\deploy\compose.yaml') "$stage\compose.yaml"
} elseif ($FromSource) {
    # What a clone has: the committed files of HEAD with LF line endings, and nothing the repository ignores.
    $src = "$stage\src"; New-Item -ItemType Directory -Force $src | Out-Null
    & git -C $PSScriptRoot archive HEAD -o "$stage\src.tar"; if ($LASTEXITCODE) { throw 'git archive failed.' }
    & tar -xf "$stage\src.tar" -C $src; if ($LASTEXITCODE) { throw 'tar failed.' }
    Remove-Item "$stage\src.tar"
    Copy-Item "$src\server\deploy\compose.yaml" "$stage\compose.yaml"
    $buildDir = $src
} else {
    # A git worktree has no model of its own: use the main checkout's.
    $model = Join-Path $PSScriptRoot 'app\src\main\assets\models\quartznet15x5-en.int8.onnx'
    if (-not (Test-Path $model)) { $model = Join-Path (Split-Path -Parent (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir)) 'app\src\main\assets\models\quartznet15x5-en.int8.onnx' }
    if (-not (Test-Path $model)) { throw 'The speech model is missing: run .\Bootstrap.ps1 first.' }
    New-Item -ItemType Directory -Force "$stage\build" | Out-Null
    Copy-Item -Recurse (Join-Path $PSScriptRoot 'server\build\install\server') "$stage\build\install"
    Copy-Item $model "$stage\build\model.onnx"
    Copy-Item (Join-Path $PSScriptRoot 'server\deploy\Dockerfile') "$stage\build\Dockerfile"
    Copy-Item (Join-Path $PSScriptRoot 'server\deploy\compose.yaml') "$stage\compose.yaml"
    $buildDir = "$stage\build"
}
$fixture = Join-Path $PSScriptRoot 'build\local-server'
if (-not $Ebooks) { $Ebooks = Join-Path $fixture 'ebooks'; if (-not (Test-Path $Ebooks)) { $Ebooks = New-Item -ItemType Directory -Force "$stage\ebooks" } }
if (-not $Audiobooks) { $Audiobooks = Join-Path $fixture 'audiobooks'; if (-not (Test-Path $Audiobooks)) { $Audiobooks = New-Item -ItemType Directory -Force "$stage\audiobooks" } }
# .env is .env.example with the two folders and the image name filled in and nothing else touched: the rest must work as it stands.
$lines = Get-Content (Join-Path $PSScriptRoot 'server\deploy\.env.example') | ForEach-Object {
    if ($_ -match '^UNISON_AUDIOBOOKS=') { "UNISON_AUDIOBOOKS=$("$Audiobooks" -replace '\\', '/')" } elseif ($_ -match '^UNISON_EBOOKS=') { "UNISON_EBOOKS=$("$Ebooks" -replace '\\', '/')" } elseif ($_ -match '^#\s*UNISON_IMAGE=') { "UNISON_IMAGE=$(if ($Image) { $Image } else { 'unisonserver:local' })" } else { $_ }
}
Set-Content "$stage\.env" $lines

Push-Location $stage
try {
    if ($Image) { & docker pull $Image; if ($LASTEXITCODE) { throw 'docker pull failed.' } }
    else { & docker build -t unisonserver:local $buildDir; if ($LASTEXITCODE) { throw 'docker build failed.' } }
    & docker compose -p unison-test up -d; if ($LASTEXITCODE) { throw 'docker compose up failed.' }
} finally { Pop-Location }
$health = $null
for ($i = 0; $i -lt 60 -and -not $health; $i++) { Start-Sleep -Seconds 2; try { $health = Invoke-RestMethod http://localhost:8095/api/health -TimeoutSec 3 } catch {} }
if (-not $health) { & docker logs --tail 30 unisonserver; throw 'The server did not answer on port 8095.' }
Write-Host "Health: $($health | ConvertTo-Json -Compress)"
Start-Sleep -Seconds 5
Write-Host "Setup:  $(Invoke-RestMethod http://localhost:8095/api/setup | ConvertTo-Json -Compress -Depth 4)"
Write-Host "Data folder: $((Get-ChildItem "$stage\data" -Name) -join ', ')"
Write-Host 'Running at http://localhost:8095 . Remove it with .\Test-Compose.ps1 -Down'
