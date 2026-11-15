<#
.SYNOPSIS
    Arranca AgroSense en este PC con el ESP32 real: backend y frontend contra una branch de desarrollo de Neon.

.DESCRIPTION
    Lee tools/esp32/.env.local, comprueba todo antes de arrancar (archivo, base que no sea producción, secreto, Java,
    puertos, internet y ESP32), arranca el backend, espera a que responda, arranca el frontend y muestra el estado del
    receptor del ESP32. Las variables solo llegan a los procesos de cada aplicación. Para detenerlo: stop-demo.ps1.
    Funciona en Windows PowerShell 5.1 y en PowerShell 7. Sin Docker.

.PARAMETER SinEsp32
    Plan B: arranca con ESP32_TCP_ENABLED=false y no comprueba el ESP32.

.PARAMETER Simulado
    Plan B sin placa: arranca un ESP32 falso en Python (esp32_simulado.py, el mismo formato de bloque) en 127.0.0.1 y lee
    de él. Solo en este modo, y solo en el proceso del backend, se activa ESP32_TCP_ALLOW_LOOPBACK. El resumen dice
    DATOS SIMULADOS. No necesita la red PLANTICAS ni el manejador de eventos.

.PARAMETER Usb
    Lee el ESP32 REAL por el cable USB cuando el PC no puede unirse a la red PLANTICAS. Arranca esp32_usb.py, que lee el
    puerto serie (ESP32_SERIAL_PORT o el primer adaptador CH340/CP210x que encuentre) y sirve el mismo bloque TCP en
    127.0.0.1. Como en -Simulado, solo el proceso del backend recibe ESP32_TCP_ALLOW_LOOPBACK. Necesita pyserial.

.PARAMETER PrepararBase
    Aplica schema.sql, las migraciones 001 a 005 y seed_demo.sql en la base (y la contraseña de la cuenta demo). Pide
    escribir SI. Nunca contra producción.

.PARAMETER BaseLocal
    Sin Neon: usa una base H2 en archivo en tools/esp32/.run/h2 (git la ignora), que se crea y se llena con los datos de
    ejemplo sola en cada arranque (idempotente). No necesita DB_HOST ni internet para la base. Los datos quedan solo en
    este PC.

.PARAMETER NoAbrir
    No abre el navegador al final.

.PARAMETER Verificar
    Solo hace las comprobaciones previas, sin arrancar nada.

.PARAMETER EnvFile
    Otro archivo de variables en lugar de tools/esp32/.env.local (para pruebas).
