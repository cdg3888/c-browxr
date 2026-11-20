# C-Browxr 🌐⚡

![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)
![Architecture: Modular](https://img.shields.io/badge/Architecture-Hot--Updatable%20Modules-orange.svg)
![Telemetry: Zero](https://img.shields.io/badge/Telemetry-Zero%20%7C%20100%25%20FOSS-red.svg)

**C-Browxr** (`com.cdg.app.cbrowxr`) es un navegador web avanzado para Android, de código abierto, 100% libre de telemetría y completamente independiente de los servicios propietarios de Google (GMS).

## Estructura Modular
- `browser/`: Motor Mozilla GeckoView sin telemetría, bloqueo estricto de popups/redirecciones y perfiles de User-Agent.
- `sniffer/`: Interceptor de streams (.m3u8, .mpd, .mp4, .vtt), limpiador de URLs, exportador .txt/.m3u y generador cURL.
- `downloader/`: Acelerador multihilo estilo IDM (8 hilos HTTP Range) + motor `yt-dlp` & `FFmpeg` desacoplados.
- `caster/`: Servidor Proxy HTTP local (`NanoHTTPD`) con inyección de cabeceras + envío DLNA/UPnP libre de Google.
- `updater/`: Actualización en caliente en `filesDir` para `yt-dlp`, extensiones `.xpi` y reglas JSON con verificación SHA-256.
- `ui/`: Interfaz Jetpack Compose (Material 3) con botón flotante contextual (FAB) y panel de acciones multimedia.

## Compilación
```bash
./gradlew assembleDebug
```
