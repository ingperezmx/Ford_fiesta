@echo off
chcp 65001 >nul
title Car Launcher — Paso 1: Conectar Pantalla por WiFi
color 0B

echo ======================================================================
echo    🚗 Car Launcher — Conexión Fácil a Pantalla Android
echo    Desarrollado por X
echo ======================================================================
echo.
echo Este instalador conectará tu PC con la pantalla de tu vehículo.
echo No necesitas saber nada de programación.
echo.

:: 1. Verificar si existe ADB, si no descargarlo de Google
set ADB_CMD=adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    if not exist "platform-tools\adb.exe" (
        echo [INFO] Descargando herramientas oficiales de Android (ADB de Google)...
        powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile 'platform-tools.zip'"
        echo [INFO] Descomprimiendo archivos...
        powershell -Command "Expand-Archive -Path 'platform-tools.zip' -DestinationPath '.' -Force"
        del platform-tools.zip >nul 2>nul
    )
    set ADB_CMD=platform-tools\adb.exe
)

echo [OK] Herramientas de conexión listas.
echo.
echo Asegúrate de que tu PC y la pantalla estén conectados al MISMO WiFi
echo (el punto de acceso / zona WiFi de tu celular es lo más fácil y recomendado).
echo.

:: 2. Preguntar la IP
set /p IP_RADIO="👉 Escribe la dirección IP de tu pantalla (ejemplo 192.168.1.12): "
if "%IP_RADIO%"=="" (
    echo [ERROR] No escribiste ninguna IP. Intenta de nuevo.
    pause
    exit /b
)

echo %IP_RADIO%> ip_pantalla.txt
echo.
echo Intentando conectar a %IP_RADIO%:5555 ...
%ADB_CMD% kill-server >nul 2>nul
%ADB_CMD% start-server >nul 2>nul
%ADB_CMD% connect %IP_RADIO%:5555

timeout /t 2 >nul

:: 3. Verificar estado
%ADB_CMD% devices | findstr /i "%IP_RADIO%:5555" > temp_check.txt
findstr /i "device" temp_check.txt >nul
if %errorlevel% equ 0 (
    color 0A
    echo.
    echo ======================================================================
    echo   ✅ ¡CONEXIÓN EXITOSA!
    echo ======================================================================
    echo Tu pantalla está lista para recibir Car Launcher.
    echo.
    echo Datos de tu pantalla:
    for /f "delims=" %%i in ('%ADB_CMD% -s %IP_RADIO%:5555 shell getprop ro.product.model') do echo • Modelo: %%i
    for /f "delims=" %%i in ('%ADB_CMD% -s %IP_RADIO%:5555 shell getprop ro.build.version.release') do echo • Android: %%i
    for /f "delims=" %%i in ('%ADB_CMD% -s %IP_RADIO%:5555 shell wm size') do echo • Pantalla: %%i
    del temp_check.txt >nul 2>nul
    echo.
    echo Ya puedes cerrar esta ventana y ejecutar con doble clic:
    echo 👉 2_INSTALAR_PABLOCAROS.bat
    echo.
    pause
    exit /b
)

findstr /i "unauthorized" temp_check.txt >nul
if %errorlevel% equ 0 (
    color 0E
    echo.
    echo ======================================================================
    echo   ⚠️ ¡PANTALLA NO AUTORIZADA TODAVÍA!
    echo ======================================================================
    echo Mira la pantalla de tu carro en este momento:
    echo Acaba de salir un mensaje diciendo: "¿Permitir depuración USB?"
    echo 1. Marca la casilla "Permitir siempre desde esta computadora"
    echo 2. Presiona "Aceptar" o "Permitir".
    echo.
    echo Cuando lo hayas hecho en la pantalla, vuelve a ejecutar este archivo.
    del temp_check.txt >nul 2>nul
    pause
    exit /b
)

color 0C
echo.
echo ======================================================================
echo   ❌ NO SE PUDO CONECTAR CON %IP_RADIO%:5555
echo ======================================================================
echo Razones comunes y soluciones fáciles:
echo 1. ¿Tu pantalla y la laptop están en el mismo WiFi?
echo    (Verifica en Ajustes de WiFi de tu pantalla la IP exacta).
echo 2. El puerto 5555 está cerrado de fábrica:
echo    - Entra en los Ajustes del Carro > Menú de Fábrica (Factory Settings).
echo    - Te pedirá un código: prueba 3368, 8888, 1617, 126 o 000000.
echo    - Busca la opción "ADB", "Depuración USB" o "USB Debugging" y actívala.
echo 3. O descarga en la pantalla desde el navegador la app "WiFi ADB" para abrir el puerto.
echo.
echo Consulta la guía completa en INSTALLATION_AND_OPTIMIZATION_GUIDE.md
del temp_check.txt >nul 2>nul
pause
