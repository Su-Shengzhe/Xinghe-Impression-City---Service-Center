#Requires -Version 7
[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$exampleEnv = Join-Path $PSScriptRoot 'docker.env.example'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot 'docker-release\xinghe-trade-center'
}

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is unavailable. Start Docker Desktop in Linux container mode and retry.'
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
$resolvedRepo = [System.IO.Path]::GetFullPath($repoRoot)
if (-not $resolvedOutput.StartsWith($resolvedRepo, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must stay inside this repository.'
}

New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $resolvedOutput 'deploy') | Out-Null

$composeArgs = @(
    '--env-file', $exampleEnv,
    '-f', (Join-Path $repoRoot 'compose.yaml'),
    '-f', (Join-Path $repoRoot 'compose.build.yaml')
)

docker compose @composeArgs build
if ($LASTEXITCODE -ne 0) { throw 'Application image build failed.' }

docker compose @composeArgs pull --ignore-buildable
if ($LASTEXITCODE -ne 0) { throw 'Base image download failed.' }

$images = @(docker compose @composeArgs config --images | Sort-Object -Unique)
if ($LASTEXITCODE -ne 0 -or $images.Count -eq 0) { throw 'Could not resolve Compose images.' }

$imageArchive = Join-Path $resolvedOutput 'images.tar'
docker image save --output $imageArchive @images
if ($LASTEXITCODE -ne 0) { throw 'Docker image export failed.' }

Copy-Item -LiteralPath (Join-Path $repoRoot 'compose.yaml') -Destination (Join-Path $resolvedOutput 'compose.yaml') -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'README.md') -Destination (Join-Path $resolvedOutput 'README.md') -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'requirements.txt') -Destination (Join-Path $resolvedOutput 'requirements.txt') -Force
Copy-Item -LiteralPath $exampleEnv -Destination (Join-Path $resolvedOutput 'deploy\docker.env.example') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'DEPLOYMENT.md') -Destination (Join-Path $resolvedOutput 'deploy\DEPLOYMENT.md') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'import-and-start.ps1') -Destination (Join-Path $resolvedOutput 'deploy\import-and-start.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'initialize-store.ps1') -Destination (Join-Path $resolvedOutput 'deploy\initialize-store.ps1') -Force

$commit = git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { $commit = 'unknown' }
$metadata = @(
    "commit=$commit"
    "created_at=$([DateTimeOffset]::Now.ToString('o'))"
    "host_arch=$([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)"
    'images='
) + ($images | ForEach-Object { "  $_" })
$metadata | Set-Content -LiteralPath (Join-Path $resolvedOutput 'VERSION.txt') -Encoding utf8

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $imageArchive
"$($hash.Hash.ToLowerInvariant())  images.tar" | Set-Content -LiteralPath (Join-Path $resolvedOutput 'SHA256SUMS.txt') -Encoding ascii

Write-Host "Offline deployment bundle created: $resolvedOutput"
Write-Host 'Send the entire directory to the target computer. Do not add deploy/docker.env with real passwords to the package.'
