#Requires -Version 7.0

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$baseUrl = 'http://localhost:8081'
$storeId = '1'
$otherStoreId = '2'
$skuId = 990010
$zeroStockSkuId = 990011
$userId = 'stage-f-p0-user-20261010'
$otherUserId = 'stage-f-other-user-20261010'
$resultRoot = $PSScriptRoot
$storeDir = Join-Path $resultRoot 'p0-store'
$customerDir = Join-Path $resultRoot 'p0-customer-service'
$p1Dir = Join-Path $resultRoot 'p1'

foreach ($dir in @($storeDir, $customerDir, $p1Dir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

function Save-Response {
    param(
        [string]$Name,
        [string]$Directory,
        [object]$Response,
        [string]$Expected = ''
    )
    $record = [ordered]@{
        name = $Name
        status = [int]$Response.StatusCode
        expected = $Expected
        contentType = [string]$Response.Headers['Content-Type']
        content = [string]$Response.Content
    }
    $record | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $Directory ($Name + '.json')) -Encoding utf8
    return $record
}

function Request {
    param(
        [string]$Name,
        [string]$Directory,
        [ValidateSet('Get', 'Post', 'Put', 'Delete')][string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [string]$Expected = ''
    )
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = $Headers[$key] }
    $params = @{
        Method = $Method
        Uri = $baseUrl + $Path
        Headers = $requestHeaders
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    $response = Invoke-WebRequest @params
    return Save-Response -Name $Name -Directory $Directory -Response $response -Expected $Expected
}

function Parse-Data([object]$Record) {
    return (($Record.content | ConvertFrom-Json).data)
}

$meta = [ordered]@{
    baseUrl = $baseUrl
    database = 'xinghe_stage_f'
    storeId = $storeId
    otherStoreId = $otherStoreId
    skuId = $skuId
    zeroStockSkuId = $zeroStockSkuId
    userId = $userId
    otherUserId = $otherUserId
    startedAt = (Get-Date).ToString('o')
}
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'live-run-meta.json') -Encoding utf8

# Prepare a second open store only in the dedicated stage F database. This supports a real cross-store authorization check.
$envFile = Join-Path $root '.env'
$envValues = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
        $envValues[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
$env:MYSQL_PWD = $envValues['MYSQL_PASSWORD']
$storeSql = "SELECT id,status FROM store WHERE id IN (1,2) ORDER BY id;"
$storeRows = & mysql -h $envValues['MYSQL_HOST'] -P ([int]$envValues['MYSQL_PORT']) -u $envValues['MYSQL_USERNAME'] --batch --skip-column-names --database=xinghe_stage_f -e $storeSql
if (-not ($storeRows | Where-Object { $_ -match '^2\s+OPEN$' })) {
    & mysql -h $envValues['MYSQL_HOST'] -P ([int]$envValues['MYSQL_PORT']) -u $envValues['MYSQL_USERNAME'] --database=xinghe_stage_f -e "INSERT INTO store (id,name,status,created_at,updated_at) VALUES (2,'Stage F Other Store','OPEN',NOW(),NOW());"
}

$commonUser = @{ 'X-User-Id' = $userId }
$otherUser = @{ 'X-User-Id' = $otherUserId }
$storeHeaders = @{ 'X-Store-Id' = $storeId }
$otherStoreHeaders = @{ 'X-Store-Id' = $otherStoreId }

# F2 product management: create, list, edit, inventory, status, and validation.
$productBody = @{ skuId = $skuId; name = 'Stage F Fulfillment Item'; description = 'Stage F fulfillment evidence'; price = 2.50; availableQuantity = 8 }
$created = Request '01-product-create' $storeDir Post '/api/store/products' $storeHeaders $productBody '200'
Request '02-product-list-owned' $storeDir Get '/api/store/products' $storeHeaders $null '200'
Request '03-product-update' $storeDir Put "/api/store/products/$skuId" $storeHeaders @{ name = 'Stage F Fulfillment Item Updated'; description = 'Updated for F2'; price = 3.00 } '200'
Request '04-inventory-update' $storeDir Put "/api/store/products/$skuId/inventory" $storeHeaders @{ availableQuantity = 8 } '200'
Request '05-inventory-negative' $storeDir Put "/api/store/products/$skuId/inventory" $storeHeaders @{ availableQuantity = -1 } '400'
Request '06-product-off-sale' $storeDir Put "/api/store/products/$skuId/status" $storeHeaders @{ status = 'OFF_SALE' } '200'
Request '07-product-detail-off-sale' $storeDir Get "/api/customer-service/products/$skuId" @{} $null '400'
Request '08-cart-off-sale' $storeDir Post '/api/cart/items' $commonUser @{ skuId = $skuId; quantity = 1 } '400'
Request '09-product-on-sale' $storeDir Put "/api/store/products/$skuId/status" $storeHeaders @{ status = 'ON_SALE' } '200'
Request '10-store-list-other-store' $storeDir Get '/api/store/products' $otherStoreHeaders $null '200 empty'
Request '11-product-update-other-store' $storeDir Put "/api/store/products/$skuId" $otherStoreHeaders @{ name = 'Unauthorized'; description = 'Unauthorized'; price = 1.00 } '400/403'

# Create a zero-stock SKU for the checkout inventory-boundary case.
$zeroStock = Request '12-zero-stock-create' $storeDir Post '/api/store/products' $storeHeaders @{ skuId = $zeroStockSkuId; name = 'Stage F Zero Stock'; description = 'Inventory boundary'; price = 1.00; availableQuantity = 0 } '200'

# F2/F1 transaction and fulfillment chain.
Request '13-cart-empty' $storeDir Get '/api/cart' $commonUser $null '200'
Request '14-cart-add' $storeDir Post '/api/cart/items' $commonUser @{ skuId = $skuId; quantity = 1 } '200'
Request '15-cart-update' $storeDir Put "/api/cart/items/$skuId" $commonUser @{ quantity = 1 } '200'
$checkout = Request '16-checkout' $storeDir Post '/api/orders/checkout' (@{ 'X-User-Id' = $userId; 'Idempotency-Token' = 'stage-f-checkout-20261010' }) $null '200'
$checkoutData = Parse-Data $checkout
$parentOrderNo = [string]$checkoutData[0].orderNo
$childOrderNo = [string]$checkoutData[1].orderNo
$meta.parentOrderNo = $parentOrderNo
$meta.childOrderNo = $childOrderNo
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'live-run-meta.json') -Encoding utf8
Request '17-store-orders-owned' $storeDir Get '/api/orders/store' $storeHeaders $null '200'
Request '18-store-orders-other-store' $storeDir Get '/api/orders/store' $otherStoreHeaders $null '200 empty'
Request '19-pay-parent' $storeDir Post "/api/orders/$parentOrderNo/pay" (@{ 'X-User-Id' = $userId; 'Idempotency-Token' = 'stage-f-pay-20261010' }) $null '200'
Request '20-pay-child-rejected' $storeDir Post "/api/orders/$childOrderNo/pay" (@{ 'X-User-Id' = $userId; 'Idempotency-Token' = 'stage-f-pay-child-20261010' }) $null '409'
Request '21-ship-wrong-store' $storeDir Post "/api/orders/$childOrderNo/ship" $otherStoreHeaders @{ company = 'StageCarrier'; trackingNo = 'SF-WRONG-20261010' } '403'
Request '22-ship-owned' $storeDir Post "/api/orders/$childOrderNo/ship" $storeHeaders @{ company = 'StageCarrier'; trackingNo = 'SF-OK-20261010' } '200'
Request '23-ship-repeat' $storeDir Post "/api/orders/$childOrderNo/ship" $storeHeaders @{ company = 'StageCarrier'; trackingNo = 'SF-REPEAT-20261010' } '409'
Request '24-order-detail-owned' $storeDir Get "/api/orders/$parentOrderNo" $commonUser $null '200'
Request '25-order-detail-other-user' $storeDir Get "/api/orders/$parentOrderNo" $otherUser $null '403'
Request '26-customer-order-owned' $customerDir Get "/api/customer-service/orders/$parentOrderNo" $commonUser $null '200'
Request '27-customer-order-other-user' $customerDir Get "/api/customer-service/orders/$parentOrderNo" $otherUser $null '403'
Request '28-customer-logistics-owned' $customerDir Get "/api/customer-service/orders/$childOrderNo/logistics" $commonUser $null '200 SHIPPED'
Request '29-customer-logistics-other-user' $customerDir Get "/api/customer-service/orders/$childOrderNo/logistics" $otherUser $null '403'

# F3 customer service session, SSE, history, session isolation, and product tools.
$session = Request '30-session-create' $customerDir Post '/api/customer-service/sessions' $commonUser $null '200'
$sessionId = [string](Parse-Data $session).sessionId
$meta.sessionId = $sessionId
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'live-run-meta.json') -Encoding utf8
Request '31-session-owned' $customerDir Get "/api/customer-service/sessions/$sessionId" $commonUser $null '200'
Request '32-session-other-user' $customerDir Get "/api/customer-service/sessions/$sessionId" $otherUser $null '403'
$sseHeaders = @{ 'X-User-Id' = $userId; Accept = 'text/event-stream' }
Request '33-sse-order-message' $customerDir Post "/api/customer-service/sessions/$sessionId/messages" $sseHeaders @{ content = "查询订单 $childOrderNo 的物流" } '200 message+complete; child logistics order'
Request '34-message-history' $customerDir Get "/api/customer-service/sessions/$sessionId/messages" $commonUser $null '200 >=2'
Request '35-message-history-other-user' $customerDir Get "/api/customer-service/sessions/$sessionId/messages" $otherUser $null '403'
Request '36-customer-products' $customerDir Get '/api/customer-service/products?keyword=Stage%20F' $commonUser $null '200'
Request '37-customer-product-detail' $customerDir Get "/api/customer-service/products/$skuId" $commonUser $null '200'

