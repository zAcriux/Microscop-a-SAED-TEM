# Manual de usuario de TEM Lab

## 1. Instalación

1. Copie `TEMLab-0.1.1-release.apk` al dispositivo (Android 8.0 o superior).
2. Ábralo y permita la instalación desde esa fuente.
3. El idioma (español o inglés) y el tema (claro u oscuro) se cambian en ⚙ **Ajustes**. Por defecto siguen la
   configuración del sistema.

## 2. Inicio y proyectos

* **Abrir micrografías:** puede seleccionar varios archivos a la vez (DM3, DM4, TIFF de 8/16/32 bits o
  multipágina, MRC, PNG, JPG o BMP). Cada página o imagen se abre en su propia pestaña, en la barra superior.
* **Proyecto actual:** escriba el nombre, la muestra, el operador y la descripción, y pulse **Guardar**. El
  proyecto conserva las calibraciones, el procesamiento, las FFT, las máscaras IFFT, las partículas, las
  mediciones, las anotaciones y las plantillas.
* **Copia / exportar:** crea un `.zip` con el proyecto y, si lo indica, una copia de los archivos originales.
  **Importar** acepta tanto `.zip` como `.temlab.json`.
* **Archivos recientes:** un toque los vuelve a abrir.
* La rueda ← (Inicio) regresa a esta pantalla sin cerrar las imágenes.

## 3. Gestos

| Gesto | Acción |
|---|---|
| Dos dedos | Zoom y desplazamiento, en cualquier herramienta |
| Un dedo | Desplaza la imagen o dibuja con la herramienta activa |
| Dibujar | Aparece una **lupa ×3** con cruz en la esquina para posicionar con precisión |

Los botones ↺ ↻ rotan **solo la vista**; ⛶ ajusta la imagen a la pantalla. Al hacer zoom se cargan los píxeles
reales a resolución completa. El lápiz digital funciona igual que el dedo.

## 4. Medir

* **Herramientas:** distancia, diámetro, radio, ángulo (3 toques), longitud por tramos (toques + *Terminar*),
  área a mano alzada, franjas (línea sobre N periodos → d = L/N), perfil (con ancho de promediado) y calibración de
  dos puntos.
* **Perfil:** muestra la gráfica, el periodo calculado por Fourier, la separación media entre máximos y la
  orientación de las franjas.
* **Calibración espacial:** muestra la calibración leída del archivo. Puede introducir el tamaño de píxel de X e Y
  por separado (pm, Å, nm, µm o mm), restaurar el valor del archivo y consultar el **registro de cambios**.
* **Barra de escala:** es solo visual (longitud, texto, posición, color, grosor y fondo). **Modificarla no cambia
  la calibración.**
* **Visualización:** mínimo, máximo, brillo, contraste, gamma, inversión y Auto. No alteran los datos.
* **Metadatos:** dimensiones, tipo de datos, rango y todas las etiquetas del archivo.

Los diámetros y radios manuales pueden marcarse con *Incluir en la distribución*. Si se dibujan sobre una partícula
ya detectada, se marcan como **duplicado** y no se cuentan dos veces.

## 5. Procesar

* Toque una operación para añadirla: gaussiano, mediana, bilateral, reducción de ruido (Wiener), pasa bajas, pasa
  altas, máscara de enfoque, laplaciano, Sobel, bordes LoG, normalizar, invertir, ecualización, CLAHE, fondo
  (gaussiano o plano), rotar, voltear y recortar.
* La lista de operaciones se aplica siempre al original (**no destructivo**). Use ↶/↷ para deshacer o rehacer y ✕
  para quitar un paso.
* **Antes/después:** compara con una línea divisoria deslizable y zoom sincronizado.
* **Configuraciones reutilizables:** guarde la lista de operaciones con un nombre y aplíquela a otra imagen o a
  **todas** las imágenes abiertas (procesamiento por lotes).
* **Exportar:** TIFF de 32 bits con la calibración.

## 6. FFT (HRTEM)

1. **Seleccione la región cuadrada** sobre la micrografía:
   * toque para centrar el cuadrado;
   * arrastre dentro para moverlo;
   * arrastre el asa amarilla de la esquina para cambiar el tamaño (siempre 1:1);
   * arrastre fuera para dibujar uno nuevo;
   * o use los tamaños fijos 128/256/512/1024/2048 o un tamaño personalizado.
2. Elija la **ventana Hann** y el **zero-padding** (×2 o ×4) y pulse **Calcular FFT**.
3. Ajuste la visualización: escala logarítmica, potencia, mapa de color, contraste y gamma. Se muestra la
   calibración recíproca Δk.
4. **Medir:**
   * *Tocar máximo:* toque cerca de una reflexión, o arrastre para usar la lupa. La app localiza el máximo local
     dentro del *radio de búsqueda*, lo refina con precisión subpíxel y muestra **d = 1/|g|** en nm o Å.
   * *Par simétrico:* toque g y −g. Se calcula d con el vector promedio, que no depende del error del origen, y se
     indica la diferencia entre ambos.
   * *Ángulo:* toque dos reflexiones.
   * *Perfil:* arrastre una línea sobre la FFT.
   * *Eliminar:* toque una reflexión.
   * **Detectar** encuentra máximos de forma automática.
