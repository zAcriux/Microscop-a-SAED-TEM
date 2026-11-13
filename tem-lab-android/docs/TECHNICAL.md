# TEM Lab – documentación técnica

## Arquitectura

```
app (Android, Compose, MVVM)                core (Kotlin/JVM puro, probado en JVM)
├─ MainActivity → TemLabApp (Scaffold)       ├─ image/    ImageData (float32), Calibration X/Y, LengthUnit
├─ data/AppViewModel  (estado, proyectos,    ├─ io/       DmReader (DM3/DM4), TiffReader/Writer, MrcReader
│     recientes, presets, FFT/IFFT, lotes)   ├─ fft/      FFT (radix-2 + Bluestein), FftAnalysis, FourierFilter
├─ data/Doc  (una micrografía abierta)       ├─ process/  filtros, CLAHE, fondo, geometría, Pipeline, History
├─ render/   Render, Figures, Report (PDF)   ├─ segmentation/ Otsu, adaptativa, contornos, morfología, EDT, watershed
├─ ui/       ZoomableImage (gestos, lupa,    ├─ particles/ ParticleSet (edición), ParticleMeasurer
│            teselas de detalle), overlays   ├─ measure/  mediciones manuales, perfiles, periodicidad
└─ screens/  Medir, Procesar, FFT, IFFT,     ├─ stats/    descriptiva, histogramas, ajuste lognormal + KS
             Partículas, Estadística,        ├─ crystal/  tensor métrico, d(hkl), ángulos, extinciones, comparación
             Anotar, Comparar, Reporte       ├─ export/   CSV, XLSX (OOXML), SVG
                                             └─ project/  modelo de proyecto JSON (kotlinx.serialization)
```

* **No destructivo.** `Doc.original` no se modifica nunca. El procesamiento es una lista serializable de
  `ProcessOp` (`Pipeline`) que siempre se aplica sobre el original, con historial para deshacer y rehacer. La
  visualización (mínimo/máximo, brillo, contraste, gamma, inversión) es una tabla de consulta (LUT) que no toca
  los datos.
* **Precisión.** Todos los datos se convierten a `float32` sin reducirlos a 8 bits. Los análisis internos (FFT,
  momentos, estadística) usan `double`.
* **Memoria.** Los archivos científicos se copian a la caché y se mapean en memoria (`MappedByteBuffer`); solo se
  reserva el arreglo float decodificado. La pantalla muestra una vista previa de hasta 2048 px. Al hacer zoom, el
  visor genera de forma asíncrona una tesela a resolución completa de la zona visible, de modo que nunca se crea
  un bitmap del tamaño completo de la imagen.
* **Hilos.** Los cálculos pesados (pipeline, FFT, IFFT, segmentación, carga de proyectos) se ejecutan en
  `Dispatchers.Default` o `IO` mediante corrutinas del `viewModelScope`.

## Lectura de formatos

| Formato | Implementación | Calibración |
|---|---|---|
| DM3 / DM4 | Analizador completo del árbol de etiquetas (grupos, datos simples, cadenas, arreglos, estructuras, arreglos de estructuras; enteros de 4 u 8 bytes; ambos órdenes de bytes). El arreglo de la imagen se lee directamente desde su posición en el archivo. Tipos 1, 2, 3, 6, 7, 9, 10, 11, 12, 13, 14 y 23. Por la convención de DM, se omite `ImageList[0]` (miniatura). | `ImageData/Calibrations/Dimension[i]/Scale,Units` en X e Y por separado. Unidades pm/Å/nm/µm/mm/m y recíprocas (`1/nm`). Los metadatos de `ImageTags` (voltaje, aumento, microscopio, etc.) se aplanan para mostrarlos. |
| TIFF / BigTIFF | Muestras de 1/8/16/32/64 bits (enteras con o sin signo, flotantes de 16/32/64 bits), RGB (convertido a luminancia), multipágina, tiras y teselas, compresión sin compresión/LZW/PackBits/Deflate y predictor horizontal. | ImageJ (`unit=` + X/YResolution), FEI/Thermo (etiqueta 34682 `PixelWidth`), Zeiss (34118) y ResolutionUnit en cm. |
| MRC | Modos 0, 1, 2, 6 y 12, encabezado extendido, pilas (secciones como páginas). | `cella/mx` (Å → nm). |
| PNG/JPG/BMP | `BitmapFactory` → luminancia, marcada como 8 bits. | No tienen calibración: se calibran manualmente. |

