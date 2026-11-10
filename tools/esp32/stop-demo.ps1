<#
.SYNOPSIS
    Detiene el backend y el frontend que arrancó run-demo.ps1, y nada más.

.DESCRIPTION
    Lee los procesos de tools/esp32/.run/pids.txt y detiene cada uno con sus hijos (Maven y la aplicación), solo si
    sigue siendo el mismo proceso: se compara también su hora de inicio, así un PID reutilizado por otro programa no
    se toca. Otros procesos Java del PC quedan como estaban. Funciona en Windows PowerShell 5.1 y en PowerShell 7.
#>
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Continue'

$PidFile = Join-Path $PSScriptRoot '.run\pids.txt'
if (-not (Test-Path $PidFile)) {
    Write-Host 'No hay ninguna demo en marcha (no existe tools\esp32\.run\pids.txt).'
    exit 0
}

foreach ($line in Get-Content $PidFile) {
    $parts = $line -split '\|'
    if ($parts.Count -lt 3) { continue }
    $name = $parts[0]
    $id = [int]$parts[1]
    $process = Get-Process -Id $id -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "$name ya estaba detenido."
    } elseif ($process.StartTime.ToString('o') -ne $parts[2]) {
        Write-Host "$($name): el PID $id ahora es otro programa ($($process.ProcessName)); no se toca."
    } else {
        # /T stops the whole tree that cmd started: Maven and the application's JVM.
        & taskkill.exe /T /F /PID $id *> $null
        Write-Host "$name detenido (PID $id y sus procesos hijos)."
    }
}
Remove-Item $PidFile -Force
Write-Host 'Demo detenida.'
