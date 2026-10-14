#Requires -Version 7
[CmdletBinding()]
param(
    [long]$StoreId = 1,
    [string]$StoreName = '星河印象城店铺'
)

$ErrorActionPreference = 'Stop'
if ($StoreId -le 0) { throw 'StoreId must be a positive integer.' }
if ([string]::IsNullOrWhiteSpace($StoreName) -or $StoreName.Length -gt 120) {
    throw 'StoreName must contain 1 to 120 characters.'
}

$packageRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $packageRoot 'compose.yaml'
$envFile = Join-Path $PSScriptRoot 'docker.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Missing deploy/docker.env.' }

$escapedName = $StoreName.Replace("'", "''")
$sql = @"
INSERT INTO store (id, name, status, created_at, updated_at)
SELECT $StoreId, '$escapedName', 'OPEN', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM store WHERE id = $StoreId);
SELECT id, name, status, created_at, updated_at FROM store WHERE id = $StoreId;
"@

docker compose --env-file $envFile -f $composeFile exec -T -e "STORE_BOOTSTRAP_SQL=$sql" mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --database="$MYSQL_DATABASE" --table --execute="$STORE_BOOTSTRAP_SQL"'
if ($LASTEXITCODE -ne 0) { throw 'Store initialization failed.' }

Write-Host 'Store is ready. Follow DEPLOYMENT.md to perform the one-time employee account seed.'
