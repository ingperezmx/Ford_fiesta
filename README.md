# Car Launcher - Ford Fiesta Edition

Launcher automotriz de alto rendimiento y diseño OEM para Ford Fiesta Sedan (2014-2019) y pantallas Android para auto / smartphones Xiaomi. Desarrollado en Kotlin con Jetpack Compose moderno, arquitectura reactiva y soporte dual-bus CAN (HS-CAN y MS-CAN).

**Versión actual: 1.0.15 (versionCode 16) — compileSdk / targetSdk 36 (Android 16), minSdk 26, paquete `com.x.carlauncher`.**

> **Solo datos reales.** La app usa únicamente GPS real (FusedLocationProvider), telemetría OBD-II real y rutas reales de OSRM. No hay ubicaciones simuladas, ni RPM simulados, ni rutas inventadas, ni coordenadas de respaldo. Tampoco trae ningún destino personal guardado: los destinos se buscan, no vienen fijos.

> **Tablero adaptable.** El tablero se diseña a 1024×600 dp y se escala en proporción a la pantalla real, así que se ve igual en pantallas grandes o chicas, sin nada movido.

---

## Novedades y Funciones Principales

### 1. Selector de Colores Oficiales Ford Fiesta (1 solo clic)
El carro cambia de color con un solo toque directamente sobre la imagen del vehículo o sobre el botón circular en la esquina superior derecha:
- **Azul Candy Tintcoat (E6)**: Azul metálico original Ford.
- **Rojo Rubí Metálico (D4)**: Tono rojo profundo perlado.
- **Blanco Oxford (YZ)**: Blanco sólido oficial de flotilla y calle.
- **Negro Sombra / Tuxedo Black (G1)**: Negro profundo con reflejos plateados.
- **Plata Lingote Metálico (UX)**: Plata brillante deportivo.
- **Gris Magnético (J7)**: Gris grafito metálico elegante.
- **Verde Envidia Metálico (HD)**: Verde lima perlado especial ST / Fiesta.
- **Naranja Fundido Metálico (Y8)**: Naranja electrizante perlado.

Cada cambio es instantáneo, se guarda automáticamente en memoria persistente y muestra una pastilla flotante con el nombre oficial y el código de pintura Ford durante 2.5 segundos. El recolor solo afecta la carrocería: las ventanas, las llantas y los faros quedan intactos (la matriz de color gira el tono sin tocar los píxeles grises). Al abrir una puerta, el cofre o la cajuela, se marca la parte con un resaltado blanco sobre la imagen del carro.

### 2. Vista de Carretera y Navegación en Vivo (Debajo de "Hola, Pablo")
El contenedor del carro debajo del saludo "Hola, Pablo" ofrece dos vistas integradas:
- **Vista Estacionado**: Muestra el Ford Fiesta en perspectiva 3D, estado de puertas abiertas/cerradas con alertas visuales, faros encendidos/apagados y clima local en tiempo real.
- **Vista Autopista en Vivo**: Se activa automáticamente cuando el auto supera los 10 km/h (o con doble toque sobre el carro para prueba). Muestra el Ford Fiesta avanzando por una autopista nocturna, con velocímetro digital, siguiente maniobra y una flecha 3D de neón cian que rota e indica giros según la ruta. El color oficial de carrocería seleccionado se sincroniza y aplica en tiempo real.

### 3. Búsqueda de lugares y navegación guiada (OSRM)
Ya no hay una lista fija de puntos de interés. Desde la barra superior del mapa ("Busca puntos de interés") se abre un buscador:
- Busca cualquier lugar en México y en todo el continente americano (Nominatim / OpenStreetMap, sin clave ni cuenta), con los últimos lugares buscados guardados como recientes.
- Al elegir un resultado, el mapa traza la ruta con OSRM y la navegación guiada muestra maniobras e instrucciones en la tarjeta del carro y en el botón "Navegar en Pantalla".
- La navegación necesita internet (para OSRM) y señal GPS real; sin GPS muestra el estado de búsqueda, sin inventar una posición.

