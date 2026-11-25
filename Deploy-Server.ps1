# Builds UnisonServer here and deploys it to a Docker host over SSH as the Compose stack "unisonserver"
# in /opt/stacks/unisonserver. -Target is the SSH host (a name from your SSH config, or user@host).
# Usage: .\Deploy-Server.ps1 [-Target homelab]
param([string]$Target = 'homelab')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1" | Out-Null

& .\gradlew.bat :server:installDist --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Server build failed.' }

$stage = Join-Path $env:TEMP 'unisonserver-deploy'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force "$stage\build" | Out-Null
Copy-Item -Recurse 'server\build\install\server' "$stage\build\install"
Copy-Item 'app\src\main\assets\models\quartznet15x5-en.int8.onnx' "$stage\build\model.onnx"
Copy-Item 'server\deploy\Dockerfile' "$stage\build\Dockerfile"
Copy-Item 'server\deploy\compose.yaml' "$stage\compose.yaml"
# Where the libraries are on the host is your own detail: local.properties (not in the repository) carries
# unison.audiobooks and unison.ebooks, and they travel as the stack's .env file.
$local = @{}; Get-Content 'local.properties' -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*(unison\.[A-Za-z]+)\s*=\s*(.+?)\s*$' } | ForEach-Object { $null = $_ -match '^\s*(unison\.[A-Za-z]+)\s*=\s*(.+?)\s*$'; $local[$Matches[1]] = $Matches[2] }
foreach ($k in 'unison.audiobooks', 'unison.ebooks') { if (-not $local[$k]) { throw "local.properties needs $k (the folder on the server)." } }
# Optional: unison.wantedWebhook, the Discord channel webhook new wanted-list requests are posted to (a secret; off when absent),
# and unison.timezone, the IANA zone statistics fall back on (UTC when absent).
# The .env can hold a secret (the webhook): on the server it is made readable by root alone, below.
[IO.File]::WriteAllText("$stage\.env", "UNISON_AUDIOBOOKS=$($local['unison.audiobooks'])`nUNISON_EBOOKS=$($local['unison.ebooks'])`nUNISON_WANTED_WEBHOOK=$($local['unison.wantedWebhook'])`nTZ=$($local['unison.timezone'])`n")
tar -czf "$env:TEMP\unisonserver.tgz" -C $stage .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }

scp "$env:TEMP\unisonserver.tgz" "${Target}:/tmp/unisonserver.tgz"
if ($LASTEXITCODE -ne 0) { throw 'Upload failed.' }
ssh $Target 'sudo mkdir -p /opt/stacks/unisonserver/data && sudo rm -rf /opt/stacks/unisonserver/build && sudo tar -xzf /tmp/unisonserver.tgz -C /opt/stacks/unisonserver && rm /tmp/unisonserver.tgz && sudo chmod 600 /opt/stacks/unisonserver/.env && cd /opt/stacks/unisonserver && sudo docker compose up -d --build 2>&1 | tail -5'
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed.' }
Write-Host 'Deployed. Health:'
ssh $Target 'sleep 5; curl -s http://127.0.0.1:8095/api/health'
