@echo off
chcp 65001 >nul
title PabloCarOS — Paso 3: Revertir al Estado de Fábrica
color 0E

echo ======================================================================
echo    🔄 PabloCarOS — Revertir al Estado de Fábrica
echo    Desarrollado por X
echo ======================================================================
echo.
echo Este asistente devolverá tu pantalla al launcher que traía de fábrica.
echo Todos tus ajustes originales se restaurarán de forma segura.
echo.

if not exist "ip_pantalla.txt" (
    color 0C
    echo [ERROR] No se encontró el archivo de conexión ip_pantalla.txt.
    echo Asegúrate de tener conexión con tu pantalla.
    pause
    exit /b
)

set /p IP_RADIO=<ip_pantalla.txt
set IP_RADIO=%IP_RADIO: =%

set ADB_CMD=adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    set ADB_CMD=platform-tools\adb.exe
)

echo Conectando con %IP_RADIO%:5555 ...
%ADB_CMD% connect %IP_RADIO%:5555 >nul 2>nul

echo [1/3] Restaurando animaciones originales del sistema...
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global window_animation_scale 1.0"
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global transition_animation_scale 1.0"
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global animator_duration_scale 1.0"

echo [2/3] Quitando PabloCarOS como pantalla de inicio predeterminada...
%ADB_CMD% -s %IP_RADIO%:5555 shell "cmd package clear-package-preferred-activities com.x.carlauncher.debug" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "cmd package clear-package-preferred-activities com.x.carlauncher" >nul 2>nul

echo [3/3] Abriendo el selector de pantalla de inicio en tu radio...
%ADB_CMD% -s %IP_RADIO%:5555 shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >nul 2>nul

echo.
set /p DESINSTALAR="¿Deseas desinstalar la app PabloCarOS por completo de la pantalla? (S/N): "
if /i "%DESINSTALAR%"=="S" (
    echo Desinstalando aplicación...
    %ADB_CMD% -s %IP_RADIO%:5555 uninstall com.x.carlauncher.debug >nul 2>nul
    %ADB_CMD% -s %IP_RADIO%:5555 uninstall com.x.carlauncher >nul 2>nul
    echo [OK] PabloCarOS ha sido desinstalado.
)

color 0A
echo.
echo ======================================================================
echo   ✅ TU PANTALLA HA VUELTO A SU ESTADO DE FÁBRICA ORIGINAL
echo ======================================================================
echo.
echo Puedes volver a instalar PabloCarOS cuando gustes ejecutando:
echo 1_CONECTAR_PANTALLA.bat y 2_INSTALAR_PabloCarOS.bat
echo.
pause

