# Limitaciones conocidas y funciones pendientes

Este documento indica de forma explícita lo que **no** se pudo implementar o validar en el entorno de
desarrollo. Ningún resultado de esta lista se ha simulado.

## Validación

1. **No hubo emulador ni dispositivo.** El entorno de compilación no tiene KVM, así que la instalación y la
   ejecución en un dispositivo real **no se verificaron**. La app se ejecutó en Robolectric con gráficos nativos
   (actividad real y flujo completo en configuración de teléfono y de tableta, ver
   [screenshots/](screenshots/)). Robolectric no reproduce gestos multitáctiles reales, rendimiento de GPU ni el
   selector de archivos (SAF) del sistema. Antes de usar la app en investigación conviene probar en hardware real:
   pellizco, lápiz, abrir y guardar mediante SAF, y PDF.
2. **DM3/DM4 reales.** No se dispuso de archivos reales de Gatan. El lector se validó con archivos generados según
   la especificación publicada del formato (ambos órdenes de bytes y seis tipos de datos). Las variantes de
   etiquetas propias de cada versión de DigitalMicrograph podrían requerir ajustes. Use `testdata/real/` con
   valores de referencia (`.expected`) para validarlo con sus archivos.
3. **TIFF de proveedores.** La lectura de calibración de FEI/Thermo (34682) y ImageJ está probada. Zeiss (34118)
   se implementó a partir de la documentación, pero no se probó con un archivo real.

## Formatos

* **EMD (HDF5) no está soportado.** Haría falta una biblioteca HDF5 nativa; la app muestra un mensaje claro.
* **MRC:** solo modos 0, 1, 2, 6 y 12; no admite modos complejos ni de 4 bits.
* **Tamaño máximo:** archivos de hasta 2 GB (mapeo en memoria). La imagen decodificada se guarda completa como
  float32 (4 bytes por píxel), así que una imagen de 8k×8k ocupa 256 MB. La visualización usa teselas, pero el
  **procesamiento no se hace por bloques**.
* Los enteros de 32 bits mayores que 2²⁴ pierden precisión al convertirse a float32.
* Las pilas 3D de DM se leen solo en su primer plano. Los espectros 1D se ignoran.
* Las imágenes RGB se convierten a luminancia.

## Tecnología: desviaciones respecto a la propuesta

* **No se usa OpenCV, NDK/C++ ni Room.** Los algoritmos están escritos en Kotlin puro dentro de `core`, para que
  sean verificables en JVM y el APK sea pequeño (1,5 MB). Los proyectos se guardan en JSON en lugar de Room, lo
  que facilita la copia de seguridad y la portabilidad.
* Los filtros mediana y bilateral son O(r²) por píxel. Con imágenes muy grandes (más de 16 Mpx) y radios grandes
  pueden tardar varios segundos.

## Funcionalidad

* **Deshacer y rehacer** solo cubre el procesamiento de imagen. Las ediciones de partículas y de máscaras se
  revierten manualmente (excluir/incluir, historial IFFT).
* **Lotes:** se aplica la misma lista de operaciones a todas las imágenes abiertas y cada una se exporta por
  separado. No existe una exportación por carpeta en un solo paso.
* **Calibración de dos puntos:** fija un píxel isotrópico (X = Y). Para calibraciones anisótropas, introduzca X e
  Y manualmente. La línea se mide sobre la imagen procesada; si el procesamiento incluye una rotación libre, el
  tamaño de píxel sigue siendo el mismo.
* Las **intensidades de las partículas** se miden sobre la imagen procesada, no sobre la original.
* El **Feret máximo** tiene una incertidumbre de discretización de ±1 px.
* **Cristalografía:** solo se aplican las extinciones por centrado, diamante y hcp. No se aplican las de planos
  de deslizamiento ni ejes helicoidales de cada grupo espacial (por ejemplo, anatasa o rutilo), así que pueden
  aparecer reflexiones prohibidas entre los candidatos. Todas las asignaciones son tentativas.
* **Reportes:** solo PDF (A4). Las tablas se truncan a 9 columnas y 60 filas; los datos completos están en
  CSV/XLSX. No hay exportación a DOCX.
* **Idioma:** los nombres de las variables de partículas y algunas cabeceras de tablas exportadas están en inglés.
* **Lápiz digital:** se trata como un dedo; no usa presión ni el botón lateral.
* El APK de release se firma con la clave de depuración salvo que se configure un keystore propio.

---
© 2026 F. Mares-Briones. Todos los derechos reservados.
