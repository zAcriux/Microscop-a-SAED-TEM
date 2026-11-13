# TEM Lab – análisis de micrografías electrónicas para Android

TEM Lab es una aplicación Android nativa (Kotlin + Jetpack Compose + Material 3) para visualizar, procesar,
medir y analizar micrografías TEM, HRTEM, SEM, STEM y HAADF-STEM. Funciona completamente sin conexión: todo el
procesamiento se hace en el dispositivo.

* **APK instalable:** [`dist/TEMLab-0.1.0-release.apk`](dist/) (Android 8.0+, API 26). Está firmado con la clave
  de depuración, así que se instala desde el archivo. Para publicarlo en una tienda hay que firmarlo con una clave
  propia (ver más abajo).
* **Manual de usuario:** [docs/MANUAL_USUARIO.md](docs/MANUAL_USUARIO.md)
* **Documentación técnica:** [docs/TECHNICAL.md](docs/TECHNICAL.md)
* **Validación científica:** [docs/VALIDATION.md](docs/VALIDATION.md)
* **Ejemplos trabajados (FFT, partículas, IFFT):** [docs/examples/README.md](docs/examples/README.md)
* **Capturas de pantalla (teléfono y tableta):** [docs/screenshots/](docs/screenshots/)
* **Limitaciones y trabajo pendiente:** [docs/LIMITATIONS.md](docs/LIMITATIONS.md)

## Módulos

| Módulo | Contenido |
|---|---|
| `core/` | Biblioteca científica en Kotlin/JVM pura, sin dependencias de Android: lectores DM3/DM4/TIFF/MRC, FFT, análisis de reflexiones, filtrado de Fourier/IFFT, filtros, segmentación, partículas, estadística, cristalografía, exportación (CSV/XLSX/SVG/TIFF) y modelo de proyecto. Tiene 39 pruebas unitarias. |
| `app/` | Interfaz Compose: inicio y proyectos, Medir, Procesar, FFT, IFFT, Partículas, Estadística, Anotar, Comparar y Reporte (PDF). Incluye pruebas de humo con Robolectric en configuración de teléfono y de tableta. |

## Compilar

Requisitos: JDK 17 o superior y Android SDK (plataforma 35, build-tools 35).

```bash
cd tem-lab-android
echo "sdk.dir=/ruta/al/android-sdk" > local.properties
./gradlew :core:test                 # validación científica (39 pruebas)
./gradlew :app:testDebugUnitTest     # flujo completo de la UI en Robolectric + capturas
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease       # APK minimizado con R8
```

**Firma de distribución.** Define `TEMLAB_KEYSTORE`, `TEMLAB_KEYSTORE_PASSWORD`, `TEMLAB_KEY_ALIAS` y
`TEMLAB_KEY_PASSWORD` antes de ejecutar `assembleRelease`. Sin estas variables, la versión de release se firma con
la clave de depuración.

El proyecto también se abre directamente en Android Studio (abrir la carpeta `tem-lab-android`).