## FFT y distancias interplanares

* **Transformada:** `FFT.transform` usa radix-2 iterativo para potencias de 2 y el algoritmo chirp-z de Bluestein
  (una DFT exacta) para cualquier otro tamaño. Se comprueba contra una DFT directa O(n²).
* **ROI cuadrada en píxeles originales** (`SquareRoi`), independiente del zoom de pantalla. Opciones: restar la
  media, ventana Hann y zero-padding hasta `paddedSize`. Después de la transformada se aplica `fftshift`, de modo
  que el origen queda en (n/2, n/2).
* **Calibración recíproca:** Δk_x = 1/(n·p_x) y Δk_y = 1/(n·p_y), donde n es el tamaño de la transformada
  (incluido el relleno) y p_x, p_y son los tamaños de píxel independientes. Un píxel (u, v) corresponde a
  k = ((u−n/2)Δk_x, (v−n/2)Δk_y), con |g| = √(k_x²+k_y²) y **d = 1/|g|**.
* **Selección táctil:** se busca el máximo de amplitud dentro de un radio configurable, ignorando el disco DC.
  Luego se refina con un ajuste gaussiano separable (parábola sobre el logaritmo) en el vecindario 3×3, con
  precisión subpíxel. Si el ajuste está mal condicionado, se conserva la posición entera y se indica.
* **Par simétrico:** ĝ = (g₁ − g₂)/2, que cancela cualquier error en la posición del origen. Se informan la
  diferencia relativa entre ambos d y la distancia del punto medio al origen.
* **Ángulos:** se calculan con las componentes calibradas, por lo que son correctos también con píxeles
  anisótropos.
* **Detección semiautomática:** máximos locales por encima de μ + kσ del logaritmo de la amplitud, con
  supresión de no máximos.
* **Perfil radial:** promedio azimutal en intervalos de min(Δk_x, Δk_y).

## Filtrado de Fourier e IFFT

`FourierFilter.apply` transforma la ROI sin ventana ni relleno, para que la inversa corresponda píxel a píxel a la
región. Formas de máscara: círculo, elipse (rotada), rectángulo, gaussiana, pasa bajas, pasa altas y pasa banda.
Todas admiten un borde gaussiano suave.

Con la opción *simétrica*, cada forma se refleja en −g y la máscara se simetriza en la malla discreta (índice
compañero (2c − i) mod n, válido también para tamaños impares). Así el espectro filtrado sigue siendo hermítico. El
cociente max|Im|/max|Re| de la IFFT se informa como comprobación: con simetría está en torno a 1e-17, y sin ella
alrededor de 0,3. La reconstrucción conserva la calibración y lleva en sus metadatos la descripción completa de la
máscara y una advertencia.

## Partículas

1. **Máscara binaria:** suavizado previo gaussiano y luego umbral manual, Otsu (256 intervalos), adaptativo
   (media local ± k·σ global), contornos (Sobel + Otsu + cierre + relleno) o rango de intensidad. Siguen
   cierre/apertura y relleno de huecos. Los métodos de contornos y adaptativo refinan cada borde al nivel local del
   50 % entre el núcleo y el anillo de fondo.
2. **Separación:** etiquetado con conectividad 8 o watershed sobre la transformada de distancia euclídea exacta
   (Felzenszwalb y Huttenlocher), con fusión por prominencia.
