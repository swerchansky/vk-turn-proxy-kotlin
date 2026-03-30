# Получаем default gateway (IPv4)
$gateway = Get-NetRoute `
    -DestinationPrefix "0.0.0.0/0" `
    | Sort-Object RouteMetric `
    | Select-Object -First 1 -ExpandProperty NextHop

if (-not $gateway) {
    Write-Error "Не удалось определить default gateway"
    exit 1
}

Write-Host "Default gateway: $gateway"

# Читаем адреса из stdin
$input | ForEach-Object {
    $addr = $_.Trim()
    if ($addr -eq "") { return }

    Write-Host "Добавляем маршрут к $addr через $gateway"

    New-NetRoute `
        -DestinationPrefix "$addr/32" `
        -NextHop $gateway `
        -PolicyStore ActiveStore `
        -ErrorAction Stop
}