#>
[CmdletBinding()]
param(
    [switch]$SinEsp32,
    [switch]$Simulado,
    [switch]$Usb,
    [switch]$PrepararBase,
    [switch]$BaseLocal,
    [switch]$NoAbrir,
    [switch]$Verificar,
    [string]$EnvFile = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$Tools = $PSScriptRoot
$Workspace = (Resolve-Path (Join-Path $Tools '..\..')).Path
$RunDir = Join-Path $Tools '.run'
$PidFile = Join-Path $RunDir 'pids.txt'
if (-not $EnvFile) { $EnvFile = Join-Path $Tools '.env.local' }
$DemoEmail = 'demo@agrosense.co'
$Required = @('DB_HOST', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'PROD_DB_HOST', 'JWT_SECRET', 'DEMO_PASSWORD',
    'ESP32_TCP_HOST', 'ESP32_TCP_PORT', 'ESP32_DEVICE_ID', 'BACKEND_PORT', 'FRONTEND_PORT')
# Variables of the file passed to the backend as they are, besides the ones built below.
$BackendPassThrough = @('ESP32_TCP_HOST', 'ESP32_TCP_PORT', 'ESP32_DEVICE_ID', 'ESP32_AGGREGATE_SECONDS', 'IRRIGATION_ACTUATOR',
    'SENSOR_CALIBRATION', 'AI_SERVICE_URL', 'AI_SERVICE_KEY')
# Never read from the variables file: only -Simulado sets it, and only for the backend's process.
$LoopbackSwitch = 'ESP32_TCP_ALLOW_LOOPBACK'
$SimulatedHost = '127.0.0.1'
$SimulatedPortDefault = 18080

# ---------------------------------------------------------------- output

function Write-Ok([string]$text) { Write-Host "  [ok] $text" -ForegroundColor Green }
function Write-Bad([string]$text, [string]$fix) {
    Write-Host "  [X]  $text" -ForegroundColor Red
    if ($fix) { Write-Host "       Qué hacer: $fix" -ForegroundColor Yellow }
}
function Write-Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Stop-Demo([string]$text) {
    Write-Host ''
    Write-Host $text -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- the variables file

function Read-EnvFile([string]$path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $text = $line.Trim()
        if ($text -eq '' -or $text.StartsWith('#')) { continue }
        $at = $text.IndexOf('=')
        if ($at -lt 1) { continue }
        $key = $text.Substring(0, $at).Trim()
        $value = $text.Substring($at + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

function Get-Value([hashtable]$values, [string]$key, [string]$default = '') {
    if ($values.ContainsKey($key) -and $values[$key] -ne '') { return $values[$key] }
    return $default
}

function Test-Marker([string]$value) { return $value -match '^<.*>$' }

# The Neon endpoint a host belongs to: its first label, lowercase and without "-pooler". So
# ep-cool-darkness-123456-pooler.us-east-2.aws.neon.tech and EP-COOL-DARKNESS-123456.us-east-2.aws.neon.tech are the same
# database. A scheme, credentials, a port or a path pasted around the host are dropped first.
function Get-NeonEndpoint([string]$hostName) {
    $text = $hostName.Trim().ToLowerInvariant()
    $text = $text -replace '^[a-z][a-z0-9+.-]*://', ''
    if ($text.Contains('@')) { $text = $text.Substring($text.LastIndexOf('@') + 1) }
    $text = ($text -split '[/:?]')[0]
    $label = ($text -split '\.')[0]
    return ($label -replace '-pooler$', '')
}

function Get-Esp32SerialPort([hashtable]$values) {
    $configured = Get-Value $values 'ESP32_SERIAL_PORT'
    if ($configured) { return $configured }
    # The usual USB-to-serial chips of ESP32 boards; their friendly name ends in (COMn).
    $device = Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(CH340|CH910|CP210|USB.*(Serial|UART)).*\((COM\d+)\)' } | Select-Object -First 1
    if ($device -and $device.Name -match '\((COM\d+)\)') { return $Matches[1] }
    return $null
}

function Get-PythonExe {
    # The python.exe in WindowsApps is only a shortcut to the Store: it does not run scripts.
    foreach ($name in @('python', 'py')) {
        $found = Get-Command $name -ErrorAction SilentlyContinue | Where-Object { $_.Source -notlike '*\WindowsApps\*' } | Select-Object -First 1
        if ($found) { return $found.Source }
    }
    return $null
}

# ---------------------------------------------------------------- checks

function Test-TcpPort([string]$hostName, [int]$port, [int]$timeoutMs) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.ConnectAsync($hostName, $port)
        if (-not $attempt.Wait($timeoutMs)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-PortFree([int]$port) {
    $listening = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    return -not $listening
}

function Get-JavaExe {
    # mvnw uses JAVA_HOME when it is set, and the java on the PATH otherwise.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $found = Get-Command java -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    return $null
}

function Get-JavaMajor([string]$java) {
    $text = (cmd /d /c "`"$java`" -version 2>&1") -join "`n"
    if ($text -match 'version "(\d+)(\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[3]) { $major = [int]$Matches[3] }
        return $major
    }
    return 0
}

function Get-PomJava([string]$pom) {
    $match = Select-String -LiteralPath $pom -Pattern '<java.version>(\d+)</java.version>' | Select-Object -First 1
    if ($match) { return [int]$match.Matches[0].Groups[1].Value }
    return 0
}

function Invoke-Checks([hashtable]$values, [bool]$useEsp32, [bool]$isH2, [bool]$simulated, [bool]$usb = $false) {
    $failed = 0

    Write-Step '1. Archivo de variables'
    $missing = @($Required | Where-Object { -not $values.ContainsKey($_) -or $values[$_] -eq '' })
    if ($isH2) { $missing = @($missing | Where-Object { $_ -notin @('DB_HOST', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'PROD_DB_HOST') }) }
    $markers = @($values.Keys | Where-Object { Test-Marker $values[$_] } | Sort-Object)
    if ($isH2) { $markers = @($markers | Where-Object { $_ -notin @('DB_HOST', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'PROD_DB_HOST') }) }
    if ($missing.Count -gt 0) {
        Write-Bad "Faltan variables: $($missing -join ', ')" "Cópialas de tools\esp32\.env.local.example a $EnvFile y llénalas."
        $failed++
    } elseif ($markers.Count -gt 0) {
        Write-Bad "Quedan marcadores sin llenar en: $($markers -join ', ')" "Reemplaza cada valor <...> en $EnvFile."
        $failed++
    } else {
        Write-Ok "Variables completas ($EnvFile)"
    }
    if ($values.ContainsKey($LoopbackSwitch)) {
        Write-Bad "$LoopbackSwitch no se admite en el archivo de variables" "Bórrala de $EnvFile. Para probar sin la placa usa run-demo.ps1 -Simulado, que la activa solo para su ESP32 falso."
        $failed++
    }

    Write-Step '2. La base no es producción'
    if ($isH2) {
        Write-Ok 'Base H2 local de este PC: no es producción'
    } else {
        $dbHost = (Get-Value $values 'DB_HOST').Trim().ToLowerInvariant()
        $prodHost = (Get-Value $values 'PROD_DB_HOST').Trim().ToLowerInvariant()
        if (-not $dbHost -or -not $prodHost -or (Test-Marker $dbHost) -or (Test-Marker $prodHost)) {
            Write-Bad 'No se puede comparar: falta DB_HOST o PROD_DB_HOST' 'Llena los dos en el archivo de variables.'
            $failed++
        } elseif ((Get-NeonEndpoint $dbHost) -eq (Get-NeonEndpoint $prodHost)) {
            # The endpoint, not the host: with and without -pooler, and in any case, it is the same database.
            Write-Bad "DB_HOST es la base de PRODUCCIÓN (endpoint $(Get-NeonEndpoint $dbHost))" 'Usa el host de una branch de desarrollo de Neon, nunca el de producción, con o sin -pooler.'
            $failed++
        } else {
            Write-Ok "DB_HOST (endpoint $(Get-NeonEndpoint $dbHost)) no es la de producción (endpoint $(Get-NeonEndpoint $prodHost))"
            if ($dbHost -like '*-pooler*') {
                Write-Host '       Aviso: es un host con pooler; usa el host directo (sin -pooler) si el frontend falla con la sesión.' -ForegroundColor Yellow
            }
        }
    }

    Write-Step '3. JWT_SECRET'
    $secret = Get-Value $values 'JWT_SECRET'
    $bytes = [Text.Encoding]::UTF8.GetByteCount($secret.Trim())
    if (Test-Marker $secret) {
        Write-Bad 'JWT_SECRET sigue con el marcador' 'Genera uno con el comando del archivo de ejemplo.'
        $failed++
    } elseif ($bytes -lt 64) {
        Write-Bad "JWT_SECRET tiene $bytes bytes; el backend exige 64 o más" 'Genera uno más largo con el comando del archivo de ejemplo.'
        $failed++
    } else {
        Write-Ok "JWT_SECRET de $bytes bytes"
    }

    Write-Step '4. Java'
    $needBackend = Get-PomJava (Join-Path $Workspace 'backend\agrosense\pom.xml')
    $needFrontend = Get-PomJava (Join-Path $Workspace 'frontend\pom.xml')
    $need = [Math]::Max($needBackend, $needFrontend)
    $java = Get-JavaExe
    if (-not $java) {
        Write-Bad 'No se encontró Java' "Instala un JDK $need o más nuevo (por ejemplo Microsoft Build of OpenJDK $need) y abre una consola nueva."
        $failed++
    } else {
        $major = Get-JavaMajor $java
        if ($major -lt $need) {
            Write-Bad "Java $major en $java; el backend pide $needBackend y el frontend $needFrontend" "Instala un JDK $need o más nuevo, o apunta JAVA_HOME a uno."
            $failed++
        } else {
            Write-Ok "Java $major ($java); el backend pide $needBackend+, el frontend $needFrontend+"
        }
    }

    Write-Step '5. Puertos libres'
    foreach ($entry in @(@('backend', 'BACKEND_PORT'), @('frontend', 'FRONTEND_PORT'))) {
        $port = [int](Get-Value $values $entry[1] '0')
        if ($port -le 0) {
            Write-Bad "$($entry[1]) no es un puerto" 'Pon un número, por ejemplo 9000 y 8080.'
            $failed++
        } elseif (Test-PortFree $port) {
            Write-Ok "Puerto $port libre para el $($entry[0])"
        } else {
            Write-Bad "El puerto $port ($($entry[0])) ya está en uso" 'Corre tools\esp32\stop-demo.ps1, cierra la otra aplicación o cambia el puerto en el archivo de variables.'
            $failed++
        }
    }

    Write-Step '6. Internet hasta la base'
    if ($isH2) {
        Write-Ok 'Base H2 local: no hace falta internet'
    } else {
        $dbHost = Get-Value $values 'DB_HOST'
        if ((Test-Marker $dbHost) -or -not $dbHost) {
            Write-Bad 'Sin DB_HOST no se puede probar' 'Llena DB_HOST.'
            $failed++
        } elseif (Test-TcpPort $dbHost 5432 8000) {
            Write-Ok "Conexión TCP a $($dbHost):5432"
        } else {
            Write-Bad "No se pudo abrir una conexión a $($dbHost):5432" 'Conecta internet por cable o por el celular en USB; el Wi-Fi PLANTICAS no tiene internet. Revisa también que DB_HOST esté bien escrito.'
            $failed++
        }
    }

    Write-Step '7. ESP32'
    if ($usb) {
        $simPort = [int](Get-Value $values 'ESP32_SIM_PORT' "$SimulatedPortDefault")
        $python = Get-PythonExe
        $serialPort = Get-Esp32SerialPort $values
        $hasSerial = $false
        if ($python) { & $python -c 'import serial' 2>$null; $hasSerial = ($LASTEXITCODE -eq 0) }
        if (-not $python) {
            Write-Bad 'Modo USB: no se encontró Python' 'Instala Python 3 (python.org) y abre una consola nueva.'
            $failed++
        } elseif (-not $hasSerial) {
            Write-Bad 'Modo USB: falta pyserial' "Instálalo con: `"$python`" -m pip install pyserial"
            $failed++
        } elseif (-not $serialPort) {
            Write-Bad 'Modo USB: no se encontró el ESP32 en ningún puerto COM' 'Conecta la placa por USB (instala el driver CH340 o CP210x si Windows no la reconoce) o pon ESP32_SERIAL_PORT=COMn en el archivo de variables.'
            $failed++
        } elseif (-not (Test-PortFree $simPort)) {
            Write-Bad "Modo USB: el puerto $simPort del puente está en uso" 'Corre stop-demo.ps1 o pon otro ESP32_SIM_PORT en el archivo de variables.'
            $failed++
        } else {
            Write-Ok "Modo USB: el ESP32 real por $serialPort, servido en $($SimulatedHost):$simPort (cierra PuTTY y el monitor serie de Arduino)"
        }
    } elseif ($simulated) {
        $simPort = [int](Get-Value $values 'ESP32_SIM_PORT' "$SimulatedPortDefault")
        $python = Get-PythonExe
        if (-not $python) {
            Write-Bad 'Modo simulado: no se encontró Python' 'Instala Python 3 (python.org) y abre una consola nueva; el ESP32 falso solo usa la biblioteca estándar.'
            $failed++
        } elseif (-not (Test-PortFree $simPort)) {
            Write-Bad "Modo simulado: el puerto $simPort del ESP32 falso está en uso" 'Corre stop-demo.ps1 o pon otro ESP32_SIM_PORT en el archivo de variables.'
            $failed++
        } else {
            Write-Ok "Modo simulado: ESP32 falso con $python en $($SimulatedHost):$simPort (no se comprueba la placa)"
        }
    } elseif (-not $useEsp32) {
        Write-Ok 'Sin ESP32 (-SinEsp32 o ESP32_TCP_ENABLED=false)'
    } else {
        $espHost = Get-Value $values 'ESP32_TCP_HOST' '192.168.4.1'
        $espPort = [int](Get-Value $values 'ESP32_TCP_PORT' '8080')
        # The same test as Test-NetConnection -Port, faster: it opens and closes one connection.
        if (Test-TcpPort $espHost $espPort 4000) {
            Write-Ok "El ESP32 responde en $($espHost):$espPort"
        } else {
            Write-Bad "El ESP32 no responde en $($espHost):$espPort" 'Conecta el PC a la red PLANTICAS y cierra PuTTY u otro cliente: el ESP32 atiende uno solo. Si no aparece la red, revisa que la placa esté encendida.'
            $failed++
        }
    }
    return $failed
}

# ---------------------------------------------------------------- processes

function Get-Running {
    $alive = @()
    if (Test-Path $PidFile) {
        foreach ($line in Get-Content $PidFile) {
            $parts = $line -split '\|'
            if ($parts.Count -lt 3) { continue }
            $process = Get-Process -Id ([int]$parts[1]) -ErrorAction SilentlyContinue
            if ($process -and $process.StartTime.ToString('o') -eq $parts[2]) { $alive += $parts[0] }
        }
    }
    return $alive
}

function Start-Logged([string]$name, [string]$dir, [string]$command, [hashtable]$vars) {
    $log = Join-Path $RunDir "$name.log"
    if (Test-Path $log) { Remove-Item $log -Force }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $env:ComSpec
    # cmd drops the outer quotes and keeps the inner ones: the paths have spaces.
    $info.Arguments = "/d /c `"$command > `"$log`" 2>&1`""
    $info.WorkingDirectory = $dir
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    # Only this process and its children get these: the user's session and the system keep theirs.
    foreach ($key in @('SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')) {
        if ($info.EnvironmentVariables.ContainsKey($key)) { $info.EnvironmentVariables.Remove($key) }
    }
    foreach ($key in $vars.Keys) { $info.EnvironmentVariables[$key] = [string]$vars[$key] }
    $process = [System.Diagnostics.Process]::Start($info)
    Add-Content -LiteralPath $PidFile -Value "$name|$($process.Id)|$($process.StartTime.ToString('o'))"
    Write-Host "  $name arrancando (PID $($process.Id)); registro: tools\esp32\.run\$name.log"
    return $process
}

function Start-App([string]$name, [string]$dir, [hashtable]$vars, [string[]]$mavenArgs) {
    $mvnw = Join-Path $dir 'mvnw.cmd'
    return Start-Logged $name $dir "`"$mvnw`" $($mavenArgs -join ' ')" $vars
}

function Get-Hint([string]$log) {
    if (-not (Test-Path $log)) { return 'No hay registro: revisa que exista mvnw.cmd.' }
    $text = Get-Content -LiteralPath $log -Raw
    if ($text -match 'UnsupportedClassVersionError|release version \d+ not supported|invalid target release') { return 'Java: la versión de Java es menor que la que pide el pom.xml.' }
    if ($text -match 'Address already in use|Port \d+ was already in use|already in use') { return 'Puerto: otro programa ocupa el puerto. Corre stop-demo.ps1 o cambia el puerto.' }
    if ($text -match 'JWT_SECRET|jwt\.secret|at least 64') { return 'Secreto: revisa JWT_SECRET (64 bytes o más).' }
    if ($text -match 'Schema-validation|missing table|missing column') { return 'Base: faltan tablas o columnas. Corre run-demo.ps1 -PrepararBase (con la branch de desarrollo).' }
    if ($text -match 'password authentication failed|FATAL|UnknownHost|Connection refused|connect timed out|SSL|PSQLException|HikariPool') { return 'Base: no se pudo conectar. Revisa DB_HOST, DB_NAME, DB_USER, DB_PASSWORD e internet por cable o celular.' }
    if ($text -match 'ESP32_TCP_HOST must be a private') { return 'ESP32: ESP32_TCP_HOST debe ser una IPv4 privada, por ejemplo 192.168.4.1.' }
    if ($text -match 'SEED_USER_PASSWORD') { return 'Cuenta demo: falta DEMO_PASSWORD.' }
    if ($text -match 'Could not resolve dependencies|Could not transfer artifact') { return 'Maven no pudo descargar dependencias: hace falta internet la primera vez.' }
    return 'Revisa el registro completo.'
}

function Show-Failure([string]$name, [string]$text) {
    $log = Join-Path $RunDir "$name.log"
    Write-Host ''
    Write-Host "El $name no arrancó: $text" -ForegroundColor Red
    if (Test-Path $log) {
        Write-Host "Últimas 30 líneas de tools\esp32\.run\$name.log:" -ForegroundColor Yellow
        Get-Content -LiteralPath $log -Tail 30 | ForEach-Object { Write-Host "    $_" }
    }
    Write-Host "Pista: $(Get-Hint $log)" -ForegroundColor Yellow
    Write-Host 'Los procesos que arrancaron siguen en marcha: detenlos con tools\esp32\stop-demo.ps1.'
    exit 1
}

function Wait-Http([string]$name, [System.Diagnostics.Process]$process, [string]$url, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) { Show-Failure $name "el proceso terminó (código $($process.ExitCode))" }
        try {
            $answer = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($answer.StatusCode -eq 200) { Write-Ok "$name responde en $url"; return }
        } catch { }
        Start-Sleep -Seconds 3
    }
    Show-Failure $name "no respondió en $url en $seconds s"
}

# ---------------------------------------------------------------- main

Write-Host 'AgroSense con el ESP32 real' -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Bad "No existe $EnvFile" 'Copia tools\esp32\.env.local.example como tools\esp32\.env.local y llénalo.'
    exit 1
}
$values = Read-EnvFile $EnvFile
$override = Get-Value $values 'DATABASE_URL'
if ($override -and -not $override.StartsWith('jdbc:h2:')) {
    Stop-Demo 'DATABASE_URL en el archivo de variables solo se admite para una base H2 de prueba. Para Neon usa DB_HOST, DB_NAME, DB_USER y DB_PASSWORD.'
}
if ($BaseLocal -and $override) { Stop-Demo 'Usa -BaseLocal o DATABASE_URL en el archivo, no los dos.' }
if ($BaseLocal -and $PrepararBase) { Stop-Demo '-BaseLocal ya prepara su base en cada arranque: no hace falta -PrepararBase.' }
if ($BaseLocal) {
    $h2 = (Join-Path $RunDir 'h2\agrosense') -replace '\\', '/'
    $override = "jdbc:h2:file:$h2;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
    Write-Host 'Modo -BaseLocal: base H2 en tools\esp32\.run\h2, no Neon; los datos quedan solo en este PC.' -ForegroundColor Magenta
}
$isH2 = [bool]$override
if ($Simulado -and $SinEsp32) { Stop-Demo 'Usa -Simulado o -SinEsp32, no los dos.' }
if ($Usb -and ($Simulado -or $SinEsp32)) { Stop-Demo 'Usa -Usb solo: lee la placa real, no se combina con -Simulado ni con -SinEsp32.' }
$useEsp32 = $Simulado -or $Usb -or ((-not $SinEsp32) -and ((Get-Value $values 'ESP32_TCP_ENABLED' 'true') -eq 'true'))
$simPort = [int](Get-Value $values 'ESP32_SIM_PORT' "$SimulatedPortDefault")
if ($Simulado) { Write-Host 'Modo -Simulado: un ESP32 falso en este PC; los datos NO son de la placa.' -ForegroundColor Magenta }
if ($Usb) { Write-Host 'Modo -Usb: el ESP32 real por el cable USB; no hace falta la red PLANTICAS.' -ForegroundColor Magenta }

$alive = @(Get-Running)
if ($alive.Count -gt 0 -and -not $Verificar) {
    Stop-Demo "Ya hay una demo en marcha ($($alive -join ', ')). Detenla con tools\esp32\stop-demo.ps1."
}

$failed = Invoke-Checks $values $useEsp32 $isH2 ([bool]$Simulado) ([bool]$Usb)
Write-Host ''
if ($failed -gt 0) {
    Write-Host "$failed comprobación(es) fallaron. Corrígelas y vuelve a intentarlo." -ForegroundColor Red
    exit 1
}
Write-Host 'Todas las comprobaciones pasaron.' -ForegroundColor Green
if ($Verificar) { exit 0 }

$backendPort = [int](Get-Value $values 'BACKEND_PORT' '9000')
$frontendPort = [int](Get-Value $values 'FRONTEND_PORT' '8080')
$database = Join-Path $Workspace 'database'
$databaseUri = 'file:' + ($database -replace '\\', '/')
if ($BaseLocal) {
    $jdbc = $override
    $dbUser = 'sa'
    $dbPassword = ''
} elseif ($isH2) {
    $jdbc = $override
    $dbUser = Get-Value $values 'DB_USER' 'sa'
    $dbPassword = Get-Value $values 'DB_PASSWORD' ''
} else {
    $jdbc = "jdbc:postgresql://$(Get-Value $values 'DB_HOST')/$(Get-Value $values 'DB_NAME')?sslmode=require"
    $dbUser = Get-Value $values 'DB_USER'
    $dbPassword = Get-Value $values 'DB_PASSWORD'
}
$common = @{
    DATABASE_DIR      = $database
    DATABASE_URL      = $jdbc
    DATABASE_USERNAME = $dbUser
    DATABASE_PASSWORD = $dbPassword
}

$backendVars = @{
    PORT                           = "$backendPort"
    JWT_SECRET                     = Get-Value $values 'JWT_SECRET'
    CORS_ALLOWED_ORIGINS           = "http://localhost:$frontendPort"
    LOGIN_MAX_ATTEMPTS_PER_ADDRESS = '0'
    ESP32_TCP_ENABLED              = $(if ($useEsp32) { 'true' } else { 'false' })
}
foreach ($key in $BackendPassThrough) { $value = Get-Value $values $key; if ($value) { $backendVars[$key] = $value } }
foreach ($entry in $common.GetEnumerator()) { $backendVars[$entry.Key] = $entry.Value }
if ($Simulado -or $Usb) {
    # The fake ESP32 and the USB bridge listen on loopback, which the backend refuses unless told: only here, only for its process.
    $backendVars['ESP32_TCP_ENABLED'] = 'true'
    $backendVars['ESP32_TCP_HOST'] = $SimulatedHost
    $backendVars['ESP32_TCP_PORT'] = "$simPort"
    $backendVars[$LoopbackSwitch] = 'true'
}

if ($PrepararBase) {
    Write-Step 'Preparar la base'
    $target = $(if ($isH2) { $jdbc } else { "$(Get-Value $values 'DB_HOST') / base $(Get-Value $values 'DB_NAME')" })
    Write-Host "  Se aplicarán schema.sql, migrations/001 a 005 y seed_demo.sql en: $target"
    Write-Host '  Todo es idempotente: se puede repetir. No borra datos. La cuenta demo recibe DEMO_PASSWORD.'
    $answer = Read-Host '  Escribe SI para continuar'
    if ($answer -cne 'SI') { Stop-Demo 'Cancelado: la base no se tocó.' }
    $migrations = @('001_devices.sql', '002_alert_types.sql', '003_reliability.sql', '004_esp32_air_humidity.sql', '005_air_humidity_alerts.sql') | ForEach-Object { "$databaseUri/migrations/$_" }
    $backendVars['SPRING_PROFILES_ACTIVE'] = 'seed'
    $backendVars['SQL_INIT_MODE'] = 'always'
    # schema.sql first (it builds an empty base) and then the migrations (they bring an older base up to date).
    $backendVars['SCHEMA_LOCATION'] = (@("$databaseUri/schema.sql") + $migrations) -join ','
    $backendVars['SEED_LOCATION'] = "$databaseUri/seed_demo.sql"
    $backendVars['SEED_USER_PASSWORD'] = Get-Value $values 'DEMO_PASSWORD'
}

if ($BaseLocal) {
    # A local base needs no confirmation: schema, migrations and sample rows on every start, all idempotent.
    $migrations = @('001_devices.sql', '002_alert_types.sql', '003_reliability.sql', '004_esp32_air_humidity.sql', '005_air_humidity_alerts.sql') | ForEach-Object { "$databaseUri/migrations/$_" }
    $backendVars['SPRING_PROFILES_ACTIVE'] = 'seed'
    $backendVars['SQL_INIT_MODE'] = 'always'
    $backendVars['SCHEMA_LOCATION'] = (@("$databaseUri/schema.sql") + $migrations) -join ','
    $backendVars['SEED_LOCATION'] = "$databaseUri/seed_demo.sql"
    $backendVars['SEED_USER_PASSWORD'] = Get-Value $values 'DEMO_PASSWORD'
}

$frontendVars = @{
    PORT                  = "$frontendPort"
    BACKEND_URL           = "http://localhost:$backendPort"
    BACKEND_JWT_SECRET    = Get-Value $values 'JWT_SECRET'
    SESSION_COOKIE_SECURE = 'false'
    IRRIGATION_ACTUATOR   = Get-Value $values 'IRRIGATION_ACTUATOR' 'simulated'
}
foreach ($entry in $common.GetEnumerator()) { $frontendVars[$entry.Key] = $entry.Value }

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
# H2 is only on the backend's test classpath; PostgreSQL is on both.
$backendArgs = @('-q', '-B', 'spring-boot:run')
if ($isH2) { $backendArgs += '-Dspring-boot.run.useTestClasspath=true' }

if ($Simulado) {
    Write-Step 'ESP32 simulado'
    $python = Get-PythonExe
    $fake = Join-Path $Tools 'esp32_simulado.py'
    $simulator = Start-Logged 'esp32-simulado' $Tools "`"$python`" -u `"$fake`" --port $simPort" @{}
    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Test-TcpPort $SimulatedHost $simPort 1000)) {
        if ($simulator.HasExited -or (Get-Date) -gt $deadline) { Show-Failure 'esp32-simulado' 'el ESP32 falso no abrió su puerto' }
        Start-Sleep -Milliseconds 500
    }
    Write-Ok "ESP32 falso escuchando en $($SimulatedHost):$simPort"
}

if ($Usb) {
    Write-Step 'ESP32 por USB'
    $python = Get-PythonExe
    $serialPort = Get-Esp32SerialPort $values
    $bridgeScript = Join-Path $Tools 'esp32_usb.py'
    $bridge = Start-Logged 'esp32-usb' $Tools "`"$python`" -u `"$bridgeScript`" --serial $serialPort --port $simPort" @{}
    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Test-TcpPort $SimulatedHost $simPort 1000)) {
        if ($bridge.HasExited -or (Get-Date) -gt $deadline) { Show-Failure 'esp32-usb' 'el puente USB no abrió su puerto' }
        Start-Sleep -Milliseconds 500
    }
    Write-Ok "Puente USB: el ESP32 de $serialPort servido en $($SimulatedHost):$simPort"
}

Write-Step 'Backend'
$backend = Start-App 'backend' (Join-Path $Workspace 'backend\agrosense') $backendVars $backendArgs
Wait-Http 'backend' $backend "http://localhost:$backendPort/api/health" 180

Write-Step 'Frontend'
$frontend = Start-App 'frontend' (Join-Path $Workspace 'frontend') $frontendVars @('-q', '-B', 'spring-boot:run')
Wait-Http 'frontend' $frontend "http://localhost:$frontendPort/health" 180

$backendLog = Join-Path $RunDir 'backend.log'
if ($useEsp32) {
    Write-Step 'ESP32'
    $line = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and -not $line) {
        $line = Select-String -LiteralPath $backendLog -Pattern 'ESP32: connected to' -SimpleMatch | Select-Object -Last 1
        if (-not $line) { Start-Sleep -Seconds 2 }
    }
    if ($line) {
        Write-Ok ($line.Line -replace '^.*(ESP32: connected to)', '$1')
    } else {
        if ($Simulado) {
            Write-Bad 'El backend no se conectó al ESP32 falso en 30 s' 'Revisa tools\esp32\.run\esp32-simulado.log y backend.log.'
        } elseif ($Usb) {
            Write-Bad 'El backend no se conectó al puente USB en 30 s' 'Revisa tools\esp32\.run\esp32-usb.log y backend.log; cierra PuTTY y el monitor serie.'
        } else {
            Write-Bad 'El backend no se conectó al ESP32 en 30 s' 'Sigue reintentando solo. Revisa que el PC siga en PLANTICAS y que no haya otro cliente (PuTTY) conectado.'
        }
    }
}

Write-Step $(if ($Simulado) { 'Estado del receptor del ESP32 (DATOS SIMULADOS)' } else { 'Estado del receptor del ESP32' })
try {
    $login = Invoke-RestMethod -Uri "http://localhost:$backendPort/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ email = $DemoEmail; password = (Get-Value $values 'DEMO_PASSWORD') } | ConvertTo-Json) -TimeoutSec 20
    $status = Invoke-RestMethod -Uri "http://localhost:$backendPort/api/devices/esp32/status" -Headers @{ Authorization = "Bearer $($login.token)" } -TimeoutSec 20
    $connected = $(if ($status.connected) { 'sí' } else { 'no' })
    $enabled = $(if ($status.enabled) { 'encendido' } else { 'apagado' })
    # PowerShell 7 turns the ISO time into a DateTime and 5.1 keeps the text: both are shown the same way.
    $last = 'todavía ninguna'
    if ($status.lastStoredAt) {
        $when = $status.lastStoredAt
        if ($when -isnot [datetime]) { $when = [datetimeoffset]::Parse([string]$when).LocalDateTime }
        $last = $when.ToString('yyyy-MM-dd HH:mm:ss')
    }
    Write-Host "  Receptor $enabled; conectado: $connected; dispositivo: $($status.deviceId)"
    Write-Host "  Bloques recibidos: $($status.receivedBlocks); descartados: $($status.discardedBlocks); lecturas guardadas: $($status.storedReadings)"
    Write-Host "  Última lectura guardada: $last (una cada $($status.aggregateSeconds) s)"
} catch {
    Write-Bad 'No se pudo pedir el estado (inicio de sesión con la cuenta demo)' 'Revisa DEMO_PASSWORD; si la base es nueva, arranca una vez con -PrepararBase.'
}

# No secret may reach a log.
$secrets = @(@((Get-Value $values 'JWT_SECRET'), (Get-Value $values 'DEMO_PASSWORD'), $dbPassword) | Where-Object { $_ -and $_.Length -ge 6 })
$leaks = @()
if ($secrets.Count -gt 0) {
    $leaks = @(Get-ChildItem -LiteralPath $RunDir -Filter '*.log' | Where-Object { Select-String -LiteralPath $_.FullName -Pattern $secrets -SimpleMatch -Quiet })
}
if ($leaks.Count -gt 0) {
    Write-Host "Aviso: un secreto aparece en $(($leaks | ForEach-Object Name) -join ', '). No compartas esos registros." -ForegroundColor Yellow
}

$url = "http://localhost:$frontendPort"
Write-Host ''
if ($Simulado) {
    Write-Host '*** DATOS SIMULADOS: las lecturas vienen de esp32_simulado.py, no de la placa ***' -ForegroundColor Magenta
}
if ($Usb) {
    Write-Host '*** ESP32 REAL POR USB: las lecturas vienen de la placa a través de esp32_usb.py ***' -ForegroundColor Magenta
}
if ($BaseLocal) {
    Write-Host '*** BASE LOCAL: H2 en tools\esp32\.run\h2, no Neon ***' -ForegroundColor Magenta
}
Write-Host "Frontend: $url" -ForegroundColor Green
Write-Host "Cuenta demo: $DemoEmail (la contraseña es DEMO_PASSWORD de tu .env.local)"
Write-Host 'Abre Cultivos > Café (la primera lectura aparece al minuto) o Sensores > Humedad del ESP32.' -ForegroundColor Green
Write-Host 'Para detener todo: tools\esp32\stop-demo.ps1'
if (-not $NoAbrir) { Start-Process $url }
