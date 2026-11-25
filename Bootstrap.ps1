param([switch]$InstallNativeTools)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\Enter-Android.ps1"
New-Item -ItemType Directory -Force vendor,app/src/main/assets/models | Out-Null
$dependencies = @(
    @{ Name='libmobi'; Url='https://github.com/bfabiszewski/libmobi.git'; Ref='v0.12'; Commit='85dcfe803fc2a21020ddcf15c3eb66b93d388add' },
    @{ Name='foliate-js'; Url='https://github.com/johnfactotum/foliate-js.git'; Ref='78914aef4466eb960965702401634c2cb348e9b1'; Commit='78914aef4466eb960965702401634c2cb348e9b1' }
)
foreach ($dependency in $dependencies) {
    $destination = Join-Path "$PSScriptRoot\vendor" $dependency.Name
    if (!(Test-Path -LiteralPath $destination)) {
        & git init $destination
        & git -C $destination remote add origin $dependency.Url
        & git -C $destination fetch --depth 1 origin $dependency.Ref
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($dependency.Name)" }
        & git -C $destination checkout --detach FETCH_HEAD
    }
    $actual = & git -C $destination rev-parse HEAD
    if ($actual -ne $dependency.Commit) { throw "Unexpected source revision in $destination. Preserve local changes and check out $($dependency.Commit)." }
}
# Speech model: NVIDIA QuartzNet 15x5 English (CC BY 4.0), int8 ONNX export with the mel
# frontend inside the graph, from the Honomiya models-v1 release. ONNX Runtime itself comes
# from Maven Central through Gradle.
$model = Join-Path $PSScriptRoot 'app/src/main/assets/models/quartznet15x5-en.int8.onnx'
$modelHash = '98C3918F0C5EE620E9FC894CAFC50CBB925BDDDB1F153C959C1568D2319228BA'
if (!(Test-Path -LiteralPath $model)) {
    Invoke-WebRequest 'https://github.com/Natsume-197/Honomiya/releases/download/models-v1/quartznet15x5-en.model.int8.onnx' -OutFile "$model.pending"
    if ((Get-FileHash "$model.pending" -Algorithm SHA256).Hash -ne $modelHash) { throw 'Speech model checksum failed.' }
    Move-Item -LiteralPath "$model.pending" -Destination $model
}
if ((Get-FileHash $model -Algorithm SHA256).Hash -ne $modelHash) { throw 'Speech model checksum failed.' }
if ($InstallNativeTools) {
    & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'ndk;27.2.12479018' 'cmake;3.22.1'
    if ($LASTEXITCODE -ne 0) { throw 'Android native-tool installation failed.' }
}
Write-Host 'Pinned native library, reading engine, and offline speech model are ready.'
