#Requires -Version 7.0
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$UserId = "user-1001",
    [long]$SkuId = 990001,
    [int]$Rounds = 3,
    [int]$Requests = 100,
    [int]$InitialStock = 10,
    [string]$StoreUsername = $(if ($env:XINGHE_STAGE_D_EMPLOYEE_USERNAME) { $env:XINGHE_STAGE_D_EMPLOYEE_USERNAME } else { $env:XINGHE_STORE_USERNAME }),
    [string]$StorePassword = $(if ($env:XINGHE_STAGE_D_EMPLOYEE_PASSWORD) { $env:XINGHE_STAGE_D_EMPLOYEE_PASSWORD } else { $env:XINGHE_STORE_PASSWORD }),
    [string]$CustomerPassword = $(if ($env:XINGHE_TEST_PASSWORD) { $env:XINGHE_TEST_PASSWORD } else { "" }),
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
if ([string]::IsNullOrWhiteSpace($CustomerPassword) -or $CustomerPassword.Length -lt 8) { throw "Set -CustomerPassword or XINGHE_TEST_PASSWORD (at least 8 characters)." }
if ([string]::IsNullOrWhiteSpace($StoreUsername) -or [string]::IsNullOrWhiteSpace($StorePassword)) { throw "Set -StoreUsername/-StorePassword or XINGHE_STAGE_D_EMPLOYEE_USERNAME/XINGHE_STAGE_D_EMPLOYEE_PASSWORD for inventory setup." }
if ($DbName -notmatch '^xinghe_stage_d(?:_d\d+)?$') { throw "Refusing database '$DbName'; Stage D resets require a dedicated xinghe_stage_d* database." }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = [guid]::NewGuid().ToString('N').Substring(0, 8)
$runDirectory = Join-Path $OutputDirectory $timestamp
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Invoke-JsonRequest([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body = $null, [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession = $null) {
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $params = @{ Method = $Method; Uri = $Uri; Headers = $Headers; SkipHttpErrorCheck = $true }
        if ($null -ne $WebSession) { $params.WebSession = $WebSession }
        if ($null -ne $Body) { $params.ContentType = "application/json"; $params.Body = ($Body | ConvertTo-Json -Compress) }
        $response = Invoke-WebRequest @params
        $started.Stop()
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $started.Elapsed.TotalMilliseconds }
    } catch {
        $started.Stop()
        return [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $started.Elapsed.TotalMilliseconds }
    }
}

function New-AuthenticatedSession([string]$Username, [string]$Password) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrfResponse = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/auth/csrf"
    $csrfToken = $session.Cookies.GetCookies($BaseUrl)['XSRF-TOKEN'].Value
    if ([string]::IsNullOrWhiteSpace($csrfToken)) { $csrfToken = [string]$csrfResponse.data }
    if ([string]::IsNullOrWhiteSpace($csrfToken)) { throw "Could not acquire CSRF token for $Username" }
    $headers = @{ 'X-XSRF-TOKEN' = $csrfToken }
    $payload = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    $login = Invoke-WebRequest -Method Post -WebSession $session -Uri "$BaseUrl/api/auth/login" -Headers $headers -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$login.StatusCode -ne 200) { throw "Login failed for $Username with HTTP $([int]$login.StatusCode): $($login.Content)" }
    return [pscustomobject]@{ WebSession = $session; CsrfToken = $csrfToken }
}

function Get-InventorySnapshot {
    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if ($null -eq $mysql) { return "mysql command not found; use the SQL below for the database snapshot." }
    $sql = "SELECT sku_id, available_quantity, locked_quantity, sold_quantity, version, (available_quantity + locked_quantity + sold_quantity) AS inventory_total FROM product_inventory WHERE sku_id = $SkuId;"
    $env:MYSQL_PWD = $DbPassword
    return (& $mysql.Source --protocol=TCP --host=$DbHost --port=$DbPort --user=$DbUser --database=$DbName --batch --skip-column-names -e $sql 2>&1 | Out-String).Trim()
}

