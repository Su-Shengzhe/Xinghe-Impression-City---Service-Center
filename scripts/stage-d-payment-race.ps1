param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$UserId = "user-1001",
    [string]$OrderNo,
    [int]$Requests = 20,
    [int]$Rounds = 20,
    [ValidateSet("same-token", "different-token")]
    [string]$TokenMode = "same-token",
    [string]$OutputDirectory = ".\stage-d-results"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($OrderNo)) { throw "Provide -OrderNo for a pending-payment parent or standalone order." }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectory = Join-Path $OutputDirectory "payment-$timestamp"
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Invoke-Payment([string]$token) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/orders/$OrderNo/pay" `
            -Headers @{ "X-User-Id" = $UserId; "Idempotency-Token" = $token } -SkipHttpErrorCheck
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
            param($baseUrl, $order, $user, $requestToken)
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $response = Invoke-WebRequest -Method Post -Uri "$baseUrl/api/orders/$order/pay" `
                    -Headers @{ "X-User-Id" = $user; "Idempotency-Token" = $requestToken } -SkipHttpErrorCheck
                $watch.Stop()
                [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $response.Content; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            } catch {
                $watch.Stop()
                [pscustomobject]@{ StatusCode = 0; Body = $_.Exception.Message; ElapsedMs = $watch.Elapsed.TotalMilliseconds }
            }
        } -ArgumentList $BaseUrl, $OrderNo, $UserId, $(if ($TokenMode -eq "same-token") { $token } else { "$token-$requestNumber" })
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
$metadata = [pscustomobject]@{ BaseUrl = $BaseUrl; OrderNo = $OrderNo; Requests = $Requests; Rounds = $Rounds; TokenMode = $TokenMode; Timestamp = (Get-Date).ToString("o"); Commit = (& git rev-parse HEAD 2>$null) }
$metadata | ConvertTo-Json | Set-Content (Join-Path $runDirectory "metadata.json")
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory "summary.json")
Write-Host "Payment race results written to $runDirectory"