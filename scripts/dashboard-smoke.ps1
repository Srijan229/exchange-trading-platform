$ErrorActionPreference = 'Stop'
$base = 'http://localhost:3000/demo-api'
function Check($ok, $message) { if (-not $ok) { throw $message } }
function PostOrder($id, $account, $symbol, $side, $price, $quantity) {
    $body = @{clientId=$id;accountId=$account;symbol=$symbol;side=$side;priceCents=$price;quantity=$quantity} | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$base/orders" -ContentType 'application/json' -Body $body
}
function Wait-Api {
    for ($i=0; $i -lt 90; $i++) { try { Invoke-RestMethod "$base/markets" | Out-Null; return } catch { Start-Sleep -Seconds 1 } }
    throw 'Demo API did not become ready'
}
Wait-Api
Invoke-RestMethod -Method Post -Uri "$base/demo/reset" | Out-Null
PostOrder 'demo-initial-sell' 'maker' 'BTC-PERP' 'SELL' 5000 10 | Out-Null
PostOrder 'demo-initial-buy' 'alice' 'BTC-PERP' 'BUY' 5000 10 | Out-Null
Invoke-RestMethod -Method Put -Uri "$base/markets/BTC-PERP/mark" -ContentType 'application/json' -Body '{"priceCents":4500}' | Out-Null
PostOrder 'demo-buyer-one' 'buyer1' 'BTC-PERP' 'BUY' 4500 5 | Out-Null
$position = foreach ($item in (Invoke-RestMethod "$base/positions")) { if ($item.account_id -eq 'alice') { $item } }
Check ($position.quantity -eq 5) 'Expected five contracts before restart'
docker compose kill demo-backend | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not stop demo backend' }
docker compose up -d demo-backend | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restart demo backend' }
Wait-Api
$position = foreach ($item in (Invoke-RestMethod "$base/positions")) { if ($item.account_id -eq 'alice') { $item } }
Check ($position.quantity -eq 5) 'Expected five contracts after restart'
PostOrder 'demo-buyer-two' 'buyer2' 'BTC-PERP' 'BUY' 4500 5 | Out-Null
$account = foreach ($item in (Invoke-RestMethod "$base/accounts")) { if ($item.id -eq 'alice') { $item } }
$liquidation = foreach ($item in (Invoke-RestMethod "$base/orders")) { if ($item.reduce_only) { $item } }
$fills = @(foreach ($item in (Invoke-RestMethod "$base/fills")) { if ($item.sell_order -eq $liquidation.sequence) { $item } })
Check ($account.cash_cents -eq 5000 -and $fills.Count -eq 2) 'Final state does not match expected liquidation'
Write-Host 'PASS: dashboard proxy, demo reset, liquidation, and demo-backend restart.'

Invoke-RestMethod -Method Post -Uri "$base/demo/reset" | Out-Null
$btc = PostOrder 'demo-race-btc' 'alice' 'BTC-PERP' 'BUY' 3500 10
try { PostOrder 'demo-race-eth' 'alice' 'ETH-PERP' 'BUY' 3500 10 | Out-Null; throw 'Expected insufficient collateral' }
catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw } }
Invoke-RestMethod -Method Delete -Uri "$base/orders/$($btc.client_id)" | Out-Null
$eth = PostOrder 'demo-fresh-other' 'alice' 'ETH-PERP' 'BUY' 3500 10
Check ($eth.status -eq 'OPEN') 'Expected fresh order to be open'
Write-Host 'PASS: dashboard proxy and shared-collateral scenario.'