# P1: repeat operations, invalid quantity, zero inventory, cancellation, and illegal status.
Request '38-pay-repeat-same-token' $p1Dir Post "/api/orders/$parentOrderNo/pay" (@{ 'X-User-Id' = $userId; 'Idempotency-Token' = 'stage-f-pay-20261010' }) $null '200 idempotent'
Request '39-pay-repeat-different-token' $p1Dir Post "/api/orders/$parentOrderNo/pay" (@{ 'X-User-Id' = $userId; 'Idempotency-Token' = 'stage-f-pay-repeat-20261010' }) $null '409'
Request '40-cancel-shipped-parent' $p1Dir Post "/api/orders/$parentOrderNo/cancel" $commonUser $null '409'
Request '41-invalid-cart-quantity-zero' $p1Dir Post '/api/cart/items' $otherUser @{ skuId = $skuId; quantity = 0 } '400'
Request '42-zero-stock-cart-add' $p1Dir Post '/api/cart/items' $otherUser @{ skuId = $zeroStockSkuId; quantity = 1 } '200'
Request '43-zero-stock-checkout' $p1Dir Post '/api/orders/checkout' (@{ 'X-User-Id' = $otherUserId; 'Idempotency-Token' = 'stage-f-zero-stock-checkout-20261010' }) $null '409'
Request '44-empty-cart-checkout' $p1Dir Post '/api/orders/checkout' (@{ 'X-User-Id' = 'stage-f-empty-user-20261010'; 'Idempotency-Token' = 'stage-f-empty-checkout-20261010' }) $null '409'