function Reset-TestDatabase {
    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if ($null -eq $mysql) { throw "mysql client is required when -ResetDatabase is used" }
    $sql = "DELETE FROM payment_idempotency; DELETE FROM trade_order; DELETE FROM cart_item; UPDATE product_inventory SET available_quantity = $InitialStock, locked_quantity = 0, sold_quantity = 0, version = version + 1 WHERE sku_id = $SkuId;"
    $env:MYSQL_PWD = $DbPassword
    $result = & $mysql.Source --protocol=TCP --host=$DbHost --port=$DbPort --user=$DbUser --database=$DbName --batch --skip-column-names -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "database reset failed: $result" }
}

$allRounds = [System.Collections.Generic.List[object]]::new()
for ($round = 1; $round -le $Rounds; $round++) {
    if ($ResetDatabase) {
        Reset-TestDatabase
        $storeAuth = New-AuthenticatedSession $StoreUsername $StorePassword
        $setup = Invoke-JsonRequest "PUT" "$BaseUrl/api/store/products/$SkuId/inventory" @{ 'X-XSRF-TOKEN' = $storeAuth.CsrfToken } @{ availableQuantity = $InitialStock } $storeAuth.WebSession
    }
    if ($setup.StatusCode -ne 200) { throw "Inventory setup failed with HTTP $($setup.StatusCode): $($setup.Body)" }
    $jobs = 1..$Requests | ForEach-Object {
        $requestNumber = $_
        Start-Job -ScriptBlock {
            param($url, $user, $sku, $number, $password, $runId, $roundNumber)
            $requestUser = "$user-$runId-$roundNumber-$number"
            $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
            $csrfResponse = Invoke-RestMethod -WebSession $session -Uri "$url/api/auth/csrf"
            $csrfToken = $session.Cookies.GetCookies($url)['XSRF-TOKEN'].Value
            if ([string]::IsNullOrWhiteSpace($csrfToken)) { $csrfToken = [string]$csrfResponse.data }
            $headers = @{ 'X-XSRF-TOKEN' = $csrfToken }
            $cartBody = @{ skuId = $sku; quantity = 1 }
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $registerBody = @{ username = $requestUser; password = $password } | ConvertTo-Json -Compress
                $register = Invoke-WebRequest -Method Post -Uri "$url/api/auth/register" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $registerBody -SkipHttpErrorCheck
                if ([int]$register.StatusCode -ne 200) { throw "registration failed with HTTP $([int]$register.StatusCode): $($register.Content)" }
                $loginBody = @{ username = $requestUser; password = $password } | ConvertTo-Json -Compress
                $login = Invoke-WebRequest -Method Post -Uri "$url/api/auth/login" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
                if ([int]$login.StatusCode -ne 200) { throw "login failed with HTTP $([int]$login.StatusCode): $($login.Content)" }
                $cartResponse = Invoke-WebRequest -Method Post -Uri "$url/api/cart/items" -WebSession $session -Headers $headers -ContentType "application/json" -Body ($cartBody | ConvertTo-Json -Compress) -SkipHttpErrorCheck
                if ([int]$cartResponse.StatusCode -lt 200 -or [int]$cartResponse.StatusCode -ge 300) {
                    throw "cart add failed with HTTP $([int]$cartResponse.StatusCode): $($cartResponse.Content)"
                }
                $headers["Idempotency-Token"] = "stage-d-checkout-$number-$([guid]::NewGuid())"
                $response = Invoke-WebRequest -Method Post -Uri "$url/api/orders/checkout" -WebSession $session -Headers $headers -SkipHttpErrorCheck
                $watch.Stop()
                [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            } catch {
                $watch.Stop()
                [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            }
        } -ArgumentList $BaseUrl, $UserId, $SkuId, $requestNumber, $CustomerPassword, $runId, $round
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

$metadata = [pscustomobject]@{ BaseUrl = $BaseUrl; SkuId = $SkuId; Requests = $Requests; InitialStock = $InitialStock; Rounds = $Rounds; ResetDatabase = [bool]$ResetDatabase; DbHost = $DbHost; DbPort = $DbPort; DbName = $DbName; DbUser = $DbUser; Timestamp = (Get-Date).ToString("o"); Commit = (& git -C $PSScriptRoot\.. rev-parse HEAD 2>$null) }
$metadata | ConvertTo-Json | Set-Content (Join-Path $runDirectory "metadata.json")
$allRounds | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "summary.json")
Write-Host "Stage D results written to $runDirectory"
