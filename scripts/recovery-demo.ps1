$ErrorActionPreference = 'Stop'
$project = 'exchange-recovery-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$composeFile = Join-Path $PSScriptRoot '..\compose.recovery-demo.yaml'
$base = 'http://localhost:18080/api'
function Compose { docker compose -p $project -f $composeFile @args; if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed' } }
function Wait-Api {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try { Invoke-RestMethod "$base/markets" | Out-Null; return } catch { Start-Sleep -Seconds 1 }
    }
    throw 'Backend did not become ready'
}
function Order($id, $account, $side, $price, $quantity) {
    $body = @{clientId=$id; accountId=$account; symbol='BTC-PERP'; side=$side; priceCents=$price; quantity=$quantity} | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType 'application/json' -Body $body | Out-Null
}
function Check($ok, $message) { if (-not $ok) { throw $message } }
try {
    Compose up -d --build
    Wait-Api
    # Only this demo's temporary database is modified; trades use the REST API.
    Compose exec -T db psql -U exchange -d exchange -c "INSERT INTO accounts VALUES ('buyer1',100000),('buyer2',100000)"
    Order 'initial-sell' 'maker' 'SELL' 5000 10
    Order 'initial-buy' 'alice' 'BUY' 5000 10
    Invoke-RestMethod -Method Put -Uri "$base/markets/BTC-PERP/mark" -ContentType 'application/json' -Body '{"priceCents":4500}' | Out-Null
    Order 'buyer-one' 'buyer1' 'BUY' 4500 5
    $liquidation = foreach ($item in (Invoke-RestMethod "$base/orders")) { if ($item.reduce_only) { $item } }
    $account = foreach ($item in (Invoke-RestMethod "$base/accounts")) { if ($item.id -eq 'alice') { $item } }
    $position = foreach ($item in (Invoke-RestMethod "$base/positions")) { if ($item.account_id -eq 'alice') { $item } }
    Check ($liquidation.remaining -eq 5 -and $position.quantity -eq 5 -and $account.cash_cents -eq 7500 -and $account.equity_cents -eq 5000) 'Partial liquidation did not match expected state'
    Write-Host 'Before restart: Alice has 5 contracts, $75 cash, $50 equity, and 5 liquidation contracts remain.'
    Compose kill backend
    Compose up -d backend
    Wait-Api
    $after = foreach ($item in (Invoke-RestMethod "$base/orders")) { if ($item.reduce_only) { $item } }
    $position = foreach ($item in (Invoke-RestMethod "$base/positions")) { if ($item.account_id -eq 'alice') { $item } }
    $fills = @(foreach ($item in (Invoke-RestMethod "$base/fills")) { if ($item.sell_order -eq $liquidation.sequence) { $item } })
    Write-Host "After restart: original order=$($liquidation.sequence); order=$($after.sequence); remaining=$($after.remaining); position=$($position.quantity); fills=$($fills.Count)"
    Check ($after.sequence -eq $liquidation.sequence -and $after.remaining -eq 5 -and $position.quantity -eq 5 -and $fills.Count -eq 1) 'State changed across restart'
    Order 'buyer-two' 'buyer2' 'BUY' 4500 5
    $account = foreach ($item in (Invoke-RestMethod "$base/accounts")) { if ($item.id -eq 'alice') { $item } }
    $position = foreach ($item in (Invoke-RestMethod "$base/positions")) { if ($item.account_id -eq 'alice') { $item } }
    $fills = @(foreach ($item in (Invoke-RestMethod "$base/fills")) { if ($item.sell_order -eq $liquidation.sequence) { $item } })
    Check ($null -eq $position -and $account.cash_cents -eq 5000 -and $account.equity_cents -eq 5000 -and $fills.Count -eq 2 -and @($fills | Where-Object { $_.quantity -ne 5 }).Count -eq 0) 'Final liquidation state incorrect'
    Write-Host 'PASS: after backend kill/restart, exactly two fills closed Alice; cash and equity are $50.'
} finally {
    # The project name is randomly generated above, so these resources belong to this run.
    docker compose -p $project -f $composeFile down -v --remove-orphans
}
