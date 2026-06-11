import React, { useRef, useState, useEffect } from "react";
import { ExperimentalImage, FFTMask } from "../types";
import { compute2DFFT, fftShift } from "../utils/crystalMath";
import { Eye, EyeOff, Zap, RefreshCw, Layers, Plus, Trash2, Sliders } from "lucide-react";

interface ComponentProps {
  image: ExperimentalImage | null;
}

export default function FFTAnalysisPanel({ image }: ComponentProps) {
  const fftSize = 256; // 256 is perfect for fast execution and high accuracy
  const size = 256;

  const [hasImage, setHasImage] = useState(false);
  const [selectedZone, setSelectedZone] = useState<{ x: number; y: number; w: number; h: number }>({
    x: 0,
    y: 0,
    w: 256,
    h: 256,
  });

  const [realGrid, setRealGrid] = useState<Float32Array | null>(null);
  const [fftReal, setFftReal] = useState<Float32Array | null>(null);
  const [fftImag, setFftImag] = useState<Float32Array | null>(null);

  // Masks
  const [masks, setMasks] = useState<FFTMask[]>([
    { id: "mask_0", x: 0.5, y: 0.5, radius: 0.08, type: "pass" }, // central direct bean
  ]);

  const [reconstructedReal, setReconstructedReal] = useState<Float32Array | null>(null);

  const [activeFilterTab, setActiveFilterTab] = useState<"lowpass" | "highpass" | "bandpass" | "masks">("masks");
  const [lowpassCutoff, setLowpassCutoff] = useState<number>(45); // px radius
  const [highpassCutoff, setHighpassCutoff] = useState<number>(8); // px radius

  const origCanvasRef = useRef<HTMLCanvasElement>(null);
  const fftCanvasRef = useRef<HTMLCanvasElement>(null);
  const ifftCanvasRef = useRef<HTMLCanvasElement>(null);

  // Load and sample experimental image to 256x256 grayscale matrix
  useEffect(() => {
    if (!image || !image.dataUrl) {
      setHasImage(false);
      return;
    }

    const img = new Image();
    img.crossOrigin = "anonymous";
    img.src = image.dataUrl;
    img.onload = () => {
      setHasImage(true);

      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;

      // Draw experimental image scaled into size x size box
      ctx.drawImage(img, 0, 0, size, size);
      const imgData = ctx.getImageData(0, 0, size, size);
      const data = imgData.data;

      // Extract grayscale intensities
      const grid = new Float32Array(size * size);
      for (let i = 0; i < size * size; i++) {
        const r = data[i * 4];
        const g = data[i * 4 + 1];
        const b = data[i * 4 + 2];
        // Luminosity formula
        grid[i] = 0.299 * r + 0.587 * g + 0.114 * b;
      }

      setRealGrid(grid);

      // Perform forward FFT
      const { real, imag } = compute2DFFT(grid, size, false);
      setFftReal(real);
      setFftImag(imag);
    };
  }, [image]);

  useEffect(() => {
    if (!realGrid || !fftReal || !fftImag) return;
    drawOriginalSlice();
    drawFFTAndIFFT();
  }, [realGrid, fftReal, fftImag, masks, activeFilterTab, lowpassCutoff, highpassCutoff]);

  const drawOriginalSlice = () => {
    const canvas = origCanvasRef.current;
    if (!canvas || !realGrid) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const imgData = ctx.createImageData(size, size);
    for (let i = 0; i < size * size; i++) {
      const val = Math.min(255, Math.max(0, realGrid[i]));
      imgData.data[i * 4] = val;
      imgData.data[i * 4 + 1] = val;
      imgData.data[i * 4 + 2] = val;
      imgData.data[i * 4 + 3] = 255;
    }
    ctx.putImageData(imgData, 0, 0);
  };

  const drawFFTAndIFFT = () => {
    const fftCanvas = fftCanvasRef.current;
    if (!fftCanvas || !fftReal || !fftImag) return;
    const fftCtx = fftCanvas.getContext("2d");
    if (!fftCtx) return;

    // Shift the FFT real and imag components so DC is centered
    const shiftedReal = fftShift(fftReal, size);
    const shiftedImag = fftShift(fftImag, size);

    // Compute original power spectrum for visualization
    const power = new Float32Array(size * size);
    let maxLogVal = 0;
    for (let i = 0; i < size * size; i++) {
      const mag = Math.sqrt(shiftedReal[i] * shiftedReal[i] + shiftedImag[i] * shiftedImag[i]);
      // Logarithmic scaling is mandatory for diffraction spectral visual visibility
      power[i] = Math.log(1.0 + mag * 0.1);
      if (power[i] > maxLogVal) maxLogVal = power[i];
    }
    if (maxLogVal === 0) maxLogVal = 1;

    // Draw raw FFT power spectrum on FFT canvas
    const fftImgData = fftCtx.createImageData(size, size);
    for (let i = 0; i < size * size; i++) {
      const normVal = Math.floor((power[i] / maxLogVal) * 255);
      const val = Math.min(255, Math.max(0, normVal));
      fftImgData.data[i * 4] = val;
      // Style with a cool electron green lens tint!
      fftImgData.data[i * 4 + 1] = Math.min(255, val * 1.1);
      fftImgData.data[i * 4 + 2] = val * 0.8;
      fftImgData.data[i * 4 + 3] = 255;
    }
    fftCtx.putImageData(fftImgData, 0, 0);

    // Render filter overlays on FFT canvas
    drawFFTOverlays(fftCtx);

    // APPLY FREQUENCY FILTERS
    const filteredShiftedReal = new Float32Array(shiftedReal);
    const filteredShiftedImag = new Float32Array(shiftedImag);

    const half = size / 2;

    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const dx = x - half;
        const dy = y - half;
        const dist = Math.sqrt(dx * dx + dy * dy);
        const idx = y * size + x;

        let passFilter = true;

        if (activeFilterTab === "lowpass") {
          if (dist > lowpassCutoff) passFilter = false;
        } else if (activeFilterTab === "highpass") {
          if (dist < highpassCutoff) passFilter = false;
        } else if (activeFilterTab === "bandpass") {
          if (dist < highpassCutoff || dist > lowpassCutoff) passFilter = false;
        } else if (activeFilterTab === "masks") {
          // Check circular custom masks
          // Normalised x, y (0-1) matches center
          let insideMask = false;
          for (const mask of masks) {
            const mx = mask.x * size;
            const my = mask.y * size;
            const mRad = mask.radius * size;
            const dMask = Math.sqrt((x - mx) * (x - mx) + (y - my) * (y - my));

            if (dMask <= mRad) {
              if (mask.type === "pass") {
                insideMask = true;
              } else {
                insideMask = false; // Block
              }
            }
          }

          // If custom masks are enabled:
          // A standard "fringe filter" allows only components inside the highlighted circles to pass, and wipes all others!
          // We default to passFilter = false unless we match a PASS mask!
          if (masks.some((m) => m.type === "pass")) {
            passFilter = insideMask;
          }
        }

        if (!passFilter) {
          filteredShiftedReal[idx] = 0;
          filteredShiftedImag[idx] = 0;
        }
      }
    }

    // Double unshift to restore native FFT alignment before inverse operation
    const inverseReadyReal = fftShift(filteredShiftedReal, size);
    const inverseReadyImag = fftShift(filteredShiftedImag, size);

    // Compute IFFT
    const { real: recoveredReal } = compute2DFFT(inverseReadyReal, size, true);

    // Render rebuilt HRTEM fringes on IFFT canvas
    const ifftCanvas = ifftCanvasRef.current;
    if (!ifftCanvas) return;
    const ifftCtx = ifftCanvas.getContext("2d");
    if (!ifftCtx) return;

    const ifftImgData = ifftCtx.createImageData(size, size);
    // Find min/max for scale scaling
    let minRec = Infinity;
    let maxRec = -Infinity;
    for (let i = 0; i < size * size; i++) {
      if (recoveredReal[i] < minRec) minRec = recoveredReal[i];
      if (recoveredReal[i] > maxRec) maxRec = recoveredReal[i];
    }
    const rangeRec = maxRec - minRec || 1;

    for (let i = 0; i < size * size; i++) {
      const normVal = Math.floor(((recoveredReal[i] - minRec) / rangeRec) * 255);
      const val = Math.min(255, Math.max(0, normVal));
      ifftImgData.data[i * 4] = val;
      ifftImgData.data[i * 4 + 1] = val;
      ifftImgData.data[i * 4 + 2] = val;
      ifftImgData.data[i * 4 + 3] = 255;
    }
    ifftCtx.putImageData(ifftImgData, 0, 0);
  };

  const drawFFTOverlays = (ctx: CanvasRenderingContext2D) => {
    const half = size / 2;

    if (activeFilterTab === "lowpass") {
      ctx.beginPath();
      ctx.arc(half, half, lowpassCutoff, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(220, 38, 38, 0.6)";
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.fillStyle = "rgba(220, 38, 38, 0.04)";
      ctx.fill();
    } else if (activeFilterTab === "highpass") {
      ctx.beginPath();
      ctx.arc(half, half, highpassCutoff, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgba(59, 130, 246, 0.6)";
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.fillStyle = "rgba(59, 130, 246, 0.08)";
      ctx.fill();
    } else if (activeFilterTab === "bandpass") {
      ctx.beginPath();
      ctx.arc(half, half, lowpassCutoff, 0, 2 * Math.PI);
      ctx.arc(half, half, highpassCutoff, 0, 2 * Math.PI, true);
      ctx.strokeStyle = "rgba(168, 85, 247, 0.6)";
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.fillStyle = "rgba(168, 85, 247, 0.05)";
      ctx.fill();
    } else if (activeFilterTab === "masks") {
      // Draw circular masks
      for (const m of masks) {
        ctx.beginPath();
        ctx.arc(m.x * size, m.y * size, m.radius * size, 0, 2 * Math.PI);
        ctx.strokeStyle = m.type === "pass" ? "rgb(34, 197, 94)" : "rgb(239, 68, 68)";
        ctx.lineWidth = m.id === "mask_0" ? 1.5 : 2;
        ctx.stroke();

        ctx.fillStyle = m.type === "pass" ? "rgba(34, 197, 94, 0.12)" : "rgba(239, 68, 68, 0.15)";
        ctx.fill();

        ctx.fillStyle = "#FFFFFF";
        ctx.font = "9px monospace";
        ctx.fillText(
          m.id === "mask_0" ? "DF" : `${m.type === "pass" ? "P" : "B"}`,
          m.x * size - 3,
          m.y * size + 3
        );
      }
    }
  };

  const handleFFTCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (activeFilterTab !== "masks") return;
    const canvas = fftCanvasRef.current;
    if (!canvas) return;

    const rect = canvas.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const clickY = e.clientY - rect.top;

    const normalizedX = clickX / canvas.clientWidth;
    const normalizedY = clickY / canvas.clientHeight;

    // Create a new mask at this spot!
    const newMask: FFTMask = {
      id: `mask_${Date.now()}`,
      x: normalizedX,
      y: normalizedY,
      radius: 0.05, // default passing spot radius (about 12 px)
      type: "pass",
    };

    setMasks((prev) => [...prev, newMask]);
  };

  const clearMasks = () => {
    // Keep prime central spot DF
    setMasks([{ id: "mask_0", x: 0.5, y: 0.5, radius: 0.08, type: "pass" }]);
  };

  const removeMask = (id: string) => {
    if (id === "mask_0") return; // cannot erase central direct beam
    setMasks((prev) => prev.filter((m) => m.id !== id));
  };

  const updateMaskRadius = (id: string, newRad: number) => {
    setMasks((prev) => prev.map((m) => (m.id === id ? { ...m, radius: newRad } : m)));
  };

  return (
    <div className="prof-panel select-none h-full flex flex-col justify-between overflow-hidden">
      <div className="prof-panel-header px-3">
        <div className="flex items-center gap-1.5 font-bold">
          <Layers className="w-3.5 h-3.5 text-blue-500" />
          <span>HERRAMIENTA AVANZADA DE FILTRADO FFT / IFFT (HRTEM)</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-[10px] text-zinc-400 bg-zinc-950 border border-zinc-800 px-2.5 py-0.2 rounded font-mono font-bold">
            RESOLUCIÓN: 256 x 256
          </span>
        </div>
      </div>

      {!hasImage ? (
        <div className="flex-1 min-h-[300px] flex flex-col items-center justify-center p-8 text-center bg-zinc-950/20 text-zinc-500">
          <EyeOff className="w-10 h-10 mb-2.5 text-zinc-700" />
          <span className="text-[11.5px] font-bold text-zinc-400">No se ha cargado una imagen experimental</span>
          <p className="text-[10px] text-zinc-500 mt-1 max-w-sm font-medium">
            Para utilizar el análisis en el dominio de Fourier (FFT), carga una micrografía experimental en el panel de control lateral.
          </p>
        </div>
      ) : (
        <div className="flex-1 flex flex-col md:flex-row divide-y md:divide-y-0 md:divide-x divide-zinc-200 dark:divide-zinc-800">
          {/* Controls Bar */}
          <div className="w-full md:w-80 p-3 shrink-0 flex flex-col gap-4 bg-zinc-50 dark:bg-zinc-950/40">
            <div>
              <span className="text-[9px] font-bold uppercase tracking-widest text-[#64748b] dark:text-[#94a3b8]">
                Filtros en Dominio de Fourier
              </span>
              <div className="grid grid-cols-2 gap-1 mt-2">
                {(["masks", "lowpass", "highpass", "bandpass"] as const).map((tab) => (
                  <button
                    key={tab}
                    onClick={() => setActiveFilterTab(tab)}
                    className={`cursor-pointer text-[10px] py-1 px-1 rounded font-bold border text-center transition-all capitalize ${
                      activeFilterTab === tab
                        ? "bg-blue-600 border-blue-600 text-white shadow-xs"
                        : "bg-white dark:bg-zinc-900 border-zinc-200 dark:border-zinc-800 text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200"
                    }`}
                  >
                    {tab === "masks" ? "Máscaras" : tab}
                  </button>
                ))}
              </div>
            </div>

            {/* Sub-panels for filter parameters */}
            {(activeFilterTab === "lowpass" || activeFilterTab === "bandpass") && (
              <div className="space-y-1.5 p-2 bg-white dark:bg-zinc-900 rounded border border-zinc-200 dark:border-zinc-800">
                <div className="flex justify-between text-[10px] font-bold">
                  <span className="text-zinc-500 uppercase">Pasa-Bajas Cutoff</span>
                  <span className="font-mono text-blue-600 dark:text-blue-400">{lowpassCutoff} px</span>
                </div>
                <input
                  type="range"
                  min="5"
                  max="120"
                  value={lowpassCutoff}
                  onChange={(e) => setLowpassCutoff(parseInt(e.target.value))}
                  className="w-full h-1 appearance-none bg-zinc-200 dark:bg-zinc-700 cursor-pointer accent-blue-600"
                />
              </div>
            )}

            {(activeFilterTab === "highpass" || activeFilterTab === "bandpass") && (
              <div className="space-y-1.5 p-2 bg-white dark:bg-zinc-900 rounded border border-zinc-200 dark:border-zinc-800">
                <div className="flex justify-between text-[10px] font-bold">
                  <span className="text-zinc-500 uppercase">Pasa-Altas Cutoff</span>
                  <span className="font-mono text-blue-600 dark:text-blue-400">{highpassCutoff} px</span>
                </div>
                <input
                  type="range"
                  min="1"
                  max="40"
                  value={highpassCutoff}
                  onChange={(e) => setHighpassCutoff(parseInt(e.target.value))}
                  className="w-full h-1 appearance-none bg-zinc-200 dark:bg-zinc-700 cursor-pointer accent-blue-600"
                />
              </div>
            )}

            {activeFilterTab === "masks" && (
              <div className="flex-1 flex flex-col gap-2 min-h-[160px]">
                <div className="flex items-center justify-between text-[9px] text-zinc-500 font-bold uppercase tracking-widest">
                  <span>MÁSCARAS ({masks.length})</span>
                  <button
                    onClick={clearMasks}
                    className="cursor-pointer text-blue-600 dark:text-blue-400 hover:underline"
                  >
                    RESTABLECER
                  </button>
                </div>

                <div className="flex-1 bg-white dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800 rounded p-1.5 overflow-y-auto max-h-[180px] space-y-1">
                  {masks.map((mask) => (
                    <div
                      key={mask.id}
                      className="flex items-center justify-between px-2 py-1 rounded bg-zinc-50 dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 text-[10px] font-bold"
                    >
                      <div className="flex items-center gap-1.5">
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            mask.type === "pass" ? "bg-emerald-500 animate-pulse" : "bg-red-500"
                          }`}
                        />
                        <span className="font-mono text-zinc-600 dark:text-zinc-350">
                          {mask.id === "mask_0" ? "Haz Directo (DF)" : `Spot Pass`}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        <div className="flex items-center gap-1">
                          <span className="text-[8px] text-zinc-400 uppercase font-mono">Radio:</span>
                          <input
                            type="range"
                            min="0.01"
                            max="0.25"
                            step="0.01"
                            value={mask.radius}
                            onChange={(e) => updateMaskRadius(mask.id, parseFloat(e.target.value))}
                            className="w-10 h-1 appearance-none bg-zinc-200 dark:bg-zinc-700 cursor-pointer accent-blue-600"
                          />
                        </div>
                        {mask.id !== "mask_0" && (
                          <button
                            onClick={() => removeMask(mask.id)}
                            className="text-zinc-400 hover:text-red-500 p-0.5 cursor-pointer"
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                  <p className="text-[8.5px] text-zinc-400 text-center py-1.5 bg-zinc-50/50 dark:bg-zinc-900/10 border border-dashed border-zinc-200 dark:border-zinc-800 rounded font-medium leading-tight">
                    Haz clic en los electron-spots brillantes de la imagen FFT para añadir filtros de paso selectivos
                  </p>
                </div>
              </div>
            )}

            <div className="pt-2 border-t border-zinc-200 dark:border-zinc-800 flex items-start gap-1 text-[9.5px] text-zinc-500 dark:text-zinc-400 font-medium">
              <Zap className="w-3.5 h-3.5 text-amber-500 shrink-0 mt-0.5" />
              <span>
                El filtrado IFFT reconstruye las franjas de red espacial real bloqueando o dejando pasar frecuencias específicas.
              </span>
            </div>
          </div>

          {/* Grids Display Area */}
          <div className="flex-1 p-3 grid grid-cols-1 sm:grid-cols-3 gap-3 items-center justify-center bg-zinc-50 dark:bg-zinc-950/20">
            {/* Box 1: Grayscale Original */}
            <div className="flex flex-col items-center gap-1.5">
              <span className="text-[9px] font-bold text-zinc-400 tracking-wider uppercase">
                1. Micrografía HRTEM
              </span>
              <div className="border border-zinc-200 dark:border-zinc-800 rounded overflow-hidden shadow-sm relative bg-black w-[170px] h-[170px]">
                <canvas ref={origCanvasRef} width={size} height={size} className="w-full h-full block" />
                <span className="absolute bottom-1.5 left-1.5 text-[8px] font-mono text-zinc-200 bg-zinc-950/80 px-1 rounded font-bold">
                  ESPACIO REAL
                </span>
              </div>
            </div>

            {/* Box 2: Fourier Power Spectrum */}
            <div className="flex flex-col items-center gap-1.5">
              <span className="text-[9px] font-bold text-zinc-400 tracking-wider uppercase">
                2. Espectro FFT (Lattice)
              </span>
              <div className="border border-zinc-200 dark:border-zinc-800 rounded overflow-hidden shadow-sm relative bg-black w-[170px] h-[170px]">
                <canvas
                  ref={fftCanvasRef}
                  width={size}
                  height={size}
                  onClick={handleFFTCanvasClick}
                  className="w-full h-full block cursor-crosshair"
                />
                <span className="absolute bottom-1.5 left-1.5 text-[8px] font-mono text-zinc-200 bg-zinc-950/80 px-1 rounded font-bold">
                  ESPACIO RECÍPROCO
                </span>
              </div>
            </div>

            {/* Box 3: Inverse FFT (IFFT) Lattice Reconstruction */}
            <div className="flex flex-col items-center gap-1.5">
              <span className="text-[9px] font-bold text-zinc-400 tracking-wider uppercase">
                3. Reconstrucción IFFT
              </span>
              <div className="border border-blue-300 dark:border-blue-900 rounded overflow-hidden shadow-sm relative bg-black w-[170px] h-[170px]">
                <canvas ref={ifftCanvasRef} width={size} height={size} className="w-full h-full block" />
                <span className="absolute bottom-1.5 left-1.5 text-[8px] font-mono text-blue-200 bg-blue-950/80 px-1 rounded font-bold">
                  RED SIMULADA
                </span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
