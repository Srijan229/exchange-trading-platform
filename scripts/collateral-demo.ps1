$ErrorActionPreference = 'Stop'
$project = 'exchange-collateral-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$composeFile = Join-Path $PSScriptRoot '..\compose.recovery-demo.yaml'
$base = 'http://localhost:18080/api'
$jobs = @()
function Compose { docker compose -p $project -f $composeFile @args; if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed' } }
function Check($ok, $message) { if (-not $ok) { throw $message } }
try {
    Compose up -d --build
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try { Invoke-RestMethod "$base/markets" | Out-Null; $ready = $true; break } catch { Start-Sleep -Seconds 1 }
    }
    Check $ready 'Backend did not become ready'
    foreach ($symbol in @('BTC-PERP', 'ETH-PERP')) {
        $jobs += Start-Job -ArgumentList $base, $symbol -ScriptBlock {
            param($base, $symbol)
            $id = 'race-' + $symbol
            $body = @{clientId=$id;accountId='alice';symbol=$symbol;side='BUY';priceCents=3500;quantity=10} | ConvertTo-Json
            try {
                Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType 'application/json' -Body $body | Out-Null
                [pscustomobject]@{id=$id;symbol=$symbol;status=200}
            } catch {
                $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
                [pscustomobject]@{id=$id;symbol=$symbol;status=$code}
            }
        }
    }
    $done = $jobs | Wait-Job -Timeout 60
    Check ($done.Count -eq 2) 'Timed out waiting for requests'
    $results = @($jobs | Receive-Job)
    $accepted = @($results | Where-Object { $_.status -eq 200 })
    $rejected = @($results | Where-Object { $_.status -eq 409 })
    Check ($accepted.Count -eq 1 -and $rejected.Count -eq 1) 'Expected exactly one accepted and one rejected order'
    $alice = foreach ($item in (Invoke-RestMethod "$base/accounts")) { if ($item.id -eq 'alice') { $item } }
    Check ($alice.reserved_cents -eq 7000 -and ($alice.cash_cents - $alice.reserved_cents) -eq 3000) 'Collateral totals incorrect'
    Write-Host "Accepted $($accepted[0].symbol); rejected $($rejected[0].symbol). Reserved: `$70; available: `$30."
    Invoke-RestMethod -Method Delete -Uri "$base/orders/$($accepted[0].id)" | Out-Null
    $alice = foreach ($item in (Invoke-RestMethod "$base/accounts")) { if ($item.id -eq 'alice') { $item } }
    Check ($alice.reserved_cents -eq 0) 'Cancellation failed to release collateral'
    $body = @{clientId='fresh-order';accountId='alice';symbol=$rejected[0].symbol;side='BUY';priceCents=3500;quantity=10} | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType 'application/json' -Body $body | Out-Null
    Write-Host "PASS: cancellation released `$70; a fresh $($rejected[0].symbol) order succeeded."
} finally {
    $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
    docker compose -p $project -f $composeFile down -v --remove-orphans
}