### 4. Controles de YouTube Music (sin cuenta ni API)
El panel de música controla la reproducción que ya suena en el teléfono o la pantalla, usando la sesión de medios del sistema (MediaSession):
- Canción actual (portada, título y artista), reproducir/pausar, siguiente/anterior, -10 s / +10 s, barra de progreso con búsqueda, volumen y botón "Abrir YouTube Music".
- La música se elige dentro de la app de YouTube Music; Car Launcher solo la controla. No pide cuenta de Google ni clave de API.
- **Para que funcione hay que conceder "Acceso a notificaciones" a Car Launcher una sola vez** (la app lo pide al arrancar y muestra los botones "1. Permitir" y "2. Activar"). En Android 13+ además hay que tocar primero "Permitir configuración restringida" en la información de la app, porque es una app instalada por fuera de Play. Si el panel dice "Nada sonando" aunque suene música, el botón "Reconectar" reinicia el servicio de notificaciones; si sigue sin arrancar, reinicia la pantalla del auto.
- También funcionan los botones del volante (SWC) y del widget FYT: siguiente, anterior y reproducir/pausar llegan a la sesión externa.
- Aleatorio y repetir solo funcionan con la música local (la app de YouTube Music no expone esos controles al sistema).

### 5. Módulo de Radio FM por Internet (Audio Streaming en Vivo)
Módulo de radio por streaming con enlaces probados y sonido estéreo directo en las bocinas del teléfono o pantalla:
- **Exa FM Monterrey (90.1 FM)**
- **LOS40 Monterrey (88.5 FM)**
- **Génesis 98.1 FM**
- **Classic 106.9 FM**
- **D99 98.9 FM**
- **Rock en Español**
- **KEXP 90.3 FM**

Permite reproducción en segundo plano con control de volumen, metadata de estación y diseño integrado en la tarjeta musical.

### 6. Preparado para OBD2 vLinker FD (Doble Bus CAN)
Compatible con escáneres OBD2 Bluetooth modernos (especialmente vLinker FD, vLinker MC+ y OBDLink EX/MX+):
- **HS-CAN (Pines 6 y 14)**: Telemetría de motor a alta velocidad (RPM, velocidad real en km/h, temperatura de refrigerante ECT, nivel de combustible y estado de batería).
- **MS-CAN (Pines 3 y 11)**: Bus de carrocería Ford (módulo BCM) para leer en tiempo real la apertura individual de cada puerta (delantera izquierda, delantera derecha, trasera izquierda, trasera derecha, cajuela y cofre).

### 7. Acceso Rápido a Microsoft Launcher
Icono oficial de Microsoft Launcher en la barra lateral izquierda para alternar de manera instantánea entre Car Launcher y el escritorio habitual del teléfono sin alterar el launcher predeterminado del sistema.

---

## Guía Rápida de Instalación

La guía completa y actualizada está en [INSTALLATION_AND_OPTIMIZATION_GUIDE.md](INSTALLATION_AND_OPTIMIZATION_GUIDE.md).

### Opción A: Instalación automática con los scripts .bat (Recomendada, Windows)
1. Ejecuta con doble clic `1_CONECTAR_PANTALLA.bat` y escribe la IP de la pantalla (ambos en el mismo WiFi).
2. Ejecuta con doble clic `2_INSTALAR_PABLOCAROS.bat`: instala el APK de lanzamiento, aplica las optimizaciones de 60 FPS, concede los permisos y fija Car Launcher como pantalla de inicio.
3. Si quieres volver al launcher de fábrica, ejecuta `3_REVERTIR_TODO.bat`.

### Opción B: Instalación directa con ADB
Si tu teléfono Xiaomi o pantalla está conectado a la misma red WiFi o por cable USB:
```bash
# Conectar por red WiFi (ejemplo con IP del Xiaomi)
adb connect 192.168.100.16:5555

# Instalar el APK de lanzamiento (desde Releases en GitHub)
adb install -r app-release.apk
```