3. **Mediciones (`ParticleMeasurer`):**
   * área: número de píxeles × p_x·p_y;
   * perímetro: estimador de Vossepoel–Smeulders sobre el código de cadena de Freeman, más π·p por el contorno
     trazado por los centros de píxel;
   * ECD = 2√(A/π);
   * Feret máximo y mínimo: envolvente convexa de los centros de píxel, más media extensión de píxel (Feret
     máximo) o una extensión completa (calibre mínimo);
   * ejes mayor y menor: segundos momentos centrales más la varianza intrapíxel (p²/12); el eje es 4√λ;
   * relación de aspecto, circularidad 4πA/P² (limitada a 1), centroide e intensidad media y desviación estándar.
4. **Edición (`ParticleSet`):** eliminar, excluir, añadir un polígono a mano alzada (rasterizado por líneas de
   barrido, reasignando los píxeles solapados), redibujar el contorno, fusionar y dividir con una línea de corte.
   Cada operación vuelve a medir solo las partículas afectadas, vuelve a aplicar los filtros e incrementa
   `revision`, de modo que la tabla y el histograma se derivan de nuevo inmediatamente.

## Estadística

Incluye: N, media, mediana, desviación estándar muestral, error estándar, coeficiente de variación, mínimo y
máximo, y percentiles 10/25/75/90 (Hyndman–Fan tipo 7). Los intervalos del histograma se definen por número
(automático con Freedman–Diaconis) o por ancho. El ajuste lognormal es de máxima verosimilitud, con prueba de
Kolmogórov–Smirnov (corrección de Stephens); la curva solo se dibuja si el ajuste no se rechaza o si el usuario la
activa.

Las mediciones manuales de diámetro o radio pueden entrar en la distribución. Si su punto medio cae sobre una
partícula detectada, se marcan como duplicado y se excluyen.

## Cristalografía

`Lattice` calcula el tensor métrico recíproco G* = G⁻¹ para cualquier sistema (de cúbico a triclínico), con
d(hkl) = 1/√(hᵀG*h) y ángulos entre planos. Extinciones: centrado P/I/F/C/A/B/R(obverso), diamante y hcp.
Incluye celdas de referencia como Au, Ag, Pt, Pd, Cu, Ni, Al, α-Fe, Si, CeO₂, Fe₃O₄, MgO, anatasa, rutilo, SnO₂,
ZnO, grafito, hematita y Ti.

`CrystalMatcher` ordena las familias candidatas por d, y los pares candidatos por d₁, d₂ y el ángulo, junto con el
eje de zona. La interfaz siempre las presenta como **tentativas**.

## Proyectos

Un proyecto se guarda como `*.temlab.json` en el almacenamiento de la app y contiene:

* la referencia persistente (URI de SAF) al archivo original y el número de página;
* la calibración modificada y su registro de cambios;
* el pipeline y la configuración de visualización;
* las FFT (ROI, opciones, reflexiones, pares y ángulos);
* las IFFT (ROI y máscara);
* las partículas, codificadas por longitud de series, con parámetros, filtro, origen y exclusión;
* las mediciones, anotaciones, barra de escala, notas, configuraciones reutilizables y plantillas de reporte.

Al reabrir un proyecto se recalculan las FFT, las IFFT y las mediciones de partículas a partir de los datos
originales. La copia de seguridad o exportación es un `.zip` con el JSON y, opcionalmente, copias de los archivos
originales, de modo que se puede restaurar en otro dispositivo.

## Pruebas

* `core`: 39 pruebas, descritas en [VALIDATION.md](VALIDATION.md).
* `app`: `PhoneUiSmokeTest` y `TabletUiSmokeTest` (Robolectric con gráficos nativos) ejecutan la actividad real.
  Cargan micrografías sintéticas, recorren todos los módulos, comprueban d en la FFT, el número de partículas y el
  guardado del proyecto, y escriben las capturas de `docs/screenshots/`. El framework de Android se descarga con
  Gradle (`robolectricRuntime`), no en tiempo de ejecución.