5. **Resultados:** tabla con coordenadas, |g|, d y dirección, más los pares y ángulos. Se puede exportar como PNG
   anotado, CSV o XLSX.
6. Puede guardar **varias FFT** de distintas regiones de la misma micrografía y volver a cualquiera de ellas.
7. **Comparación cristalográfica:** elija una celda de referencia o defina una propia. La app propone familias
   {hkl} y pares compatibles con el ángulo y el eje de zona. **Estas propuestas son tentativas:** una distancia
   interplanar por sí sola no identifica una fase.

## 7. IFFT (filtrado de Fourier)

1. La región es la misma que en la FFT; puede editarla con *Editar región*.
2. En el panel **FFT + máscara**, toque las reflexiones. La forma (círculo, elipse, rectángulo o gaussiana) se
   centra en el máximo refinado. Use *Mover* o *Eliminar* para editar las formas, y ajuste el radio, los semiejes,
   el ángulo y la suavidad del borde. Los filtros pasa bajas, pasa altas y pasa banda usan los radios interno y
   externo.
3. **Máscaras conjugadas automáticas:** mantienen la simetría hermítica para que la reconstrucción sea real. El
   control "residuo imaginario" debe ser cercano a 0.
4. Los cuatro paneles (región original, FFT con máscara, máscara e IFFT) se actualizan automáticamente. Cada uno
   tiene su propio zoom, recortado a su recuadro; active *Zoom sincronizado* si prefiere moverlos juntos. El botón ⛶
   de cada panel lo amplía a toda el área y, al tocarlo de nuevo, vuelve a los cuatro paneles. La pantalla FFT tiene
   el mismo botón en la micrografía y en el espectro.
5. Arrastre una línea sobre la IFFT para obtener el perfil, el periodo y la orientación, comparados con la d de
   cada reflexión enmascarada.
6. **Abrir como imagen** crea una pestaña ⚗ etiquetada como filtrada. También puede exportar TIFF de 32 bits
   (calibrado), PNG o la máscara. El historial IFFT guarda cada máscara.

⚠ Las imágenes filtradas pueden contener artefactos. No presente sus detalles como observaciones independientes.

## 8. Partículas

1. **Segmentación:**
   * método: Manual (con deslizador), Otsu, Adaptativa, Contornos o Rango de intensidad;
   * polaridad: partículas oscuras (TEM en campo claro) o claras (HAADF/SEM);
   * suavizado, apertura, cierre, relleno y **watershed** para separar partículas en contacto.
   *Vista previa de la máscara* muestra en rojo lo que se detectaría.
2. Pulse **Detectar partículas**. Los contornos aparecen en verde si están incluidas y en rojo discontinuo si
   están excluidas, con sus números.
3. **Filtros:** área mínima y máxima, circularidad, relación de aspecto y bordes.
4. **Corrección manual:**
   * *Seleccionar:* muestra los parámetros de la partícula.
   * *Eliminar.*
   * *Excluir/incluir.*
   * *Añadir:* dibuje el contorno a mano alzada.
   * *Redibujar contorno:* primero seleccione la partícula.
   * *Fusionar:* toque varias partículas y pulse el botón.
   * *Dividir:* trace una línea de corte.
   La lupa ayuda en los bordes finos, y la tabla y el histograma se actualizan **de inmediato**.
5. **Exportar:** CSV, XLSX, PNG numerado y PNG con contornos.

## 9. Estadística

* Elija la variable (ECD, área, perímetro, Feret, ejes, aspecto, circularidad o intensidad).
* Defina el número de intervalos (automático por defecto) o su ancho, frecuencia absoluta o relativa y el ajuste
  lognormal (con la prueba de Kolmogórov–Smirnov).
* Edite el título y la etiqueta del eje.
* La estadística incluye N, media ± DE, mediana, error estándar, coeficiente de variación, mínimo, máximo y los
  percentiles P10, P25, P75 y P90.
* **Comparar distribuciones** superpone otras micrografías.
* **Exportar:** PNG, SVG, CSV y XLSX.

## 10. Anotar, Comparar y Reporte

* **Anotar:** flechas, líneas, círculos, rectángulos, cuadrados, etiquetas, índices hkl, distancias d y
  numeración. Los elementos se organizan en **capas** que puede mostrar u ocultar. La figura se exporta como PNG o
  JPG **a la resolución nativa**, con la barra de escala.
* **Comparar:** 2 imágenes en teléfono o 4 en tableta, con zoom sincronizado. Puede comparar imágenes, FFT o
  histogramas.
* **Reporte:** elija las secciones (muestra, adquisición, originales, procesadas, procesamiento, FFT, IFFT,
  partículas, histogramas y tablas) y las micrografías, y pulse **Generar reporte PDF**. Las selecciones pueden
  guardarse como **plantillas**.

---
© 2026 F. Mares-Briones. Todos los derechos reservados.
