# CarOS - Ford Fiesta Edition (Pablo)

Launcher automotriz de alto rendimiento y diseno OEM para Ford Fiesta Sedan (2014-2019) y pantallas Android para auto / smartphones Xiaomi. Desarrollado en Kotlin con Jetpack Compose moderno, arquitectura reactiva y soporte dual-bus CAN (HS-CAN y MS-CAN).

---

## Novedades y Funciones Principales

### 1. Selector de Colores Oficiales Ford Fiesta (1 solo clic)
El carro cambia de color con un solo toque directamente sobre la imagen del vehiculo o sobre el boton circular en la esquina superior derecha:
- **Azul Candy Tintcoat (E6)**: Azul metalico original Ford.
- **Rojo Rubi Metalico (D4)**: Tono rojo profundo perlado.
- **Blanco Oxford (YZ)**: Blanco solido oficial de flotilla y calle.
- **Negro Sombra / Tuxedo Black (G1)**: Negro profundo con reflejos plateados.
- **Plata Lingote Metalico (UX)**: Plata brillante deportivo.
- **Gris Magnetico (J7)**: Gris grafito metalico elegante.
- **Verde Envidia Metalico (HD)**: Verde lima perlado especial ST / Fiesta.
- **Naranja Fundido Metalico (Y8)**: Naranja electrizante perlado.

Cada cambio es instantaneo, preserva al 100% la transparencia del fondo PNG y se guarda automaticamente en memoria persistente. Ademas, muestra una pastilla flotante con el nombre oficial y el codigo de pintura Ford durante 2.5 segundos.

### 2. Vista de Carretera y Navegacion en Vivo (Debajo de "Hola Pablo")
El contenedor del carro debajo del saludo "Hola Pablo" ofrece dos vistas integradas:
- **Vista Estacionado**: Muestra el Ford Fiesta en perspectiva 3D, estado de puertas abiertas/cerradas con alertas visuales, faros encendidos/apagados y clima local en tiempo real.
- **Vista Autopista en Vivo**: Se activa automaticamente cuando el auto supera los 10 km/h (o con doble toque sobre el carro para prueba). Muestra el Ford Fiesta avanzando por una autopista nocturna, con velocimetro digital, siguiente maniobra y una flecha 3D de neon cian que rota e indica giros segun la ruta. El color oficial de carroceria seleccionado se sincroniza y aplica en tiempo real.

### 3. Modulo de Radio FM por Internet (Audio Streaming en Vivo)
Modulo de radio por streaming con enlaces probados y sonido estereo directo en las bocinas del telefono o pantalla:
- **Exa FM Monterrey (97.3 FM)**
- **LOS40 Monterrey (88.5 FM)**
- **Genesis 98.1 FM**
- **Classic 106.9 FM**
- **D99 98.9 FM**
- **Rock en Espanol**
- **KEXP 90.3 FM**

Permite reproduccion en segundo plano con control de volumen, metadata de estacion y diseno integrado en la tarjeta musical.

### 4. Preparado para OBD2 vLinker FD (Doble Bus CAN)
Compatible con escaneres OBD2 Bluetooth modernos (especialmente vLinker FD, vLinker MC+ y OBDLink EX/MX+):
- **HS-CAN (Pines 6 y 14)**: Telemetria de motor a alta velocidad (RPM, velocidad real en km/h, temperatura de refrigerante ECT, nivel de combustible y estado de bateria).
- **MS-CAN (Pines 3 y 11)**: Bus de carroceria Ford (modulo BCM) para leer en tiempo real la apertura individual de cada puerta (delantera izquierda, delantera derecha, trasera izquierda, trasera derecha, cajuela y cofre).

### 5. Acceso Rapido a Microsoft Launcher
Icono oficial de Microsoft Launcher en la barra lateral izquierda para alternar de manera instantanea entre  CarOS y el escritorio habitual del telefono sin alterar el launcher predeterminado del sistema.

---

## Guia Rapida de Instalacion

### Opcion A: Instalacion Directa con ADB (Recomendada)
Si tu telefono Xiaomi o pantalla esta conectado a la misma red WiFi o por cable USB:
```bash
# Conectar por red WiFi (ejemplo con IP del Xiaomi)
adb connect 192.168.100.16:5555

# Instalar el APK oficial
adb install -r  CarOS_FordFiesta.apk
```

### Opcion B: Instalacion Manual en el Telefono
1. Copia el archivo ` CarOS_FordFiesta.apk` a la memoria interna de tu telefono o descargalo desde tu Google Drive.
2. Abre el Administrador de Archivos en el Xiaomi y pulsa sobre el archivo `.apk`.
3. Concede el permiso de "Instalar aplicaciones de fuentes desconocidas" si el sistema lo solicita.
4. Toca en "Instalar" y luego abre la aplicacion.

### Opcion C: Compilacion desde Codigo Fuente
Si deseas realizar cambios y compilar tu propia version:
```bash
# En Windows PowerShell o CMD
.\gradlew assembleRelease
```
El archivo generado se guardara en:
`app\build\outputs\apk\release\app-release.apk`

---

## Estructura del Codigo

- `app/src/main/kotlin/com/x/carlauncher/carassets/FiestaColorManager.kt`: Gestor de los 8 colores oficiales de Ford Fiesta con ColorMatrix y persistencia.
- `app/src/main/kotlin/com/x/carlauncher/ui/components/VehicleCard.kt`: Tarjeta principal del vehiculo con transicion dinamica de estacionado a carretera y selector de color al toque.
- `app/src/main/kotlin/com/x/carlauncher/media/RadioManager.kt`: Reproductor de estaciones de radio FM por streaming HTTP/AAC.
- `app/src/main/kotlin/com/x/carlauncher/obd/ObdManager.kt`: Gestor de conexion OBD2 para adaptadores vLinker FD con soporte HS-CAN y MS-CAN.
- `app/src/main/kotlin/com/x/carlauncher/navigation/CarRouteManager.kt`: Administrador de maniobras y rutas de navegacion.
- `app/src/main/kotlin/com/x/carlauncher/ui/components/NavigationDock.kt`: Barra lateral con icono oficial de Microsoft Launcher.
