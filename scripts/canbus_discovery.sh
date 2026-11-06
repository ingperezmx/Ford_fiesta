#!/bin/bash
# ============================================================
# XCarOS - CANBus Intent Discovery Tool
# Author: XCarOS Project
# Usage: ./canbus_discovery.sh <device_ip>
#
# Conecta al head unit por ADB y monitorea todos los broadcasts
# relacionados con puertas, CANBus, MCU y señales automotrices.
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
    err "Uso: $0 <device_ip>"
    echo "  Ejemplo: $0 192.168.1.100"
    exit 1
fi

DEVICE_IP="$1"
ADB_TARGET="${DEVICE_IP}:5555"
LOG_FILE="canbus_log_$(date +%Y%m%d_%H%M%S).txt"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 1 — ADB Connection
# ─────────────────────────────────────────────────────────────────────────────
hdr "Conectando al head unit — ${ADB_TARGET}"

adb connect "${ADB_TARGET}" || { err "adb connect falló"; exit 1; }
sleep 1

if ! adb -s "${ADB_TARGET}" shell echo "ok" &>/dev/null; then
    err "El dispositivo no respondió tras conectar"
    exit 1
fi
ok "Conectado a ${ADB_TARGET}"

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 2 — Instructions (in Spanish for X)
# ─────────────────────────────────────────────────────────────────────────────
hdr "Instrucciones"

echo -e "${BOLD}${YELLOW}"
echo "  ╔═══════════════════════════════════════════════════════════════╗"
echo "  ║              DESCUBRIMIENTO DE INTENTS CANBUS                 ║"
echo "  ╠═══════════════════════════════════════════════════════════════╣"
echo "  ║                                                               ║"
echo "  ║  El script monitoreará el logcat del head unit en busca       ║"
echo "  ║  de todos los intents relacionados con CANBus, puertas,       ║"
echo "  ║  MCU, Microntek, SYU y otros mensajes automotrices.           ║"
echo "  ║                                                               ║"
echo "  ║  ACCIONES A REALIZAR DURANTE EL MONITOREO:                   ║"
echo "  ║  ─────────────────────────────────────────────────────────── ║"
echo "  ║  1. Abre la puerta del CONDUCTOR (delantera izquierda)       ║"
echo "  ║     → espera 2 segundos → ciérrala.                          ║"
echo "  ║                                                               ║"
echo "  ║  2. Abre la puerta del COPILOTO (delantera derecha)          ║"
echo "  ║     → espera 2 segundos → ciérrala.                          ║"
echo "  ║                                                               ║"
echo "  ║  3. Abre la puerta TRASERA IZQUIERDA                         ║"
echo "  ║     → espera 2 segundos → ciérrala.                          ║"
echo "  ║                                                               ║"
echo "  ║  4. Abre la puerta TRASERA DERECHA                           ║"
echo "  ║     → espera 2 segundos → ciérrala.                          ║"
echo "  ║                                                               ║"
echo "  ║  5. Activa y desactiva el ACC (llave en posición ACC).        ║"
echo "  ║                                                               ║"
echo "  ║  6. Enciende y apaga las LUCES del automóvil.                ║"
echo "  ║                                                               ║"
echo "  ║  7. Abre y cierra la CAJUELA / MALETERO.                     ║"
echo "  ║                                                               ║"
echo "  ║  El log se guardará en:                                       ║"
echo "  ║    ${LOG_FILE}"
echo "  ║                                                               ║"
echo "  ║  Presiona Ctrl+C para detener cuando termines.                ║"
echo "  ╚═══════════════════════════════════════════════════════════════╝"
echo -e "${RESET}"

echo ""
read -r -p "Presiona Enter para comenzar el monitoreo..."
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# SECTION 3 — Logcat Monitor (filtered for CANBus-related tags)
# ─────────────────────────────────────────────────────────────────────────────
hdr "Iniciando monitoreo logcat (CANBus / puertas / MCU)"

info "Log guardándose en: ${LOG_FILE}"
info "Presiona Ctrl+C para detener."
echo ""

# Tags to monitor — covers known automotive head unit vendors:
#   canbus, door, mcu (microcontroller unit), syu (SYU Android units),
#   microntek (Microntek head units), tw (TopWay/TW units)
LOGCAT_FILTER="canbus:V door:V door_state:V mcu:V mcu_event:V syu:V syu_event:V microntek:V tw:V XCAR_CANBUS:V carservice:V CarService:V *:S"

# Dual output: terminal + log file
# Trap Ctrl+C to handle graceful shutdown
LOGCAT_PID=""
INTENT_PID=""

cleanup() {
    echo ""
    warn "Deteniendo monitoreo..."
    [[ -n "$LOGCAT_PID" ]] && kill "$LOGCAT_PID" 2>/dev/null || true
    [[ -n "$INTENT_PID" ]] && kill "$INTENT_PID" 2>/dev/null || true
    wait 2>/dev/null || true
    echo ""
    ok "Monitoreo detenido."
    echo ""
    hdr "Resultados"
    if [[ -f "$LOG_FILE" ]]; then
        LINE_COUNT=$(wc -l < "$LOG_FILE")
        ok "Log guardado: ${LOG_FILE} (${LINE_COUNT} líneas)"
        echo ""
        info "Líneas capturadas por tag:"
        grep -oP '(?<=)\w+(?=\s*:)' "$LOG_FILE" | sort | uniq -c | sort -rn | head -20 || true
        echo ""
        info "Para analizar el log:"
        echo "  grep -i 'door\|puerta'   ${LOG_FILE}"
        echo "  grep -i 'canbus\|mcu'    ${LOG_FILE}"
        echo "  grep -i 'intent\|action' ${LOG_FILE}"
    else
        warn "No se generó archivo de log."
    fi
    exit 0
}

trap cleanup INT TERM

# Clear logcat buffer first
adb -s "${ADB_TARGET}" logcat -c 2>/dev/null || true

# ── Primary logcat stream ─────────────────────────────────────────────────
{
    # Also capture raw intent broadcasts by monitoring am monitor output
    echo "=== XCarOS CANBus Discovery Log ===" 
    echo "=== Dispositivo: ${ADB_TARGET} ==="
    echo "=== Fecha: $(date) ==="
    echo ""
} | tee -a "$LOG_FILE"

adb -s "${ADB_TARGET}" logcat $LOGCAT_FILTER 2>&1 | tee -a "$LOG_FILE" &
LOGCAT_PID=$!
ok "Logcat iniciado (PID: ${LOGCAT_PID})"

# ── Secondary: broadcast intent monitor via am ────────────────────────────
{
    echo "" >> "$LOG_FILE"
    echo "=== AM BROADCAST MONITOR ===" >> "$LOG_FILE"
    adb -s "${ADB_TARGET}" shell "am monitor 2>&1" >> "$LOG_FILE" 2>&1
} &
INTENT_PID=$!
ok "Intent monitor iniciado (PID: ${INTENT_PID})"

# ── Additional: dump all recent broadcasts every 5 seconds ───────────────
info "Iniciando volcado periódico de broadcasts..."
while true; do
    sleep 5
    {
        echo "--- BROADCAST DUMP $(date) ---"
        adb -s "${ADB_TARGET}" shell "dumpsys activity broadcasts 2>/dev/null | grep -i -E 'door|canbus|mcu|syu|microntek|acc|ignition|tw\.' | head -50" 2>/dev/null
    } >> "$LOG_FILE" 2>/dev/null || true
done &
DUMP_PID=$!

# Wait indefinitely until Ctrl+C
wait "$LOGCAT_PID" 2>/dev/null || cleanup
