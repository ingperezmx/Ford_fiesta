@echo off
chcp 65001 >nul
title Car Launcher — Paso 2: Instalación y Optimización 60 FPS
color 0B

echo ======================================================================
echo    🚀 Car Launcher — Instalación y Optimización Extrema
echo    Desarrollado por X
echo    Versión 1.0.12 (API 36) — paquete com.x.carlauncher
echo ======================================================================
echo.

:: 1. Verificar IP guardada
if not exist "ip_pantalla.txt" (
    color 0C
    echo [ERROR] No has conectado tu pantalla aún.
    echo Por favor ejecuta primero con doble clic: 1_CONECTAR_PANTALLA.bat
    pause
    exit /b
)

set /p IP_RADIO=<ip_pantalla.txt
set IP_RADIO=%IP_RADIO: =%

:: 2. Configurar ADB
set ADB_CMD=adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    set ADB_CMD=platform-tools\adb.exe
)

echo [1/5] Verificando conexión con %IP_RADIO%:5555 ...
%ADB_CMD% connect %IP_RADIO%:5555 >nul 2>nul

%ADB_CMD% devices | findstr /i "%IP_RADIO%:5555" | findstr /i "device" >nul
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] No hay conexión con la pantalla (%IP_RADIO%).
    echo Asegúrate de que la radio esté encendida y en el mismo WiFi.
    echo Vuelve a ejecutar 1_CONECTAR_PANTALLA.bat
    pause
    exit /b
)
echo [OK] Pantalla conectada y lista.
echo.

:: 3. Localizar o compilar el APK de LANZAMIENTO
:: Se instala la versión de lanzamiento (paquete com.x.carlauncher), firmada
:: con la clave propia del proyecto: se actualiza encima de la anterior y
:: Play Protect no la marca como insegura. La versión de depuración
:: (app-debug.apk / com.x.carlauncher.debug) ya NO se usa.
echo [2/5] Buscando archivo del Launcher (APK de lanzamiento)...
set APK_FILE=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_FILE%" (
    if exist "app-release.apk" (
        set APK_FILE=app-release.apk
    ) else (
        echo [INFO] No hay APK de lanzamiento. Puedes descargar el APK ya
        echo [INFO] compilado y firmado desde Releases en GitHub y ponerlo
        echo [INFO] junto a este archivo con el nombre app-release.apk.
        echo [INFO] O compilarlo ahora con Gradle (esto tarda unos minutos)...
        call gradlew.bat assembleRelease
    )
)

if not exist "%APK_FILE%" (
    color 0C
    echo [ERROR] No se pudo encontrar el archivo instalador APK.
    echo Descarga el APK de lanzamiento (app-release.apk) desde Releases
    echo en GitHub y ponlo en esta carpeta, o compílalo con:
    echo   gradlew.bat assembleRelease
    pause
    exit /b
)
echo [OK] Archivo APK listo: %APK_FILE%
echo.

:: 4. Aplicar optimizaciones profundas de rendimiento
echo [3/5] Aplicando optimizaciones de bajo nivel para 60 FPS...
echo   -> Activando acelerador gráfico de pantalla (GPU SkiaGL)...
%ADB_CMD% -s %IP_RADIO%:5555 shell "setprop debug.sf.hw 1"
%ADB_CMD% -s %IP_RADIO%:5555 shell "setprop debug.egl.hw 1"
%ADB_CMD% -s %IP_RADIO%:5555 shell "setprop debug.hwui.renderer skiagl"
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global force_gpu_rendering 1"

echo   -> Reduciendo animaciones a velocidad deportiva (0.5x)...
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global window_animation_scale 0.5"
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global transition_animation_scale 0.5"
%ADB_CMD% -s %IP_RADIO%:5555 shell "settings put global animator_duration_scale 0.5"

echo   -> Blindando contra cierres en segundo plano (Doze Whitelist)...
%ADB_CMD% -s %IP_RADIO%:5555 shell "dumpsys deviceidle whitelist +com.x.carlauncher" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "cmd appops set com.x.carlauncher RUN_IN_BACKGROUND allow" >nul 2>nul
echo [OK] Optimizaciones aplicadas exitosamente.
echo.

:: 5. Instalar la aplicación
echo [4/5] Transfiriendo e instalando Car Launcher en tu pantalla...
%ADB_CMD% -s %IP_RADIO%:5555 install -r -d "%APK_FILE%"
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] La instalación falló. Revisa que haya espacio libre en la pantalla.
    pause
    exit /b
)
echo [OK] Aplicación instalada correctamente.
echo.

:: 6. Conceder permisos automáticos
echo [5/5] Configurando permisos del sistema y fijando launcher...
%ADB_CMD% -s %IP_RADIO%:5555 shell "pm grant com.x.carlauncher android.permission.ACCESS_FINE_LOCATION" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "pm grant com.x.carlauncher android.permission.ACCESS_COARSE_LOCATION" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "pm grant com.x.carlauncher android.permission.READ_EXTERNAL_STORAGE" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "pm grant com.x.carlauncher android.permission.WRITE_EXTERNAL_STORAGE" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "appops set com.x.carlauncher SYSTEM_ALERT_WINDOW allow" >nul 2>nul
%ADB_CMD% -s %IP_RADIO%:5555 shell "cmd notification allow_listener com.x.carlauncher/com.x.carlauncher.media.PowerampNotificationListener" >nul 2>nul

:: Fijar como pantalla de inicio predeterminada
%ADB_CMD% -s %IP_RADIO%:5555 shell "cmd package set-home-activity com.x.carlauncher/com.x.carlauncher.MainActivity" >nul 2>nul

:: Iniciar en la pantalla
%ADB_CMD% -s %IP_RADIO%:5555 shell "am start -n com.x.carlauncher/com.x.carlauncher.MainActivity" >nul 2>nul

color 0A
echo.
echo ======================================================================
echo   🎉 ¡FELICITACIONES! Car Launcher YA ESTÁ ACTIVO EN TU PANTALLA
echo ======================================================================
echo.
echo Mira la pantalla de tu carro:
echo  1. El sistema arrancará de inmediato a 60 FPS fluidos.
echo  2. Tienes tu velocímetro GPS y brújula rotatoria hacia el frente.
echo  3. El reproductor de música leerá tus memorias USB automáticamente.
echo  4. Para cambiar el saludo "Hola, Pablo", mantén presionado el texto
echo     durante 1 segundo e ingresa tu propio nombre.
echo.
echo Si en el futuro deseas volver al launcher de fábrica, solo haz doble
echo clic en: 3_REVERTIR_TODO.bat
echo.
echo ----------------------------------------------------------------------
echo  ☕ ¿Te gustó el resultado y lo fácil que fue?
echo  Invítame un café para seguir mejorando y actualizando el proyecto:
echo ----------------------------------------------------------------------
echo.
pause
