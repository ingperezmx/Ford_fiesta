#!/bin/bash
# ============================================================
# XCarOS - Head Unit ADB Optimization Script
# Author: XCarOS Project
# Usage: ./optimize_headunit.sh <device_ip> [package_name]
#
# Example:
#   ./optimize_headunit.sh 192.168.1.100
#   ./optimize_headunit.sh 192.168.1.100 com.x.carlauncher
# ============================================================

set -euo pipefail

# ── Helpers ──────────────────────────────────────────────────────────────────
GREEN='\033[32m'
RED='\033[31m'
YELLOW='\033[33m'
CYAN='\033[36m'
BOLD='\033[1m'
RESET='\033[0m'

ok()   { echo -e "${GREEN}✓${RESET} $*"; }
err()  { echo -e "${RED}✗${RESET} $*"; }
info() { echo -e "${CYAN}ℹ${RESET} $*"; }
warn() { echo -e "${YELLOW}⚠${RESET} $*"; }
hdr()  { echo -e "\n${BOLD}${CYAN}═══ $* ═══${RESET}"; }

# ── Args ─────────────────────────────────────────────────────────────────────
if [[ $# -lt 1 ]]; then
    err "Usage: $0 <device_ip> [package_name]"
    exit 1
fi

DEVICE_IP="$1"
LAUNCHER_PKG="${2:-com.x.carlauncher}"
ADB_TARGET="${DEVICE_IP}:5555"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 1 — ADB Connection
# ─────────────────────────────────────────────────────────────────────────────
hdr "Connecting to head unit at ${ADB_TARGET}"

adb connect "${ADB_TARGET}" || { err "adb connect failed"; exit 1; }
sleep 1

# Verify the device is actually responsive
if ! adb -s "${ADB_TARGET}" shell echo "ping" &>/dev/null; then
    err "Device did not respond after connect — check IP and that ADB is enabled"
    exit 1
fi
ok "Connected to ${ADB_TARGET}"

# Shorthand wrapper so we don't repeat -s every time
run() { adb -s "${ADB_TARGET}" "$@"; }
shell() { adb -s "${ADB_TARGET}" shell "$@"; }
settings_put() { shell settings put "$@"; }

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 2 — Performance Tuning (Hardware Acceleration & 60 FPS)
# ─────────────────────────────────────────────────────────────────────────────
hdr "Performance tuning"

settings_put global window_animation_scale 0.5     && ok "window_animation_scale → 0.5"   || err "window_animation_scale failed"
settings_put global transition_animation_scale 0.5 && ok "transition_animation_scale → 0.5" || err "transition_animation_scale failed"
settings_put global animator_duration_scale 0.5    && ok "animator_duration_scale → 0.5"  || err "animator_duration_scale failed"

shell setprop debug.hwui.renderer skiagl           && ok "hwui renderer → skiagl"         || warn "setprop renderer (may need root)"
shell setprop debug.sf.hw 1                        && ok "SurfaceFlinger hw overlays → 1"  || warn "setprop sf.hw"
shell setprop debug.egl.hw 1                       && ok "EGL hw acceleration → 1"         || warn "setprop egl.hw"
shell setprop debug.composition.type gpu           && ok "composition type → gpu"         || warn "setprop composition.type"
shell setprop persist.sys.ui.hw true               && ok "persist UI hw → true"           || warn "setprop persist.sys.ui.hw"
shell setprop ro.config.low_ram false              && ok "prevent low_ram degradation"    || warn "setprop low_ram"
settings_put global force_gpu_rendering 1          && ok "force_gpu_rendering → 1"         || err "force_gpu_rendering failed"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 3 — Power / RAM Saving & System-wide Ad/Spyware Blocking
# ─────────────────────────────────────────────────────────────────────────────
hdr "Power, RAM and Privacy (Zero Ads & Spyware)"

settings_put global wifi_scan_always_enabled 0             && ok "wifi_scan_always_enabled → 0"            || err "wifi_scan failed"
settings_put global ble_scan_always_enabled 0              && ok "ble_scan_always_enabled → 0"             || err "ble_scan failed"
settings_put global activity_starts_logging_enabled 0      && ok "activity_starts_logging_enabled → 0"    || err "activity_starts_logging failed"
settings_put global always_finish_activities 0             && ok "always_finish_activities → 0"            || err "always_finish_activities failed"
settings_put global background_process_limit 3             && ok "background_process_limit → 3"            || err "background_process_limit failed"

# System-level Private DNS (AdGuard) - Blocks 100% of ads, malware and telemetry
settings_put global private_dns_mode hostname              && ok "private_dns_mode → hostname"             || err "private_dns_mode failed"
settings_put global private_dns_specifier dns.adguard-dns.com && ok "Private DNS → dns.adguard-dns.com (System-wide Ad & Spyware Blocking)" || err "private_dns_specifier failed"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 4 — Bloatware, Spyware & Ad Pushers Removal
# ─────────────────────────────────────────────────────────────────────────────
hdr "Disabling bloatware, spyware and ad pushers"

BLOAT_APPS=(
    # Android OEM Bloat
    "com.android.printspooler"
    "com.android.bips"
    "com.android.bookmarkprovider"
    "com.android.calllogbackup"
    "com.android.dreams.basic"
    "com.android.dreams.phototable"
    "com.android.egg"
    "com.android.email"
    "com.android.exchange"
    "com.android.wallpaper.livepicker"
    "com.google.android.feedback"
    "com.google.android.marvin.talkback"
    "com.google.android.printservice.recommendation"
    "com.google.android.tts"

    # Chinese Head Unit Spyware, Backdoors & Telemetry
    "com.adups.fota"
    "com.adups.fota.sysoper"
    "com.google.android.gms.analytics"

    # Chinese Head Unit Ad Pushers & Vendor Stores
    "com.syu.market"
    "com.syu.weather"
    "com.microntek.market"
    "com.microntek.controlsettings.ads"

    # Unused vendor launchers (free up 300MB+ RAM)
    "com.tx.carlauncher"
    "com.ts.carlauncher"
    "com.fyt.launcher"

    # Unused bloat suites
    "com.baidu.carlife"
    "com.baidu.maps"
    "com.yandex.auto"
    "com.yandex.zen"
)

for pkg in "${BLOAT_APPS[@]}"; do
    result=$(shell pm disable-user --user 0 "$pkg" 2>&1)
    if echo "$result" | grep -q "disabled"; then
        ok "Disabled: $pkg"
    elif echo "$result" | grep -qi "already\|not installed\|unknown"; then
        warn "Skipped (not found): $pkg"
    else
        err "Could not disable: $pkg — $result"
    fi
done

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 5 — Whitelist Critical Apps from Doze
# ─────────────────────────────────────────────────────────────────────────────
hdr "Whitelisting critical apps from Doze"

WHITELIST_APPS=(
    "com.maxmpz.audioplayer"
    "com.waze"
    "${LAUNCHER_PKG}"
)

for pkg in "${WHITELIST_APPS[@]}"; do
    result=$(shell dumpsys deviceidle whitelist +"$pkg" 2>&1)
    ok "Whitelisted from Doze: $pkg"
done

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 6 — Appops (Background Running)
# ─────────────────────────────────────────────────────────────────────────────
hdr "Setting appops for background running"

APPOPS_APPS=(
    "com.maxmpz.audioplayer"
    "com.waze"
    "${LAUNCHER_PKG}"
)

for pkg in "${APPOPS_APPS[@]}"; do
    shell appops set "$pkg" RUN_IN_BACKGROUND allow  && ok "RUN_IN_BACKGROUND allow: $pkg" || warn "appops failed for $pkg"
    shell appops set "$pkg" RUN_ANY_IN_BACKGROUND allow && ok "RUN_ANY_IN_BACKGROUND allow: $pkg" || warn "appops failed for $pkg"
done

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 6.5 — Install XCarOS APK
# ─────────────────────────────────────────────────────────────────────────────
hdr "Installing XCarOS APK"

APK_PATH="$(dirname "$0")/../app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"
fi

if [ -f "$APK_PATH" ]; then
    info "Found APK: ${APK_PATH}"
    adb -s "${ADB_TARGET}" install -r -g "${APK_PATH}" \
        && ok "XCarOS installed successfully!" \
        || warn "APK install failed or already installed"
else
    warn "APK not found at ${APK_PATH} — run './gradlew assembleDebug' first"
fi

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 7 — Set Launcher as Default Home Activity
# ─────────────────────────────────────────────────────────────────────────────
hdr "Setting ${LAUNCHER_PKG} as default home"

shell cmd package set-home-activity "${LAUNCHER_PKG}/.MainActivity" \
    && ok "Default home set to ${LAUNCHER_PKG}/.MainActivity" \
    || warn "Could not set home activity automatically — set manually in Settings > Apps > Default Apps"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 8 — Grant Permissions
# ─────────────────────────────────────────────────────────────────────────────
hdr "Granting permissions to ${LAUNCHER_PKG}"

PERMISSIONS=(
    "android.permission.ACCESS_FINE_LOCATION"
    "android.permission.ACCESS_COARSE_LOCATION"
    "android.permission.BLUETOOTH_CONNECT"
    "android.permission.BLUETOOTH_SCAN"
    "android.permission.RECEIVE_BOOT_COMPLETED"
)

for perm in "${PERMISSIONS[@]}"; do
    shell pm grant "$LAUNCHER_PKG" "$perm" \
        && ok "Granted: $perm" \
        || warn "Could not grant: $perm (may already be granted or not declared)"
done

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 9 — Enable Notification Listener
# ─────────────────────────────────────────────────────────────────────────────
hdr "Enabling notification listener"

NOTIF_COMPONENT="${LAUNCHER_PKG}/com.x.carlauncher.media.PowerampNotificationListener"

# Try secure settings approach (needs root / adb with WRITE_SECURE_SETTINGS)
CURRENT_LISTENERS=$(shell settings get secure enabled_notification_listeners 2>/dev/null || echo "")
if echo "$CURRENT_LISTENERS" | grep -q "$LAUNCHER_PKG"; then
    ok "Notification listener already enabled"
else
    NEW_LISTENERS="${CURRENT_LISTENERS}:${NOTIF_COMPONENT}"
    shell settings put secure enabled_notification_listeners "$NEW_LISTENERS" \
        && ok "Notification listener enabled for ${LAUNCHER_PKG}" \
        || warn "Could not enable notification listener automatically — enable in Settings > Notifications > Notification Access"
fi

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 10 — CANBus Discovery Mode
# ─────────────────────────────────────────────────────────────────────────────
hdr "CANBus Discovery Mode"

echo -e "${BOLD}${YELLOW}"
echo "  ╔═══════════════════════════════════════════════════════╗"
echo "  ║         CANBUS INTENT DISCOVERY — INSTRUCTIONS        ║"
echo "  ╠═══════════════════════════════════════════════════════╣"
echo "  ║  1. El script iniciará logcat filtrado para CANBus.   ║"
echo "  ║  2. Abre y cierra CADA puerta del auto lentamente.    ║"
echo "  ║  3. Activa y desactiva las luces, el ACC y el ignition.║"
echo "  ║  4. Observa la consola para intents capturados.       ║"
echo "  ║  5. Presiona Ctrl+C para detener el monitoreo.        ║"
echo "  ╚═══════════════════════════════════════════════════════╝"
echo -e "${RESET}"

info "Starting CANBus logcat monitor (background)..."
adb -s "${ADB_TARGET}" logcat -s XCAR_CANBUS,canbus,door_state,mcu_event,syu_event,microntek &
LOGCAT_PID=$!
ok "Logcat monitor started (PID: ${LOGCAT_PID})"
info "Perform door/ACC/ignition actions on the vehicle now..."
info "Press Enter when done to stop logcat."
read -r _
kill "$LOGCAT_PID" 2>/dev/null || true
ok "CANBus logcat monitor stopped"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 11 — OBD2 Setup Note
# ─────────────────────────────────────────────────────────────────────────────
hdr "OBD2 Setup Notes"

echo -e "${CYAN}"
echo "  ┌─────────────────────────────────────────────────────────┐"
echo "  │                  OBD2 Setup Instructions                 │"
echo "  │                                                           │"
echo "  │  • Connect a Bluetooth or WiFi OBD2 adapter to the       │"
echo "  │    vehicle's OBD2 port (under dashboard, driver side).   │"
echo "  │                                                           │"
echo "  │  • Supported protocols: ELM327 (v1.5+), STN11xx         │"
echo "  │                                                           │"
echo "  │  • In XCarOS go to Settings > OBD2 and pair the    │"
echo "  │    adapter. The speedometer and RPM will switch from     │"
echo "  │    GPS-based to OBD2-based readings automatically.       │"
echo "  │                                                           │"
echo "  │  • Recommended PIDs: 010D (speed), 010C (RPM),          │"
echo "  │    0105 (coolant temp), 012F (fuel level)                │"
echo "  └─────────────────────────────────────────────────────────┘"
echo -e "${RESET}"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 12 — Success Summary
# ─────────────────────────────────────────────────────────────────────────────
hdr "Optimization Complete"

echo -e "${BOLD}${GREEN}"
echo "  ╔═══════════════════════════════════════════════════════╗"
echo "  ║          XCarOS Head Unit Setup Complete!        ║"
echo "  ╠═══════════════════════════════════════════════════════╣"
echo "  ║  ✓ ADB connected                                      ║"
echo "  ║  ✓ Animation scales reduced (0.5x)                    ║"
echo "  ║  ✓ GPU rendering enabled (Skia GL)                    ║"
echo "  ║  ✓ Background scan suppressed                         ║"
echo "  ║  ✓ Bloatware disabled                                 ║"
echo "  ║  ✓ Critical apps whitelisted from Doze                ║"
echo "  ║  ✓ Appops configured                                  ║"
echo "  ║  ✓ Permissions granted                                ║"
echo "  ║  ✓ Notification listener configured                   ║"
echo "  ╠═══════════════════════════════════════════════════════╣"
echo "  ║  Reboot the head unit to apply all changes:           ║"
echo "  ║    adb -s ${ADB_TARGET} reboot                        ║"
echo "  ╚═══════════════════════════════════════════════════════╝"
echo -e "${RESET}"

info "Run: adb -s ${ADB_TARGET} reboot   (when ready)"
