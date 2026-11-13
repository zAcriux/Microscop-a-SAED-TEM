# Validación científica

Ejecución: `./gradlew :core:test` (39 pruebas, todas superadas) y `./gradlew :app:testDebugUnitTest` (2 pruebas de
flujo completo de la UI, también superadas). Todas las entradas son sintéticas y tienen contenido exacto conocido.

## FFT (`FftValidationTest`, 10 pruebas)

| Comprobación | Resultado / tolerancia |
|---|---|
| FFT frente a DFT directa (n = 2, 3, 8, 12, 15, 64, 100, 127), ida y vuelta | error < 1e-9·n |
| Frecuencia entera: 16 ciclos en 256 px | pico exacto en u = 144, d = 16 px |
| d calibrado, Au(111) 0,2355 nm y Au(200) 0,2039 nm, píxel 0,0189 nm, con ruido, para relleno {ninguno, 512, 1024} × Hann {sí, no} | ≤ 1 % sin relleno; ≤ 0,2 % con relleno + Hann |
| Ángulo de 54,74° entre las dos reflexiones | ≤ 0,3° con relleno |
| Relleno con ceros: Δk = 1/(n·p) y la misma reflexión a 4× más píxeles de distancia con relleno 4× | exacto / d ≤ 1 % |
| Píxeles anisótropos (0,02 × 0,03 nm): d 0,31 y 0,27 nm y ángulo de 75° | ≤ 0,3 % / ≤ 0,4° |
| Par simétrico con ruido | d ≤ 0,3 %; desplazamiento del origen < 0,2 px |
| Toque desplazado (3, −2) px del máximo | se ajusta a ±0,25 px con refinamiento subpíxel |
| Detección automática de pares de Friedel | encuentra ±g₁ y ±g₂ con d correcto |
| Imagen sin calibrar | d en px (8 px), dirección de 90° |
| Perfil radial de una red hexagonal | máximo en \|g\| = 5 nm⁻¹ |

## IFFT (`FourierFilterValidationTest`, 6 pruebas)

| Comprobación | Resultado |
|---|---|
| Máscara alrededor de una sola reflexión en una imagen con dos redes + ruido | correlación > 0,97 con la componente pura y < 0,05 con la otra; media conservada; calibración conservada; d en la reconstrucción con error ≤ 0,3 % |
| Máscara asimétrica frente a simétrica | residuo imaginario > 0,3 sin simetría y < 1e-9 con ella; máscara centrosimétrica en cada píxel |
| ROI de tamaño impar (201 px) con elipse | residuo < 1e-9 |
| Pasa bajas + (pasa bajas invertido) = original; pasa banda selecciona la red correcta | error < 1e-3 / correlación > 0,9 |
| Máscara gaussiana | pesos exactos e^(−r²/2σ²), incluido el compañero de Friedel |
| Periodicidad de franjas de 0,25 nm medida con un perfil | ≤ 1 % (por Fourier del perfil y por separación de máximos) |

## Partículas (`ParticleValidationTest`, 7 pruebas)

Las partículas sintéticas tienen bordes suavizados (sobremuestreo 4×4) y ruido gaussiano.

| Comprobación | Tolerancia |
|---|---|
| 6 círculos con r = 8 a 25 px: número, área, ECD, Feret máximo y mínimo, perímetro, circularidad, aspecto, centroide | número exacto; área ≤ 4 %; ECD ≤ 2 %; Feret máximo ± 0,8 px; Feret mínimo ≤ 5 %; perímetro ≤ 4 %; circularidad > 0,93; centroide ± 0,3 px |
| Elipses (40×20 a 30° y 30×10 a −60°) | ejes ≤ 3–5 %; aspecto ≤ 6 %; orientación ± 2° |
| Watershed en dos discos en contacto | 1 objeto sin watershed y 2 con watershed |
| Exclusión de partículas en el borde | 6 con exclusión y 7 sin ella |
| Edición: eliminar, excluir/incluir, añadir, fusionar, dividir, redibujar contorno, filtro | el total del histograma se actualiza tras cada operación; las áreas fusionadas suman exactamente |
| Segmentación adaptativa y por contornos | número exacto; ECD ≤ 5 % |
| 64 discos con diámetros aleatorios | N = 64; media ≤ 1,5 %; desviación estándar ≤ 5 % |

Estimador de perímetro sobre discos de r = 5 a 40 px: error entre −0,5 % y +2,4 %. ECD: entre −0,3 % y +1,5 %.

## Formatos (`FormatValidationTest`, 7 pruebas)

| Comprobación | Resultado |
|---|---|
| DM3 y DM4 × orden de bytes little/big × tipos uint16, int16, float32, int32, uint8, float64, generados según la especificación de Gatan (con miniatura `ImageList[0]`) | dimensiones, tipo, **todas** las intensidades, calibración (0,0153 nm) y metadatos (voltaje, nombre del microscopio, nombre de la imagen) exactos |
| Unidades µm y recíprocas 1/nm en DM | se reconocen |
| TIFF multipágina (6 páginas) × II/MM: 8/16/32 bits enteros con y sin signo y float32, sin compresión, PackBits, Deflate + predictor, LZW (codificador independiente), LZW + predictor, varias tiras | intensidades exactas en todas las páginas |
| TIFF RGB | luminancia 0,299R + 0,587G + 0,114B |
| Calibración ImageJ, FEI (34682) y sin calibración | correcta |
| Escritor TIFF de 32 bits con calibración (exportación de reconstrucciones) | ida y vuelta exacta |
| **Archivos reales** en `testdata/real/` con valores de referencia `.expected` | ver [testdata/real/README.md](../testdata/real/README.md); se omite si no hay archivos |

## Cristalografía, estadística, exportación y proyecto (`CrystalStatsExportTest`, 8 pruebas)

| Comprobación | Resultado |
|---|---|
| Au | d(111) = a/√3 y d(200) = 2,0391 Å; ángulos (100)/(111) = 54,7356° y (111)/(1−11) = 70,5288° |
| ZnO frente a ICDD 36-1451 | d(100), d(002) y d(101) con error ≤ 2e-4 Å |
| Monoclínico | d(001) = c·sin β |
| Extinciones | F, I, diamante y hcp |
| Percentiles y desviación estándar | coinciden con NumPy/Excel |
| Ajuste lognormal sobre 2000 muestras | μ y σ recuperados; KS rechaza datos bimodales |
| Exportación | CSV con BOM y unidades; estructura XLSX válida; SVG |
| Proyecto | ida y vuelta JSON exacta; codificación por longitud de series |
| Pipeline | no destructivo; calibración tras rotar 90°; plano eliminado; LUT de visualización |

## Interfaz (Robolectric, teléfono 411×891 dp y tableta 1280×800 dp)

Flujo completo en la actividad real:

1. abrir dos micrografías;
2. medir franjas;
3. calcular la FFT y medir tres reflexiones (d comprobado con error ≤ 0,3 %);
4. reconstrucción IFFT;
5. segmentación de partículas (40 a 70 detectadas sobre 60 sembradas, algunas superpuestas);
6. estadística, procesamiento, anotación, comparación y reporte;
7. guardar el proyecto y volver a leer el JSON.

Las capturas están en [screenshots/](screenshots/).
