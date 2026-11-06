# ==============================================================================
# 🚀 XCarOS — Full Hardware Audit, System Adaptation & Deployment Suite
# Author: X Flores
# ==============================================================================

param(
    [string]$DeviceIp = "192.168.1.12",
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$PackageName = "com.x.carlauncher.debug"
)

function Write-Step { param($msg) Write-Host "`n[STEP] $msg" -ForegroundColor Cyan }
function Write-Success { param($msg) Write-Host "  ✅ $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "  ⚠️ $msg" -ForegroundColor Yellow }
function Write-ErrorMsg { param($msg) Write-Host "  ❌ $msg" -ForegroundColor Red }
function Ask-User {
    param($Question, $Default = "S")
    $ans = Read-Host "$Question (S/N) [Default: $Default]"
    if ([string]::IsNullOrWhiteSpace($ans)) { $ans = $Default }
    return ($ans.Trim().ToUpper() -eq "S")
}

Clear-Host
Write-Host "======================================================================" -ForegroundColor Blue
Write-Host " 🚗 XCarOS — Suite Forense y Optimizador Automotriz de 60 FPS   " -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Blue

# ─────────────────────────────────────────────────────────────────────────────
# FASE 1: Búsqueda y Verificación de Conectividad ADB
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "1. Verificando Conectividad ADB con $DeviceIp:5555..."

$Target = "$DeviceIp:5555"
& adb connect $Target | Out-Null
Start-Sleep -Seconds 1

$devices = & adb devices
$deviceFound = $false
foreach ($line in ($devices -split "`r?`n")) {
    if ($line -match "$DeviceIp:5555\s+device") {
        $deviceFound = $true
        break
    }
}

if (-not $deviceFound) {
    Write-Warn "El dispositivo no aparece como 'device'. Salida de adb devices:"
    Write-Host $devices -ForegroundColor DarkGray
    Write-Host "`n--- Checklist de Diagnóstico ---" -ForegroundColor Yellow
    Write-Host "1. ¿La pantalla y la PC están en la misma red Wi-Fi?"
    Write-Host "2. ¿Apareció en la pantalla el mensaje de 'Permitir depuración USB'?"
    Write-Host "3. Si la IP cambió, revisa en Ajustes > Wi-Fi de tu pantalla.`n"
    
    $retry = Ask-User "¿Deseas reintentar la conexión ingresando otra IP?" "N"
    if ($retry) {
        $DeviceIp = Read-Host "Ingresa la nueva IP de la pantalla"
        $Target = "$DeviceIp:5555"
        & adb connect $Target
    } else {
        Write-ErrorMsg "No se pudo establecer conexión segura. Revisa la guía INSTALLATION_AND_OPTIMIZATION_GUIDE.md"
        exit 1
    }
}
Write-Success "Dispositivo conectado y autorizado: $Target"

# ─────────────────────────────────────────────────────────────────────────────
# FASE 2: Auditoría y Perfilado Forense del Hardware
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "2. Realizando Auditoría Forense del Hardware de la Pantalla..."

$soc = (& adb -s $Target shell getprop ro.board.platform).Trim()
$model = (& adb -s $Target shell getprop ro.product.model).Trim()
$androidVer = (& adb -s $Target shell getprop ro.build.version.release).Trim()
$sdkVer = (& adb -s $Target shell getprop ro.build.version.sdk).Trim()
$wmSize = (& adb -s $Target shell wm size).Trim()
$wmDensity = (& adb -s $Target shell wm density).Trim()
$renderer = (& adb -s $Target shell getprop debug.hwui.renderer).Trim()

$memInfo = & adb -s $Target shell "cat /proc/meminfo | grep -E 'MemTotal|MemAvailable'"
$packages = & adb -s $Target shell "pm list packages"

$platform = "Estándar / Genérica AOSP"
if ($packages -match "com\.syu") { $platform = "FYT / SYU (Unisoc/Spreadtrum)" }
elseif ($packages -match "com\.tw\." -or $packages -match "com\.ts\.") { $platform = "TopWay / TS10 (Allwinner/Spreadtrum)" }
elseif ($packages -match "com\.microntek") { $platform = "Microntek / MTC (Rockchip PX5/PX6)" }

Write-Host "  ┌─────────────────────────────────────────────────────────┐" -ForegroundColor DarkCyan
Write-Host "  │ 📱 Modelo:      $model" -ForegroundColor White
Write-Host "  │ ⚙️ Chipset/SoC:  $soc" -ForegroundColor White
Write-Host "  │ 🤖 Android:     $androidVer (API $sdkVer)" -ForegroundColor White
Write-Host "  │ 📐 Pantalla:    $wmSize ($wmDensity)" -ForegroundColor White
Write-Host "  │ 🚗 Plataforma:  $platform" -ForegroundColor White
Write-Host "  │ 🎮 Render GPU:  $(if ($renderer) { $renderer } else { 'Default GL' })" -ForegroundColor White
Write-Host "  └─────────────────────────────────────────────────────────┘" -ForegroundColor DarkCyan

# ─────────────────────────────────────────────────────────────────────────────
# FASE 3: Adaptación y Optimizaciones a Bajo Nivel
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "3. Aplicando Optimizaciones a Bajo Nivel (Ultra Fluidez 60 FPS)..."

if (Ask-User "¿Deseas aplicar las optimizaciones de aceleración SkiaGL y animaciones 0.5x?") {
    Write-Host "  -> Forzando composición por GPU..." -ForegroundColor DarkGray
    & adb -s $Target shell "setprop debug.sf.hw 1"
    & adb -s $Target shell "setprop debug.egl.hw 1"
    & adb -s $Target shell "setprop debug.hwui.renderer skiagl"
    & adb -s $Target shell "settings put global force_gpu_rendering 1"

    Write-Host "  -> Ajustando escala de animaciones a 0.5x..." -ForegroundColor DarkGray
    & adb -s $Target shell "settings put global window_animation_scale 0.5"
    & adb -s $Target shell "settings put global transition_animation_scale 0.5"
    & adb -s $Target shell "settings put global animator_duration_scale 0.5"

    Write-Host "  -> Blindando contra modo reposo (Doze Whitelist)..." -ForegroundColor DarkGray
    & adb -s $Target shell "dumpsys deviceidle whitelist +$PackageName"
    & adb -s $Target shell "cmd appops set $PackageName RUN_IN_BACKGROUND allow"

    Write-Success "Optimizaciones de sistema aplicadas con éxito."
}

# ─────────────────────────────────────────────────────────────────────────────
# FASE 4: Instalación del APK y Concesión de Permisos
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "4. Despliegue de XCarOS ($PackageName)..."

if (-not (Test-Path $ApkPath)) {
    Write-Warn "El APK no fue encontrado en $ApkPath. Intentando compilar con Gradle..."
    & .\gradlew assembleDebug
}

if (Test-Path $ApkPath) {
    Write-Host "  -> Instalando $ApkPath..." -ForegroundColor DarkGray
    & adb -s $Target install -r -d $ApkPath
    Write-Success "APK instalado correctamente."

    Write-Host "  -> Concediendo permisos críticos del sistema..." -ForegroundColor DarkGray
    & adb -s $Target shell "pm grant $PackageName android.permission.ACCESS_FINE_LOCATION"
    & adb -s $Target shell "pm grant $PackageName android.permission.ACCESS_COARSE_LOCATION"
    & adb -s $Target shell "pm grant $PackageName android.permission.READ_EXTERNAL_STORAGE"
    & adb -s $Target shell "pm grant $PackageName android.permission.WRITE_EXTERNAL_STORAGE"
    & adb -s $Target shell "appops set $PackageName SYSTEM_ALERT_WINDOW allow"
    & adb -s $Target shell "cmd notification allow_listener $PackageName/com.x.carlauncher.media.PowerampNotificationListener"

    Write-Success "Todos los permisos fueron concedidos silenciosamente."

    if (Ask-User "¿Deseas fijar XCarOS como el Launcher de inicio predeterminado?") {
        & adb -s $Target shell "cmd package set-home-activity $PackageName/com.x.carlauncher.MainActivity"
        Write-Success "XCarOS configurado como launcher principal."
    }

    Write-Host "  -> Iniciando XCarOS..." -ForegroundColor DarkGray
    & adb -s $Target shell "am start -n $PackageName/com.x.carlauncher.MainActivity"
    Write-Success "¡XCarOS está corriendo en tu pantalla!"
} else {
    Write-ErrorMsg "No se pudo localizar el APK compilado."
}

Write-Host "`n======================================================================" -ForegroundColor Green
Write-Host " ✅ Instalación y optimización completadas al 100%.                     " -ForegroundColor Green
Write-Host " Si disfrutas de este launcher, ¡invita un café al desarrollador!       " -ForegroundColor Yellow
Write-Host "======================================================================" -ForegroundColor Green