# Fresh cancellable order: checkout, cancel, repeat cancel, and pay after cancellation.
$cancelUser = 'stage-f-cancel-user-20261010'
$cancelHeaders = @{ 'X-User-Id' = $cancelUser }
Request '45-cancel-user-cart-add' $p1Dir Post '/api/cart/items' $cancelHeaders @{ skuId = $skuId; quantity = 1 } '200'
$cancelCheckout = Request '46-cancel-user-checkout' $p1Dir Post '/api/orders/checkout' (@{ 'X-User-Id' = $cancelUser; 'Idempotency-Token' = 'stage-f-cancel-checkout-20261010' }) $null '200'
$cancelOrderNo = [string](Parse-Data $cancelCheckout)[0].orderNo
$meta.cancelOrderNo = $cancelOrderNo
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'live-run-meta.json') -Encoding utf8
Request '47-cancel-pending-order' $p1Dir Post "/api/orders/$cancelOrderNo/cancel" $cancelHeaders $null '200'
Request '48-cancel-repeat' $p1Dir Post "/api/orders/$cancelOrderNo/cancel" $cancelHeaders $null '409'
Request '49-pay-cancelled-order' $p1Dir Post "/api/orders/$cancelOrderNo/pay" (@{ 'X-User-Id' = $cancelUser; 'Idempotency-Token' = 'stage-f-cancel-pay-20261010' }) $null '409'

$meta.finishedAt = (Get-Date).ToString('o')
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultRoot 'live-run-meta.json') -Encoding utf8
Write-Output ($meta | ConvertTo-Json -Depth 10)