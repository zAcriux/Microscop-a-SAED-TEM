import React, { useRef, useState, useEffect } from "react";
import {
  CrystallineStructure,
  SimulationParams,
  ExperimentalImage,
  Measurement,
  ExperimentalPeak,
  Reflection,
} from "../types";
import {
  calculateElectronWavelength,
  getPowderReflections,
  generateReflections,
  getSingleCrystalSpots,
} from "../utils/crystalMath";
import { Move, Ruler, SlidersHorizontal, PlusCircle, Trash, Maximize, CircleDot, Download } from "lucide-react";

interface ComponentProps {
  structure: CrystallineStructure | null;
  params: SimulationParams;
  expImage: ExperimentalImage;
  setExpImage: React.Dispatch<React.SetStateAction<ExperimentalImage>>;
  measurements: Measurement[];
  setMeasurements: React.Dispatch<React.SetStateAction<Measurement[]>>;
  expPeaks: ExperimentalPeak[];
  setExpPeaks: React.Dispatch<React.SetStateAction<ExperimentalPeak[]>>;
  activeTool: "select" | "measure_dist" | "measure_angle" | "add_peak";
  hoveredRef: Reflection | null;
  setHoveredRef: (r: Reflection | null) => void;
}

export default function DiffractionPatternCanvas({
  structure,
  params,
  expImage,
  setExpImage,
  measurements,
  setMeasurements,
  expPeaks,
  setExpPeaks,
  activeTool,
  hoveredRef,
  setHoveredRef,
}: ComponentProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [simRings, setSimRings] = useState<Reflection[]>([]);
  const [simSpots, setSimSpots] = useState<Reflection[]>([]);

  // Drag and click mechanics
  const [isDrawingMeasure, setIsDrawingMeasure] = useState(false);
  const [activePoints, setActivePoints] = useState<{ x: number; y: number }[]>([]);
  const [cursorPos, setCursorPos] = useState({ x: 0, y: 0 });

  // split ratio for comparing experimental vs simulated (0-100)
  const [splitRatio, setSplitRatio] = useState<number>(50);

  // Load and pre-calculate reflections whenever structure or parameters shift
  useEffect(() => {
    if (!structure) {
      setSimRings([]);
      setSimSpots([]);
      return;
    }

    if (params.mode === "rings" || params.mode === "xrd") {
      const rawRefs = generateReflections(
        structure,
        params.minDSpacing,
        params.voltage,
        params.cameraLength,
        params.detectorSize,
        params.resolution
      );
      const powder = getPowderReflections(rawRefs);
      setSimRings(powder);
    } else {
      const spots = getSingleCrystalSpots(structure, params);
      setSimSpots(spots);
    }
  }, [structure, params]);

  // Main canvas render pipeline
  useEffect(() => {
    drawPattern();
  }, [structure, params, simRings, simSpots, expImage, measurements, expPeaks, activePoints, cursorPos, splitRatio]);

  const drawPattern = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const size = params.resolution;
    canvas.width = size;
    canvas.height = size;

    ctx.clearRect(0, 0, size, size);

    // Calculate vertical split boundary in coordinates
    const splitLimit = (splitRatio / 100) * size;

    // A. FIRST, ALWAYS DRAW THE UNIFORM BACKGROUND SCREEN (with concentric circular guide lines)
    ctx.fillStyle = "#0c0a09"; // Slate 950
    ctx.fillRect(0, 0, size, size);

    ctx.strokeStyle = "rgba(100, 116, 139, 0.15)";
    ctx.lineWidth = 0.5;
    const cx = size / 2;
    const cy = size / 2;
    for (let r = 50; r < size; r += 50) {
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, 2 * Math.PI);
      ctx.stroke();
    }

    // 1. DRAW EXPERIMENTAL IMAGE (CLIPPED TO LEFT SIDE ONLY IF SPLITRATIO > 0)
    if (expImage.dataUrl && splitRatio > 0) {
      const img = new Image();
      img.src = expImage.dataUrl;
      // We must render compiled layers synchronously. If image is loaded, draw it
      if (img.complete) {
        ctx.save();
        // Clip to left side:
        ctx.beginPath();
        ctx.rect(0, 0, splitLimit, size);
        ctx.clip();

        ctx.globalAlpha = expImage.alpha !== undefined ? expImage.alpha : 0.6;
        // Native CSS filtering for contrast, brightness, inversion
        ctx.filter = `brightness(${100 + expImage.brightness}%) contrast(${
          expImage.contrast * 100
        }%) ${expImage.inverted ? "invert(100%)" : "invert(0%)"}`;

        // Pivot around center for translation and rotation
        ctx.translate(cx + expImage.offsetX, cy + expImage.offsetY);
        ctx.rotate((expImage.rotation * Math.PI) / 180);

        // Compute draw sizing with scaling factor
        const drawW = size * expImage.scale;
        const drawH = size * expImage.scale;
        ctx.drawImage(img, -drawW / 2, -drawH / 2, drawW, drawH);
        ctx.restore();
      }
    }

    // 2. DRAW SIMULATED BRAGGS (CLIPPED TO RIGHT SIDE ONLY IF SPLITRATIO < 100)
    if (structure && splitRatio < 100) {
      ctx.save();
      // Clip to right side:
      ctx.beginPath();
      ctx.rect(splitLimit, 0, size - splitLimit, size);
      ctx.clip();

      if (params.mode === "rings") {
        // Render concentric powder diffraction rings
        simRings.forEach((ring) => {
          if (ring.radiusPx <= 0) return;

          ctx.beginPath();
          ctx.arc(cx, cy, ring.radiusPx, 0, 2 * Math.PI);

          // Alpha mapping based on structure factor relative intensity
          ctx.strokeStyle = `rgba(34, 197, 94, ${Math.max(0.12, (ring.intensity / 100) * params.relativeIntensity * 0.45)})`;
          ctx.lineWidth = Math.max(0.7, (ring.intensity / 100) * 2.5);
          ctx.stroke();

          // Optional hkl ring labeling
          if (params.showLabels && ring.intensity > 15 && ring.radiusPx > 35) {
            ctx.fillStyle = "rgba(74, 222, 128, 0.55)";
            ctx.font = "8px monospace";
            ctx.fillText(
              `(${ring.h} ${ring.k} ${ring.l})`,
              cx + ring.radiusPx * 0.707 + 3,
              cy - ring.radiusPx * 0.707 - 3
            );
          }
        });
      } else if (params.mode === "spots") {
        // Render single-crystal SAED spots
        simSpots.forEach((spot) => {
          if (spot.xPx === undefined || spot.yPx === undefined) return;

          const sx = cx + spot.xPx;
          const sy = cy - spot.yPx; // standard Cartesian mapping flip coordinates

          // Draw neon glowing spots using radial gradients (electron phosphor look!)
          const radius = Math.max(2, Math.sqrt(spot.intensity) * 1.5);
          const grad = ctx.createRadialGradient(sx, sy, 0, sx, sy, radius);
          grad.addColorStop(0, "rgba(255, 255, 255, 1.0)");
          grad.addColorStop(0.3, "rgba(34, 197, 94, 0.9)");
          grad.addColorStop(1, "rgba(34, 197, 94, 0)");

          ctx.beginPath();
          ctx.arc(sx, sy, radius, 0, 2 * Math.PI);
          ctx.fillStyle = grad;
          ctx.fill();

          // Delicate indicator glow border
          ctx.beginPath();
          ctx.arc(sx, sy, radius * 0.5, 0, 2 * Math.PI);
          ctx.strokeStyle = "rgba(224, 242, 254, 0.65)";
          ctx.lineWidth = 0.5;
          ctx.stroke();

          // Label Bragg spots
          if (params.showLabels && spot.intensity > 0.05 * params.relativeIntensity) {
            ctx.fillStyle = "rgba(187, 247, 208, 0.75)";
            ctx.font = "8.5px monospace";
            ctx.fillText(`${spot.h} ${spot.k} ${spot.l}`, sx + radius * 0.6 + 2, sy - 2);
          }
        });
      }
      ctx.restore();
    }

    // 3. DRAW SPLIT VIEW VERTICAL DIVIDER BAR (ONLY IF SPLIT IS INTERMEDIATE)
    if (splitRatio > 0 && splitRatio < 100) {
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(splitLimit, 0);
      ctx.lineTo(splitLimit, size);
      ctx.strokeStyle = "rgb(59, 130, 246)"; // Bright Blue-500
      ctx.lineWidth = 2;
      ctx.stroke();

      // Small central circular handle
      ctx.beginPath();
      ctx.arc(splitLimit, size / 2, 8, 0, 2 * Math.PI);
      ctx.fillStyle = "rgb(59, 130, 246)";
      ctx.fill();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 1.5;
      ctx.stroke();

      // White inner core
      ctx.beginPath();
      ctx.arc(splitLimit, size / 2, 3, 0, 2 * Math.PI);
      ctx.fillStyle = "#ffffff";
      ctx.fill();

      // Draw subtle text tags in monospace
      ctx.shadowColor = "rgba(0, 0, 0, 1)";
      ctx.shadowBlur = 4;
      ctx.fillStyle = "rgba(255, 255, 255, 0.8)";
      ctx.font = "bold 9px monospace";
      ctx.textAlign = "right";
      ctx.fillText("EXPERIMENTAL", splitLimit - 14, size / 2 + 3);
      ctx.textAlign = "left";
      ctx.fillText("SIMULADO", splitLimit + 14, size / 2 + 3);

      ctx.restore();
    }

    // 4. DRAW MEASURED RINGS (CLICKED EXPERIMENTAL RINGS)
    expPeaks.forEach((p, index) => {
      ctx.beginPath();
      ctx.arc(cx, cy, p.radiusPx, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(249, 115, 22, 0.75)"; // Orange indicators
      ctx.lineWidth = 1.2;
      ctx.setLineDash([4, 4]); // Dashed rings
      ctx.stroke();
      ctx.setLineDash([]); // clear

      // Label index
      ctx.fillStyle = "rgb(249, 115, 22)";
      ctx.font = "bold 9px monospace";
      ctx.fillText(`#${index + 1} d=${p.d.toFixed(2)}Å`, cx + p.radiusPx * 0.82, cy + 8);
    });

    // 5. DRAW CENTRAL TRANSMISSION POINT Indicator
    ctx.beginPath();
    ctx.arc(cx, cy, 3, 0, 2 * Math.PI);
    ctx.fillStyle = "#ffffff";
    ctx.fill();
    ctx.strokeStyle = "rgba(239, 68, 68, 0.9)"; // red crosshair
    ctx.lineWidth = 1.2;
    ctx.stroke();

    ctx.beginPath();
    ctx.moveTo(cx - 10, cy); ctx.lineTo(cx + 10, cy);
    ctx.moveTo(cx, cy - 10); ctx.lineTo(cx, cy + 10);
    ctx.strokeStyle = "rgba(239, 68, 68, 0.6)";
    ctx.stroke();

    // 6. DRAW ACTIVE INTERACTIVE MEASUREMENTS
    measurements.forEach((m) => {
      if (m.points.length < 2) return;

      const p1 = m.points[0];
      const p2 = m.points[1];

      // Draw dashed reference ruler
      ctx.beginPath();
      ctx.moveTo(p1.x, p1.y);
      ctx.lineTo(p2.x, p2.y);
      ctx.strokeStyle = "#38bdf8"; // Celestial Cyan
      ctx.lineWidth = 1.5;
      ctx.stroke();

      // End tick caps
      ctx.fillStyle = "#38bdf8";
      ctx.beginPath();
      ctx.arc(p1.x, p1.y, 4, 0, 2 * Math.PI);
      ctx.arc(p2.x, p2.y, 4, 0, 2 * Math.PI);
      ctx.fill();

      // Render overlay text
      ctx.fillStyle = "#ffffff";
      ctx.font = "bold 10px sans-serif";
      ctx.textAlign = "center";
      const mx = (p1.x + p2.x) / 2;
      const my = (p1.y + p2.y) / 2 - 8;
      ctx.fillText(m.label, mx, my);
    });

    // Draw active drawing measurement preview
    if (activePoints.length > 0) {
      const p1 = activePoints[0];
      ctx.beginPath();
      ctx.moveTo(p1.x, p1.y);
      ctx.lineTo(cursorPos.x, cursorPos.y);
      ctx.strokeStyle = "rgba(56, 189, 248, 0.5)";
      ctx.lineWidth = 1.2;
      ctx.stroke();
    }
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / canvas.clientWidth) * params.resolution;
    const y = ((e.clientY - rect.top) / canvas.clientHeight) * params.resolution;

    setCursorPos({ x, y });

    // Enable HKL code only in "select" cursor tool and within the simulated side
    const splitLimit = (splitRatio / 100) * params.resolution;
    if (activeTool !== "select" || x < splitLimit) {
      setHoveredRef(null);
      return;
    }

    const screenThreshold = 25; // comfortable pixels range on viewport screen
    const coordThreshold = Math.max(18, screenThreshold * (params.resolution / canvas.clientWidth));

    // Multi-spot proximity helper tooltip indicator search
    if (params.mode === "spots" && structure && simSpots.length > 0) {
      const lx = params.resolution / 2;
      const ly = params.resolution / 2;

      let closestSpot: Reflection | null = null;
      let minDist = coordThreshold;

      for (const spot of simSpots) {
        if (spot.xPx === undefined || spot.yPx === undefined) continue;
        const sx = lx + spot.xPx;
        const sy = ly - spot.yPx; // standard Cartesian mapping flip coordinates
        const d = Math.sqrt((x - sx) * (x - sx) + (y - sy) * (y - sy));

        if (d < minDist) {
          minDist = d;
          closestSpot = spot;
        }
      }
      setHoveredRef(closestSpot);
    } else if (params.mode === "rings" && structure && simRings.length > 0) {
      const cx = params.resolution / 2;
      const cy = params.resolution / 2;
      const dx = x - cx;
      const dy = y - cy;
      const radPx = Math.sqrt(dx * dx + dy * dy);

      let closestRing: Reflection | null = null;
      let minDist = Math.max(12, 12 * (params.resolution / canvas.clientWidth)); // 12-pixel envelope on screen

      for (const ring of simRings) {
        if (ring.radiusPx <= 0) continue;
        const diff = Math.abs(radPx - ring.radiusPx);
        if (diff < minDist) {
          minDist = diff;
          closestRing = ring;
        }
      }
      setHoveredRef(closestRing);
    } else {
      setHoveredRef(null);
    }
  };

  const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / canvas.clientWidth) * params.resolution;
    const y = ((e.clientY - rect.top) / canvas.clientHeight) * params.resolution;

    const size = params.resolution;
    const cx = size / 2;
    const cy = size / 2;

    if (activeTool === "add_peak") {
      // Adding an experimental ring by clicking on canvas (radius from direct center)
      const dx = x - cx;
      const dy = y - cy;
      const radPx = Math.sqrt(dx * dx + dy * dy);

      if (radPx < 5) return;

      // Map pixels to physical d-spacing in Å
      // Formula: R_px = (L_lambda / d) * (size / detectorSize)
      // Therefore: d = L_lambda * size / (R_px * detectorSize)
      const lambda = calculateElectronWavelength(params.voltage);
      const L_lambda = params.cameraLength * lambda;
      const d = (L_lambda * size) / (radPx * params.detectorSize);

      const newPeak: ExperimentalPeak = {
        id: `peak_${Date.now()}`,
        d,
        radiusPx: radPx,
      };

      setExpPeaks((prev) => [...prev, newPeak]);
    } else if (activeTool === "measure_dist") {
      // Direct ruler line measurement
      if (activePoints.length === 0) {
        setActivePoints([{ x, y }]);
      } else {
        const p1 = activePoints[0];
        const dx = x - p1.x;
        const dy = y - p1.y;
        const dPx = Math.sqrt(dx * dx + dy * dy);

        // Compute physical measurement length:
        // By using standard diffraction screen scale coordinates:
        // size Represents params.detectorSize (in mm)
        const dMm = (dPx * params.detectorSize) / size;
        const lambda = calculateElectronWavelength(params.voltage);
        // physical d-spacing value
        const valDSpacing = dMm > 0 ? (params.cameraLength * lambda) / dMm : 0;

        const label = `d-spacing ≈ ${valDSpacing.toFixed(3)} Å  (${dMm.toFixed(2)} mm)`;

        const newMeasure: Measurement = {
          id: `measure_${Date.now()}`,
          type: "distance",
          points: [p1, { x, y }],
          value: valDSpacing,
          label,
        };

        setMeasurements((prev) => [...prev, newMeasure]);
        setActivePoints([]);
      }
    }
  };

  // Quick helper to move/shift backdrop center alignment
  const moveCenter = (direction: "up" | "down" | "left" | "right", step = 1) => {
    setExpImage((prev) => {
      const next = { ...prev };
      if (direction === "up") next.offsetY -= step;
      if (direction === "down") next.offsetY += step;
      if (direction === "left") next.offsetX -= step;
      if (direction === "right") next.offsetX += step;
      return next;
    });
  };

  const downloadCanvasImage = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const link = document.createElement("a");
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "_");
    const modeStr = params.mode === "rings" ? "policristal" : "monocristal";
    link.download = `difraccion_${modeStr}_${timestamp}.png`;
    link.href = canvas.toDataURL("image/png");
    link.click();
  };

  return (
    <div className="prof-panel select-none h-full flex flex-col justify-between overflow-hidden">
      <div className="prof-panel-header px-3 flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-1.5 font-bold shrink-0">
          <CircleDot className="w-3.5 h-3.5 text-blue-500 animate-pulse" />
          <span>PATRÓN DE DIFRACCIÓN TEM SIMULADO & SUPERPOSICIÓN</span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {hoveredRef && (
            <span className="text-[10px] font-mono text-emerald-500 bg-zinc-950/85 border border-emerald-950 px-2.5 py-0.5 rounded font-bold animate-pulse">
              INDICE: ({hoveredRef.h} {hoveredRef.k} {hoveredRef.l}) · d={hoveredRef.d.toFixed(3)} Å
            </span>
          )}
          <span className="text-[10px] text-zinc-400 bg-zinc-950 border border-zinc-800 px-2 py-0.5 rounded font-mono font-bold uppercase shrink-0">
            {params.mode === "rings" ? "POLICRISTAL" : "MONOCRISTAL"}
          </span>
          <button
            onClick={downloadCanvasImage}
            className="cursor-pointer bg-blue-600 hover:bg-blue-700 text-white text-[9.5px] font-bold h-[22px] px-2.5 rounded flex items-center gap-1 transition-all shadow-xs shrink-0"
            title="Descargar patrón de difracción simulado y superpuesto de alta resolución"
          >
            <Download className="w-3 h-3" />
            <span>DESCARGAR IMAGEN</span>
          </button>
        </div>
      </div>

      <div ref={containerRef} className="relative flex-1 bg-[#090d16] flex items-center justify-center overflow-hidden min-h-[380px]">
        <canvas
          ref={canvasRef}
          onMouseMove={handleMouseMove}
          onClick={handleCanvasClick}
          className="max-h-full max-w-full aspect-square border border-zinc-900 rounded bg-[#020617] block shadow-[inset_0_0_20px_rgba(0,0,0,0.8)]"
          style={{ width: "100%", maxWidth: "480px" }}
        />

        {/* Backdrop Center Calibrator Mini Controls Overlay (Only visible when image loaded) */}
        {expImage.dataUrl && (
          <div className="absolute top-3 left-3 bg-zinc-950/95 border border-zinc-800/80 p-2.5 rounded-lg flex flex-col gap-1.5 shadow-lg select-none">
            <span className="text-[9px] font-bold uppercase text-zinc-400 tracking-wider flex items-center gap-1">
              <Move className="w-3 h-3 text-zinc-500" /> AJUSTAR CENTRO
            </span>
            <div className="grid grid-cols-3 gap-1 w-[88px]">
              <div />
              <button
                onClick={(e) => moveCenter("up", e.shiftKey ? 5 : 1)}
                className="cursor-pointer bg-zinc-900 hover:bg-zinc-850 active:bg-zinc-800 text-zinc-300 p-1 rounded text-center text-[10px] font-bold border border-zinc-800 transition-all"
                title="Mover arriba: Clic para 1px, Shift+Clic para 5px"
              >
                ▲
              </button>
              <div />
              <button
                onClick={(e) => moveCenter("left", e.shiftKey ? 5 : 1)}
                className="cursor-pointer bg-zinc-900 hover:bg-zinc-850 active:bg-zinc-800 text-zinc-300 p-1 rounded text-center text-[10px] font-bold border border-zinc-800 transition-all"
                title="Mover izquierda: Clic para 1px, Shift+Clic para 5px"
              >
                ◀
              </button>
              <button
                onClick={() => {
                  setExpImage((prev) => ({ ...prev, offsetX: 0, offsetY: 0 }));
                }}
                className="cursor-pointer bg-zinc-900 hover:bg-red-950 hover:text-red-400 hover:border-red-900 text-[8px] text-zinc-400 rounded text-center border border-zinc-800 font-extrabold transition-all"
                title="Restablecer centrado"
              >
                RST
              </button>
              <button
                onClick={(e) => moveCenter("right", e.shiftKey ? 5 : 1)}
                className="cursor-pointer bg-zinc-900 hover:bg-zinc-850 active:bg-zinc-800 text-zinc-300 p-1 rounded text-center text-[10px] font-bold border border-zinc-800 transition-all"
                title="Mover derecha: Clic para 1px, Shift+Clic para 5px"
              >
                ▶
              </button>
              <div />
              <button
                onClick={(e) => moveCenter("down", e.shiftKey ? 5 : 1)}
                className="cursor-pointer bg-zinc-900 hover:bg-zinc-850 active:bg-zinc-800 text-zinc-300 p-1 rounded text-center text-[10px] font-bold border border-zinc-800 transition-all"
                title="Mover abajo: Clic para 1px, Shift+Clic para 5px"
              >
                ▼
              </button>
            </div>
            <div className="flex flex-col gap-0.5 pt-1 border-t border-zinc-900">
              <div className="flex justify-between text-[8px] font-mono text-zinc-500 font-semibold">
                <span>X: {expImage.offsetX}px</span>
                <span>Y: {expImage.offsetY}px</span>
              </div>
              <span className="text-[7.5px] text-zinc-650 leading-tight">
                *Shift+Clic para mover más rápido
              </span>
            </div>
          </div>
        )}

        {/* Quick Calibration Guide overlay */}
        <div className="absolute bottom-3 right-3 pointer-events-none flex flex-col gap-0.5 bg-zinc-950/90 border border-zinc-800 px-2.5 py-1.5 rounded shadow-lg">
          <span className="text-[9px] text-zinc-400 font-mono">
            DETECTOR: {params.detectorSize} x {params.detectorSize} mm
          </span>
          <span className="text-[9px] text-zinc-400 font-mono">
            SCALE: {expImage.scale.toFixed(3)}x | ROT: {expImage.rotation}°
          </span>
          <span className="text-[8.5px] text-blue-400 font-bold uppercase font-mono">
            λ_elec = {(calculateElectronWavelength(params.voltage) * 100).toFixed(2)} pm ({params.voltage} kV)
          </span>
        </div>
      </div>

      {/* Split/Blend Comparative Slider */}
      <div className="px-3 py-2 bg-zinc-950 border-t border-zinc-900 flex flex-col gap-1.5 select-none shrink-0">
        <div className="flex items-center justify-between text-[10px]">
          <span className="text-zinc-400 font-bold uppercase tracking-wider flex items-center gap-1.5">
            <SlidersHorizontal className="w-3.5 h-3.5 text-blue-500 animate-pulse" /> COMPARADOR DE IMAGEN (DESLIZADOR)
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setSplitRatio(100)}
              className={`cursor-pointer px-2 py-0.5 rounded text-[8.5px] font-bold border transition-all ${
                splitRatio === 100
                  ? "bg-blue-650 border-blue-600 text-white bg-blue-600"
                  : "bg-zinc-900 text-zinc-400 border-zinc-800 hover:text-zinc-200"
              }`}
              title="Mostrar únicamente patrón experimental"
            >
              EXPERIMENTAL
            </button>
            <button
              onClick={() => setSplitRatio(50)}
              className={`cursor-pointer px-2 py-0.5 rounded text-[8.5px] font-bold border transition-all ${
                splitRatio === 50
                  ? "bg-blue-650 border-blue-600 text-white bg-blue-600"
                  : "bg-zinc-900 text-zinc-400 border-zinc-800 hover:text-zinc-200"
              }`}
              title="Dividir pantalla mitad experimental, mitad simulado"
            >
              MITAD (50:50)
            </button>
            <button
              onClick={() => setSplitRatio(0)}
              className={`cursor-pointer px-2 py-0.5 rounded text-[8.5px] font-bold border transition-all ${
                splitRatio === 0
                  ? "bg-blue-650 border-blue-600 text-white bg-blue-600"
                  : "bg-zinc-900 text-zinc-400 border-zinc-800 hover:text-zinc-200"
              }`}
              title="Mostrar únicamente patrón simulado"
            >
              SIMULADO
            </button>
          </div>
        </div>
        <div className="flex items-center gap-2.5">
          <span className="text-[9.5px] font-mono font-medium text-zinc-550 dark:text-zinc-450">EXPERIMENTAL (Izq.)</span>
          <input
            type="range"
            min="0"
            max="100"
            value={splitRatio}
            onChange={(e) => setSplitRatio(parseInt(e.target.value))}
            className="flex-1 h-1.5 bg-zinc-800 rounded appearance-none cursor-pointer accent-blue-600"
            title="Desliza para ajustar la división del visor experimental / simulado"
          />
          <span className="text-[9.5px] font-mono font-medium text-zinc-550 dark:text-zinc-450">SIMULADO (Der.)</span>
          <span className="text-[9.5px] font-mono font-bold text-blue-500 bg-zinc-900 border border-zinc-800/85 px-1.5 py-0.2 rounded shrink-0">
            {splitRatio}% : {100 - splitRatio}%
          </span>
        </div>
      </div>

      {/* Tool description indicator */}
      <div className="px-3 h-7 bg-zinc-950 border-t border-zinc-800 text-[10px] text-zinc-400 flex items-center justify-between select-none">
        <span className="font-medium truncate max-w-[60%]">
          {activeTool === "select" && "🔍 HKL CURSOR: Pasa el mouse sobre el patrón de spots o anillos."}
          {activeTool === "add_peak" && "🍊 IDENTIFICACIÓN: Haz clic en d-spacing anillos experimentales."}
          {activeTool === "measure_dist" && "📐 REGLA: Haz clic y arrastra para medir d-spacing."}
        </span>
        <div className="flex items-center gap-3 shrink-0">
          {measurements.length > 0 && (
            <button
              onClick={() => setMeasurements([])}
              className="cursor-pointer text-[9px] font-bold text-zinc-500 hover:text-red-400 flex items-center gap-1 transition-all"
              title="Limpiar todas las mediciones de d-spacing hechas en el patrón"
            >
              <Trash className="w-3 h-3 text-sky-400" /> LIMPIAR REGLAS ({measurements.length})
            </button>
          )}
          {expPeaks.length > 0 && (
            <button
              onClick={() => setExpPeaks([])}
              className="cursor-pointer text-[9px] font-bold text-zinc-500 hover:text-red-400 flex items-center gap-1 transition-all"
              title="Limpiar todos los anillos experimentales indexados"
            >
              <Trash className="w-3 h-3 text-orange-500" /> LIMPIAR ANILLOS ({expPeaks.length})
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
