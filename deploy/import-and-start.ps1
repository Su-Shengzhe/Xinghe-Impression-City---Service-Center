#Requires -Version 7
[CmdletBinding()]
param(
    [switch]$SkipImageLoad
)

$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $packageRoot 'compose.yaml'
$envFile = Join-Path $PSScriptRoot 'docker.env'
$imageArchive = Join-Path $packageRoot 'images.tar'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Missing deploy/docker.env. Copy docker.env.example to docker.env and replace every CHANGE_ME value.'
}
if (Select-String -LiteralPath $envFile -Pattern '^\s*[A-Za-z_][A-Za-z0-9_]*\s*=\s*.*CHANGE_ME' -Quiet) {
    throw 'deploy/docker.env still contains CHANGE_ME placeholders.'
}

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is unavailable. Start Docker Desktop in Linux container mode and retry.'
}

if (-not $SkipImageLoad) {
    if (-not (Test-Path -LiteralPath $imageArchive)) {
        throw 'Missing images.tar. Copy the complete offline bundle, or use -SkipImageLoad when images are already installed.'
    }
    docker image load --input $imageArchive
    if ($LASTEXITCODE -ne 0) { throw 'Docker image import failed.' }
}

docker compose --env-file $envFile -f $composeFile up -d --no-build --pull never
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }

docker compose --env-file $envFile -f $composeFile ps
Write-Host 'Web UI: http://localhost:' -NoNewline
$webPortLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^WEB_PORT=' } | Select-Object -Last 1
Write-Host (($webPortLine -split '=', 2)[1])
Write-Host 'For a new database, continue with deploy/DEPLOYMENT.md section 4.'