### Opción C: Instalación manual en el teléfono
1. Descarga el archivo `app-release.apk` desde Releases en GitHub y cópialo a la memoria interna de tu teléfono o pantalla.
2. Abre el Administrador de Archivos y pulsa sobre el archivo `.apk`.
3. Concede el permiso de "Instalar aplicaciones de fuentes desconocidas" si el sistema lo solicita.
4. Toca en "Instalar" y luego abre la aplicación.
5. Concede el acceso a notificaciones cuando la app lo pida (necesario para los controles de YouTube Music). En Android 13+, primero activa "Permitir configuración restringida" en Ajustes → Apps → Car Launcher → ⋮.

### Opción D: Compilación desde código fuente
```bash
# En Windows PowerShell o CMD
.\gradlew assembleRelease
```
El archivo generado se guardará en:
`app\build\outputs\apk\release\app-release.apk`

> La versión de lanzamiento se firma con la clave propia del proyecto (guardada como secreto en GitHub Actions), así que cada actualización se instala encima de la anterior sin borrar nada. La versión de depuración (`app-debug.apk`, paquete `com.x.carlauncher.debug`) ya no se usa: Play Protect la marca como insegura y no se puede actualizar sobre la versión de lanzamiento.

---

## Estructura del Código

- `app/src/main/kotlin/com/x/carlauncher/carassets/FiestaColorManager.kt`: Gestor de los 8 colores oficiales de Ford Fiesta con ColorMatrix y persistencia.
- `app/src/main/kotlin/com/x/carlauncher/ui/components/VehicleCard.kt`: Tarjeta principal del vehículo con transición dinámica de estacionado a carretera y selector de color al toque.
- `app/src/main/kotlin/com/x/carlauncher/ui/components/YouTubeMusicPanel.kt`: Panel de controles de YouTube Music (MediaSession) con diagnóstico de acceso/servicio/sesión.
- `app/src/main/kotlin/com/x/carlauncher/media/PowerampMediaController.kt`: Escucha de notificaciones y sesión de medios (YouTube Music, local y Bluetooth), servicio FYT y receptor del volante.
- `app/src/main/kotlin/com/x/carlauncher/media/RadioManager.kt`: Reproductor de estaciones de radio FM por streaming HTTP/AAC.
- `app/src/main/kotlin/com/x/carlauncher/media/XLocalPlayer.kt`: Reproductor local (USB) y ruteo de los botones del volante.
- `app/src/main/kotlin/com/x/carlauncher/obd/ObdManager.kt`: Gestor de conexión OBD2 para adaptadores vLinker FD con soporte HS-CAN y MS-CAN.
- `app/src/main/kotlin/com/x/carlauncher/navigation/PlaceSearch.kt`: Búsqueda de lugares con Nominatim/OpenStreetMap y recientes guardados.
- `app/src/main/kotlin/com/x/carlauncher/navigation/CarRouteManager.kt`: Administrador de maniobras y rutas de navegación (OSRM, solo GPS real).
- `app/src/main/kotlin/com/x/carlauncher/ui/screens/DetailedFullMapScreen.kt`: Mapa en vivo con GPS real, buscador de destinos y navegación guiada.
- `app/src/main/kotlin/com/x/carlauncher/ui/components/NavigationDock.kt`: Barra lateral con icono oficial de Microsoft Launcher.

---

## Privacidad

La app no pide cuenta, no tiene anuncios ni analítica y no envía datos a ningún servidor propio: la ubicación GPS solo se usa en el dispositivo y para pedir la ruta a OSRM; la búsqueda de lugares va a Nominatim/OpenStreetMap; el acceso a notificaciones solo lee la sesión de medios activa para mostrar y controlar la música. La política completa está en [docs/privacy-policy.html](docs/privacy-policy.html).
