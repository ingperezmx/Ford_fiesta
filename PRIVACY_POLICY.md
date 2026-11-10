# Política de Privacidad / Privacy Policy - XCarOS Car Launcher

**Última actualización:** 10 de noviembre de 2026

**XCarOS** (en adelante, "la Aplicación") es un lanzador automotriz diseñado para mejorar la experiencia de conducción, navegación y entretenimiento dentro del vehículo. La privacidad de nuestros usuarios es una prioridad fundamental. Esta Política de Privacidad explica cómo la Aplicación trata la información en el dispositivo del usuario.

---

## 1. Tratamiento y No Venta de Información Personal
* **No recopilamos ni vendemos información personal:** La Aplicación no almacena, recopila, transfiere ni comercializa datos personales de ningún tipo a servidores externos o terceros.
* **Procesamiento 100% Local:** Todo procesamiento de datos (sensores, velocidad, estados de vehículo y audio) se realiza exclusivamente de forma local en la memoria de su dispositivo.

---

## 2. Permisos del Dispositivo y Finalidad de Uso

La Aplicación requiere ciertos permisos del sistema operativo Android para proporcionar sus funciones esenciales en el vehículo:

### A. Ubicación (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`)
* **Finalidad:** Proporcionar cálculo de velocidad en tiempo real para el velocímetro digital, orientación de la brújula y renderizado de mapas/rutas de navegación automotriz en vivo.
* **Uso en Segundo Plano:** El acceso a la ubicación en segundo plano es necesario únicamente para mantener activa la guía de navegación GPS y el cálculo de trayectorias mientras la pantalla se encuentra dividida o si el usuario alterna temporalmente a otra vista. Ningún dato de ubicación geográfica es registrado, guardado de forma permanente ni transmitido a servidores externos.

### B. Contactos y Teléfono (`READ_CONTACTS`, `CALL_PHONE`, `READ_PHONE_STATE`, `READ_CALL_LOG`)
* **Finalidad:** Permitir al conductor visualizar su lista de contactos favoritos y realizar llamadas telefónicas directamente desde la interfaz del tablero automotriz mediante el marcador integrado o comandos de voz manos libres, garantizando una conducción segura.
* **Tratamiento:** Los contactos y registros de llamadas se leen en tiempo de ejecución para mostrarlos en el tablero; no se sincronizan ni envían a servidores remotos.

### C. Micrófono y Audio (`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`)
* **Finalidad:** Permitir el reconocimiento de comandos de voz manos libres y la interacción con el Asistente automotriz mientras se conduce. El audio solo se captura cuando el usuario activa explícitamente el botón del micrófono o comando vocal.

### D. Acceso a Notificaciones (`BIND_NOTIFICATION_LISTENER_SERVICE`)
* **Finalidad:** Permitir la visualización y control multimedia de reproductores de música (como YouTube Music, Spotify o reproductores locales) mostrando la carátula, título de la pista, artista y botones de reproducción (play/pausa/siguiente) directamente en los widgets del tablero. Ningún mensaje de texto ni notificación privada es almacenada ni compartida.

### E. Bluetooth (`BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`)
* **Finalidad:** Conectarse al adaptador de diagnóstico automotriz OBD2 (vLinker FD / ELM327) para leer los datos del vehículo (RPM, velocidad, temperatura del refrigerante y voltaje) y mostrarlos en los relojes digitales.

### F. Almacenamiento y Archivos Multimedia
* **Finalidad:** Permitir la reproducción de música local (MP3, FLAC, WAV) almacenada en memorias USB, tarjetas SD o almacenamiento interno, así como el almacenamiento de mapas en caché para funcionamiento offline.

---

## 3. Seguridad de los Datos
Implementamos estándares modernos de seguridad y no mantenemos bases de datos externas de usuarios. Todas las comunicaciones de red utilizadas (por ejemplo, para mapas de OpenStreetMap o APIs del clima) utilizan protocolos encriptados estándar.

---

## 4. Derechos del Usuario
El usuario tiene el control total sobre los permisos concedidos a la Aplicación y puede revocarlos en cualquier momento a través de los **Ajustes de Android > Aplicaciones > Car Launcher > Permisos**.

---

## 5. Contacto
Si tiene alguna duda sobre esta Política de Privacidad o el funcionamiento de la Aplicación, puede comunicarse con el desarrollador a través del repositorio oficial de GitHub del proyecto:
[https://github.com/ingperezmx/Ford_fiesta](https://github.com/ingperezmx/Ford_fiesta)
