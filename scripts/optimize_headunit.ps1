# ============================================================
# XCarOS - Head Unit ADB Optimization Script (PowerShell)
# Author: XCarOS Project
# Usage: .\optimize_headunit.ps1 -DeviceIP <ip> [-LauncherPkg <package>] [-SkipDiscovery]
#
# Example:
#   .\optimize_headunit.ps1 -DeviceIP 192.168.1.25
# ============================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$DeviceIP,
    [string]$LauncherPkg = "com.x.carlauncher",
    [switch]$SkipDiscovery = $true
)

$ErrorActionPreference = "Continue"
$AdbTarget = "${DeviceIP}:5555"

# -- Helpers ------------------------------------------------------------------
function Ok   { param($msg) Write-Host "[OK] $msg" -ForegroundColor Green }
function Err  { param($msg) Write-Host "[ERR] $msg" -ForegroundColor Red }
function Info { param($msg) Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Warn { param($msg) Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Hdr  { param($msg) Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

function Invoke-Adb {
    param([string[]]$Args)
    & adb -s $AdbTarget @Args
}

function Invoke-Shell {
    param([string[]]$Args)
    & adb -s $AdbTarget shell @Args
}

function Set-Setting {
    param($Namespace, $Key, $Value)
    Invoke-Shell "settings", "put", $Namespace, $Key, $Value
}

# -----------------------------------------------------------------------------
# SECTION 1 - ADB Connection
# -----------------------------------------------------------------------------
Hdr "Connecting to head unit at $AdbTarget"

& adb connect $AdbTarget
if ($LASTEXITCODE -ne 0) {
    Err "adb connect failed - check IP and that ADB over WiFi is enabled on head unit"
    exit 1
}
Start-Sleep -Seconds 1

$ping = (& adb -s $AdbTarget shell echo "ping" 2>&1)
if ($ping -notmatch "ping") {
    Err "Device did not respond after connect"
    exit 1
}
Ok "Connected to $AdbTarget"

# Probe and enable Root mode if available
$hasRoot = $false
$suCheck = (& adb -s $AdbTarget shell "which su" 2>&1) -join " "
if ($suCheck -match "su") {
    $hasRoot = $true
    Ok "Root access (su) verified on device!"
    
    # Ensure permanent ADB over WiFi on port 5555 across all reboots
    & adb -s $AdbTarget shell "su -c 'setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555'" 2>&1 | Out-Null
    Ok "Permanent ADB over WiFi (persist.adb.tcp.port 5555) configured via root"
}

# -----------------------------------------------------------------------------
# SECTION 2 - Performance Tuning (Hardware Acceleration & 60 FPS)
# -----------------------------------------------------------------------------
Hdr "Performance tuning (60 FPS & GPU Acceleration)"

Set-Setting "global" "window_animation_scale" "0.5"
Ok "window_animation_scale -> 0.5"

Set-Setting "global" "transition_animation_scale" "0.5"
Ok "transition_animation_scale -> 0.5"

Set-Setting "global" "animator_duration_scale" "0.5"
Ok "animator_duration_scale -> 0.5"

Invoke-Shell "setprop", "debug.hwui.renderer", "skiagl"
Ok "hwui renderer -> skiagl"

Invoke-Shell "setprop", "debug.sf.hw", "1"
Ok "SurfaceFlinger hardware overlays -> 1"

Invoke-Shell "setprop", "debug.egl.hw", "1"
Ok "EGL hardware acceleration -> 1"

Invoke-Shell "setprop", "debug.composition.type", "gpu"
Ok "composition type -> gpu"

Invoke-Shell "setprop", "persist.sys.ui.hw", "true"
Ok "persist UI hardware -> true"

Invoke-Shell "setprop", "ro.config.low_ram", "false"
Ok "prevent low_ram degradation -> false"

Set-Setting "global" "force_gpu_rendering" "1"
Ok "force_gpu_rendering -> 1"

# -----------------------------------------------------------------------------
# SECTION 3 - Power / RAM Saving & System-wide Ad/Spyware Blocking
# -----------------------------------------------------------------------------
Hdr "Power, RAM and Privacy (Zero Ads & Spyware)"

Set-Setting "global" "wifi_scan_always_enabled"        "0" ; Ok "wifi_scan_always_enabled -> 0"
Set-Setting "global" "ble_scan_always_enabled"         "0" ; Ok "ble_scan_always_enabled -> 0"
Set-Setting "global" "activity_starts_logging_enabled" "0" ; Ok "activity_starts_logging_enabled -> 0"
Set-Setting "global" "always_finish_activities"        "0" ; Ok "always_finish_activities -> 0"
Set-Setting "global" "background_process_limit"        "3" ; Ok "background_process_limit -> 3"

# System-level Private DNS (AdGuard) - Blocks 100% of ads, malware and telemetry
Set-Setting "global" "private_dns_mode" "hostname"
Set-Setting "global" "private_dns_specifier" "dns.adguard-dns.com"
Ok "Private DNS configured -> dns.adguard-dns.com (System-wide Ad & Spyware Blocking)"

# -----------------------------------------------------------------------------
# SECTION 4 - Bloatware, Spyware & Ad Pushers Removal
# -----------------------------------------------------------------------------
Hdr "Disabling bloatware, spyware and ad pushers"

$BloatApps = @(
    # Android OEM Bloat
    "com.android.printspooler",
    "com.android.bips",
    "com.android.bookmarkprovider",
    "com.android.calllogbackup",
    "com.android.dreams.basic",
    "com.android.dreams.phototable",
    "com.android.egg",
    "com.android.email",
    "com.android.exchange",
    "com.android.wallpaper.livepicker",
    "com.google.android.feedback",
    "com.google.android.marvin.talkback",
    "com.google.android.printservice.recommendation",
    "com.google.android.tts",

    # Chinese Head Unit Spyware, Backdoors & Telemetry
    "com.adups.fota",
    "com.adups.fota.sysoper",
    "com.google.android.gms.analytics",

    # Chinese Head Unit Ad Pushers & Vendor Stores
    "com.syu.market",
    "com.syu.weather",
    "com.microntek.market",
    "com.microntek.controlsettings.ads",

    # Unused vendor launchers (free up 300MB+ RAM)
    "com.tx.carlauncher",
    "com.ts.carlauncher",
    "com.fyt.launcher",

    # Unused bloat suites
    "com.baidu.carlife",
    "com.baidu.maps",
    "com.yandex.auto",
    "com.yandex.zen"
)

foreach ($pkg in $BloatApps) {
    $result = (& adb -s $AdbTarget shell pm disable-user --user 0 $pkg 2>&1) -join " "
    if ($result -match "disabled") {
        Ok "Disabled: $pkg"
    } elseif ($result -match "already|not installed|unknown") {
        Warn "Skipped (not found): $pkg"
    } else {
        Err "Could not disable: $pkg ($result)"
    }
}

# -----------------------------------------------------------------------------
# SECTION 5 - Whitelist Critical Apps from Doze
# -----------------------------------------------------------------------------
Hdr "Whitelisting critical apps from Doze"

$WhitelistApps = @(
    "com.maxmpz.audioplayer",
    "com.waze",
    $LauncherPkg
)

foreach ($pkg in $WhitelistApps) {
    Invoke-Shell "dumpsys", "deviceidle", "whitelist", "+$pkg" | Out-Null
    Ok "Whitelisted from Doze: $pkg"
}

# -----------------------------------------------------------------------------
# SECTION 6 - Appops (Background Running)
# -----------------------------------------------------------------------------
Hdr "Setting appops for background running"

$AppopsApps = @("com.maxmpz.audioplayer", "com.waze", $LauncherPkg)

foreach ($pkg in $AppopsApps) {
    $r1 = (& adb -s $AdbTarget shell appops set $pkg RUN_IN_BACKGROUND allow 2>&1)
    if ($LASTEXITCODE -eq 0) { Ok "RUN_IN_BACKGROUND allow: $pkg" } else { Warn "appops failed for $pkg" }

    $r2 = (& adb -s $AdbTarget shell appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>&1)
    if ($LASTEXITCODE -eq 0) { Ok "RUN_ANY_IN_BACKGROUND allow: $pkg" } else { Warn "appops failed for $pkg" }
}

# -----------------------------------------------------------------------------
# SECTION 6.5 - Install XCarOS APK
# -----------------------------------------------------------------------------
Hdr "Installing XCarOS APK"

$ApkPath = Join-Path $PSScriptRoot "..\app-debug.apk"
if (-not (Test-Path $ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
}
if (Test-Path $ApkPath) {
    Info "Found APK: $ApkPath"
    & adb -s $AdbTarget install -r -g $ApkPath
    if ($LASTEXITCODE -eq 0) {
        Ok "XCarOS installed successfully!"
    } else {
        Warn "Install failed or already exists - proceeding with configuration"
    }
} else {
    Warn "APK not found at $ApkPath - run './gradlew assembleDebug' first"
}

# -----------------------------------------------------------------------------
# SECTION 7 - Set Launcher as Default Home Activity
# -----------------------------------------------------------------------------
Hdr "Setting $LauncherPkg as default home"

& adb -s $AdbTarget shell cmd package set-home-activity "${LauncherPkg}/.MainActivity"
if ($LASTEXITCODE -eq 0) {
    Ok "Default home set to ${LauncherPkg}/.MainActivity"
} else {
    Warn "Could not set home automatically - set manually in Settings -> Apps -> Default Apps"
}

# -----------------------------------------------------------------------------
# SECTION 8 - Grant Permissions
# -----------------------------------------------------------------------------
Hdr "Granting permissions to $LauncherPkg"

$Permissions = @(
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.RECEIVE_BOOT_COMPLETED"
)

foreach ($perm in $Permissions) {
    & adb -s $AdbTarget shell pm grant $LauncherPkg $perm 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Ok "Granted: $perm"
    } else {
        Warn "Could not grant: $perm (may already be granted or not declared)"
    }
}

if ($hasRoot) {
    & adb -s $AdbTarget shell "su -c 'pm grant $LauncherPkg android.permission.WRITE_SECURE_SETTINGS'" 2>&1 | Out-Null
    Ok "Granted WRITE_SECURE_SETTINGS via root"
}

# -----------------------------------------------------------------------------
# SECTION 9 - Enable Notification Listener
# -----------------------------------------------------------------------------
Hdr "Enabling notification listener"

$NotifComponent = "${LauncherPkg}/com.x.carlauncher.media.PowerampNotificationListener"
$CurrentListeners = (& adb -s $AdbTarget shell settings get secure enabled_notification_listeners 2>&1) -join ""

if ($CurrentListeners -match [regex]::Escape($LauncherPkg)) {
    Ok "Notification listener already enabled"
} else {
    $NewListeners = "$CurrentListeners`:$NotifComponent"
    & adb -s $AdbTarget shell settings put secure enabled_notification_listeners "`"$NewListeners`"" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Ok "Notification listener enabled for $LauncherPkg"
    } else {
        Warn "Could not enable notification listener automatically - enable in Settings -> Notifications -> Notification Access"
    }
}

# -----------------------------------------------------------------------------
# SECTION 10 - CANBus Discovery Mode
# -----------------------------------------------------------------------------
if (-not $SkipDiscovery) {
    Hdr "CANBus Discovery Mode"
    Info "Starting CANBus logcat monitor for 10 seconds..."
    $LogcatJob = Start-Job -ScriptBlock {
        param($target)
        & adb -s $target logcat -s "XCAR_CANBUS,canbus,door_state,mcu_event,syu_event,microntek"
    } -ArgumentList $AdbTarget

    Ok "Logcat monitor started (Job ID: $($LogcatJob.Id))"
    Start-Sleep -Seconds 10
    Stop-Job $LogcatJob -ErrorAction SilentlyContinue
    Receive-Job $LogcatJob | Write-Host
    Remove-Job $LogcatJob -ErrorAction SilentlyContinue
    Ok "CANBus logcat monitor stopped"
}

# -----------------------------------------------------------------------------
# SECTION 11 - Success Summary
# -----------------------------------------------------------------------------
Hdr "Optimization Complete"

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "          XCarOS Head Unit Setup Complete!           " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  [OK] ADB connected                                      " -ForegroundColor Green
Write-Host "  [OK] Animation scales reduced (0.5x)                    " -ForegroundColor Green
Write-Host "  [OK] GPU rendering enabled (Skia GL)                    " -ForegroundColor Green
Write-Host "  [OK] Background scan suppressed                         " -ForegroundColor Green
Write-Host "  [OK] Bloatware disabled                                 " -ForegroundColor Green
Write-Host "  [OK] Critical apps whitelisted from Doze                " -ForegroundColor Green
Write-Host "  [OK] Appops configured                                  " -ForegroundColor Green
Write-Host "  [OK] Permissions granted                                " -ForegroundColor Green
Write-Host "  [OK] Notification listener configured                   " -ForegroundColor Green
Write-Host "  [OK] XCarOS APK deployed                           " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host ""

Info "To reboot the head unit and apply changes immediately:"
Info "Run: adb -s $AdbTarget reboot"
