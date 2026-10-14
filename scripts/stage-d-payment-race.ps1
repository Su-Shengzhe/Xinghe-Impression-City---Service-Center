#Requires -Version 7.0
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$UserId = "user-1001",
    [string]$OrderNo,
    [int]$Requests = 20,
    [int]$Rounds = 20,
    [ValidateSet("same-token", "different-token")]
    [string]$TokenMode = "same-token",
    [string]$LoginPassword = $(if ($env:XINGHE_TEST_PASSWORD) { $env:XINGHE_TEST_PASSWORD } else { "" }),
    [string]$OutputDirectory = ".\stage-d-results"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($OrderNo)) { throw "Provide -OrderNo for a pending-payment parent or standalone order." }
if ([string]::IsNullOrWhiteSpace($LoginPassword) -or $LoginPassword.Length -lt 8) { throw "Set -LoginPassword or XINGHE_TEST_PASSWORD (at least 8 characters)." }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectory = Join-Path $OutputDirectory "payment-$timestamp"
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Invoke-Payment([string]$token) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
        $csrf = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/auth/csrf"
        $csrfToken = $session.Cookies.GetCookies($BaseUrl)['XSRF-TOKEN'].Value
        if ([string]::IsNullOrWhiteSpace($csrfToken)) { $csrfToken = [string]$csrf.data }
        $headers = @{ 'X-XSRF-TOKEN' = $csrfToken }
        $loginBody = @{ username = $UserId; password = $LoginPassword } | ConvertTo-Json -Compress
        $login = Invoke-WebRequest -Method Post -WebSession $session -Uri "$BaseUrl/api/auth/login" -Headers $headers -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
        if ([int]$login.StatusCode -ne 200) { throw "login failed with HTTP $([int]$login.StatusCode): $($login.Content)" }
        $headers['Idempotency-Token'] = $token
        $response = Invoke-WebRequest -Method Post -WebSession $session -Uri "$BaseUrl/api/orders/$OrderNo/pay" `
            -Headers $headers -SkipHttpErrorCheck
        $watch.Stop()
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
    } catch {
        $watch.Stop()
        return [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
    }
}

$summary = [System.Collections.Generic.List[object]]::new()
for ($round = 1; $round -le $Rounds; $round++) {
    $token = "stage-d-pay-$round-$([guid]::NewGuid())"
    $jobs = 1..$Requests | ForEach-Object {
        $requestNumber = $_
        Start-Job -ScriptBlock {
            param($baseUrl, $order, $user, $requestToken, $password)
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
                $csrf = Invoke-RestMethod -WebSession $session -Uri "$baseUrl/api/auth/csrf"
                $csrfToken = $session.Cookies.GetCookies($baseUrl)['XSRF-TOKEN'].Value
                if ([string]::IsNullOrWhiteSpace($csrfToken)) { $csrfToken = [string]$csrf.data }
                $headers = @{ 'X-XSRF-TOKEN' = $csrfToken }
                $loginBody = @{ username = $user; password = $password } | ConvertTo-Json -Compress
                $login = Invoke-WebRequest -Method Post -WebSession $session -Uri "$baseUrl/api/auth/login" -Headers $headers -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
                if ([int]$login.StatusCode -ne 200) { throw "login failed with HTTP $([int]$login.StatusCode): $($login.Content)" }
                $headers['Idempotency-Token'] = $requestToken
                $response = Invoke-WebRequest -Method Post -WebSession $session -Uri "$baseUrl/api/orders/$order/pay" `
                    -Headers $headers -SkipHttpErrorCheck
                $watch.Stop()
                [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            } catch {
                $watch.Stop()
                [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            }
        } -ArgumentList $BaseUrl, $OrderNo, $UserId, $(if ($TokenMode -eq "same-token") { $token } else { "$token-$requestNumber" }), $LoginPassword
    }
    $responses = $jobs | Wait-Job | Receive-Job
    $jobs | Remove-Job
    $times = @($responses | ForEach-Object { [double]$_.ElapsedMs } | Sort-Object)
    $successes = @($responses | Where-Object { $_.StatusCode -eq 200 }).Count
    $record = [pscustomobject]@{
        Round = $round; Requests = $Requests; Successful = $successes; Failed = $Requests - $successes
        ActualPaymentTransitions = [Math]::Min(1, $successes); DuplicatePayments = [Math]::Max(0, $successes - 1)
        AverageMs = [Math]::Round((($times | Measure-Object -Average).Average), 2)
        P50Ms = $times[[Math]::Min($times.Count - 1, [Math]::Floor($times.Count * 0.50))]
        P95Ms = $times[[Math]::Min($times.Count - 1, [Math]::Floor($times.Count * 0.95))]
        MaxMs = $times[-1]; CompletedAt = (Get-Date).ToString("o")
    }
    $summary.Add($record)
    $responses | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "round-$round-raw.json")
    $record | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "round-$round.json")
}
$metadata = [pscustomobject]@{ BaseUrl = $BaseUrl; OrderNo = $OrderNo; Requests = $Requests; Rounds = $Rounds; TokenMode = $TokenMode; Timestamp = (Get-Date).ToString("o"); Commit = (& git -C $PSScriptRoot\.. rev-parse HEAD 2>$null) }
$metadata | ConvertTo-Json | Set-Content (Join-Path $runDirectory "metadata.json")
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "summary.json")
Write-Host "Payment race results written to $runDirectory"