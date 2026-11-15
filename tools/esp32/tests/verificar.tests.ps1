<#
.SYNOPSIS
    Pruebas de run-demo.ps1 -Verificar: la protección contra la base de producción y la variable de loopback.

.DESCRIPTION
    Corre run-demo.ps1 -Verificar -SinEsp32 con archivos de variables de prueba y comprueba el resultado de la
    comprobación 2 (la base no es producción) y de la comprobación 1. No arranca nada ni necesita internet: los hosts son
    de un dominio .invalid. Usa la misma edición de PowerShell que lo ejecuta, así que se corre dos veces:
        powershell -ExecutionPolicy Bypass -File tools\esp32\tests\verificar.tests.ps1
        pwsh -ExecutionPolicy Bypass -File tools\esp32\tests\verificar.tests.ps1
#>
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script = Join-Path $PSScriptRoot '..\run-demo.ps1'
$shell = (Get-Process -Id $PID).Path
$work = Join-Path ([IO.Path]::GetTempPath()) ("agrosense-verificar-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$failures = 0

function Invoke-Verificar([string]$dbHost, [string]$prodHost, [string[]]$extra = @()) {
    $file = Join-Path $work 'env.local'
    $lines = @(
        "DB_HOST=$dbHost", 'DB_NAME=neondb', 'DB_USER=u', 'DB_PASSWORD=p', "PROD_DB_HOST=$prodHost",
        'JWT_SECRET=un-secreto-de-prueba-de-mas-de-sesenta-y-cuatro-bytes-0123456789abcdefghijklmnop',
        'DEMO_PASSWORD=demo', 'ESP32_TCP_HOST=192.168.4.1', 'ESP32_TCP_PORT=8080', 'ESP32_DEVICE_ID=ESP32-PLANTICAS',
        'BACKEND_PORT=9000', 'FRONTEND_PORT=8080') + $extra
    Set-Content -LiteralPath $file -Value $lines -Encoding UTF8
    $output = & $shell -NoProfile -ExecutionPolicy Bypass -File $script -Verificar -SinEsp32 -EnvFile $file 2>&1 | Out-String
    return $output
}

function Assert-Case([string]$name, [string]$output, [string]$pattern, [bool]$present) {
    $found = $output -match $pattern
    if ($found -eq $present) {
        Write-Host "  [ok] $name" -ForegroundColor Green
    } else {
        $script:failures++
        Write-Host "  [X]  $name" -ForegroundColor Red
        Write-Host ($output -split "`n" | Select-String -Pattern '== 2|DB_HOST|ESP32_TCP_ALLOW' | Out-String)
    }
}

$blocked = 'DB_HOST es la base de PRODUCCI'
$prod = 'ep-cool-darkness-123456.us-east-2.aws.neon.invalid'
$prodPooler = 'ep-cool-darkness-123456-pooler.us-east-2.aws.neon.invalid'

Write-Host "run-demo.ps1 -Verificar con $shell"
try {
    Assert-Case 'Bloquea el mismo host' (Invoke-Verificar $prod $prod) $blocked $true
    Assert-Case 'Bloquea DB_HOST con -pooler y PROD_DB_HOST sin él' (Invoke-Verificar $prodPooler $prod) $blocked $true
    Assert-Case 'Bloquea DB_HOST sin -pooler y PROD_DB_HOST con él' (Invoke-Verificar $prod $prodPooler) $blocked $true
    Assert-Case 'Bloquea aunque cambien las mayúsculas' (Invoke-Verificar 'EP-Cool-Darkness-123456-POOLER.us-east-2.aws.neon.invalid' $prod) $blocked $true
    Assert-Case 'Bloquea una dirección pegada con esquema y puerto' (Invoke-Verificar "postgresql://u@$($prodPooler):5432/neondb" $prod) $blocked $true
    Assert-Case 'Deja pasar otra branch (otro endpoint)' (Invoke-Verificar 'ep-quiet-river-654321-pooler.us-east-2.aws.neon.invalid' $prod) $blocked $false
    Assert-Case 'Otra branch dice que no es producción' (Invoke-Verificar 'ep-quiet-river-654321.us-east-2.aws.neon.invalid' $prod) 'no es la de producci' $true
    Assert-Case 'Rechaza ESP32_TCP_ALLOW_LOOPBACK en el archivo' (Invoke-Verificar 'ep-quiet-river-654321.us-east-2.aws.neon.invalid' $prod @('ESP32_TCP_ALLOW_LOOPBACK=true')) 'ESP32_TCP_ALLOW_LOOPBACK no se admite' $true
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force
}

Write-Host ''
if ($failures -gt 0) {
    Write-Host "$failures prueba(s) fallaron." -ForegroundColor Red
    exit 1
}
Write-Host 'Todas las pruebas pasaron.' -ForegroundColor Green
exit 0
