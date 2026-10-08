param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$UserId = "user-1001",
    [long]$SkuId = 10001,
    [int]$Rounds = 3,
    [int]$Requests = 100,
    [int]$InitialStock = 10,
    [string]$StoreId = "1",
    [switch]$ResetDatabase,
    [string]$DbHost = $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "localhost" }),
    [int]$DbPort = $(if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }),
    [string]$DbName = $(if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "xinghe_trade" }),
    [string]$DbUser = $(if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }),
    [string]$DbPassword = $(if ($null -ne $env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "" }),
    [string]$OutputDirectory = ".\stage-d-results"
)

$ErrorActionPreference = "Stop"
if (-not $ResetDatabase) { throw "Use -ResetDatabase with a dedicated Stage D test database so each round starts from a reproducible snapshot." }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectory = Join-Path $OutputDirectory $timestamp
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Invoke-JsonRequest([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body = $null) {
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $params = @{ Method = $Method; Uri = $Uri; Headers = $Headers; SkipHttpErrorCheck = $true }
        if ($null -ne $Body) { $params.ContentType = "application/json"; $params.Body = ($Body | ConvertTo-Json -Compress) }
        $response = Invoke-WebRequest @params
        $started.Stop()
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $started.Elapsed.TotalMilliseconds }
    } catch {
        $started.Stop()
        return [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $started.Elapsed.TotalMilliseconds }
    }
}

function Get-InventorySnapshot {
    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if ($null -eq $mysql) { return "mysql command not found; use the SQL below for the database snapshot." }
    $sql = "SELECT sku_id, available_quantity, locked_quantity, sold_quantity, version, (available_quantity + locked_quantity + sold_quantity) AS inventory_total FROM product_inventory WHERE sku_id = $SkuId;"
    return (& $mysql.Source --protocol=TCP --host=$DbHost --port=$DbPort --user=$DbUser --password=$DbPassword --database=$DbName --batch --skip-column-names -e $sql 2>&1 | Out-String).Trim()
}

function Reset-TestDatabase {
    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if ($null -eq $mysql) { throw "mysql client is required when -ResetDatabase is used" }
    $sql = "START TRANSACTION; DELETE FROM payment_idempotency; DELETE FROM trade_order; DELETE FROM cart_item; UPDATE product_inventory SET available_quantity = $InitialStock, locked_quantity = 0, sold_quantity = 0, version = version + 1 WHERE sku_id = $SkuId; COMMIT;"
    $result = & $mysql.Source --protocol=TCP --host=$DbHost --port=$DbPort --user=$DbUser --password=$DbPassword --database=$DbName --batch --skip-column-names -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "database reset failed: $result" }
}

$allRounds = [System.Collections.Generic.List[object]]::new()
for ($round = 1; $round -le $Rounds; $round++) {
    if ($ResetDatabase) {
        Reset-TestDatabase
        $setup = [pscustomobject]@{ StatusCode = 200 }
    } else {
        $setup = Invoke-JsonRequest "PUT" "$BaseUrl/api/store/products/$SkuId/inventory" @{ "X-Store-Id" = $StoreId } @{ availableQuantity = $InitialStock }
    }
    $jobs = 1..$Requests | ForEach-Object {
        $requestNumber = $_
        Start-Job -ScriptBlock {
            param($url, $user, $sku, $number)
            $requestUser = "$user-$number"
            $headers = @{ "X-User-Id" = $requestUser }
            $cartBody = @{ skuId = $sku; quantity = 1 }
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $cartResponse = Invoke-WebRequest -Method Post -Uri "$url/api/cart/items" -Headers $headers -ContentType "application/json" -Body ($cartBody | ConvertTo-Json -Compress) -SkipHttpErrorCheck
                if ([int]$cartResponse.StatusCode -lt 200 -or [int]$cartResponse.StatusCode -ge 300) {
                    throw "cart add failed with HTTP $([int]$cartResponse.StatusCode): $($cartResponse.Content)"
                }
                $headers["Idempotency-Token"] = "stage-d-checkout-$number-$([guid]::NewGuid())"
                $response = Invoke-WebRequest -Method Post -Uri "$url/api/orders/checkout" -Headers $headers -SkipHttpErrorCheck
                $watch.Stop()
                [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            } catch {
                $watch.Stop()
                [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            }
        } -ArgumentList $BaseUrl, $UserId, $SkuId, $requestNumber
    }
    $responses = $jobs | Wait-Job | Receive-Job
    $jobs | Remove-Job
    $times = @($responses | ForEach-Object { [double]$_.ElapsedMs } | Sort-Object)
    $successes = @($responses | Where-Object { $_.StatusCode -eq 200 }).Count
    $snapshot = Get-InventorySnapshot
    $record = [pscustomobject]@{
        Round = $round; Requests = $Requests; Successful = $successes; Failed = $Requests - $successes
        Oversold = [Math]::Max(0, $successes - $InitialStock); AverageMs = [Math]::Round((($times | Measure-Object -Average).Average), 2)
        P50Ms = $times[[Math]::Min($times.Count - 1, [Math]::Floor($times.Count * 0.50))]
        P95Ms = $times[[Math]::Min($times.Count - 1, [Math]::Floor($times.Count * 0.95))]
        MaxMs = $times[-1]; InventorySnapshot = $snapshot; SetupStatus = $setup.StatusCode; CompletedAt = (Get-Date).ToString("o")
    }
    $allRounds.Add($record)
    $record | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "round-$round.json")
    $responses | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "round-$round-raw.json")
}

$metadata = [pscustomobject]@{ BaseUrl = $BaseUrl; SkuId = $SkuId; Requests = $Requests; InitialStock = $InitialStock; Rounds = $Rounds; ResetDatabase = [bool]$ResetDatabase; DbHost = $DbHost; DbPort = $DbPort; DbName = $DbName; DbUser = $DbUser; Timestamp = (Get-Date).ToString("o"); Commit = (& git rev-parse HEAD 2>$null) }
$metadata | ConvertTo-Json | Set-Content (Join-Path $runDirectory "metadata.json")
$allRounds | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "summary.json")
Write-Host "Stage D results written to $runDirectory"