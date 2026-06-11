import React, { useRef, useState, useEffect } from "react";
import { CrystallineStructure, Reflection, SimulationParams } from "../types";
import { generateReflections, getPowderReflections, Matrix3x3, Vector3 } from "../utils/crystalMath";
import { Sliders, Download, Eye } from "lucide-react";

interface ComponentProps {
  structure: CrystallineStructure | null;
  params: SimulationParams;
}

export default function XRDProfileChart({ structure, params }: ComponentProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [broadening, setBroadening] = useState<number>(0.25); // peak FWHM broadening in degrees 2-theta
  const [selectedPeak, setSelectedPeak] = useState<Reflection | null>(null);

  const [xrdPeaks, setXrdPeaks] = useState<Reflection[]>([]);

  // Boundaries for plotting
  const min2Theta = 10;
  const max2Theta = 90;

  useEffect(() => {
    if (!structure) {
      setXrdPeaks([]);
      return;
    }

    // Generate reflections for XRD
    // Uses structural parameters but with a typical XRD radiation source (Cu-Ka = 1.54056 Å by default)
    const lambda = params.xrdWavelength;

    // Get all hkl planes
    const limit = 5;
    const items: Reflection[] = [];
    const visited = new Set<string>();

    // Manual sweep to calculate actual XRD 2-theta positions
    const { directTransform } = requireLatticeTransform(structure);
    const { reciprocalTransform } = requireReciprocalLatticeTransform(structure);

    for (let h = -limit; h <= limit; h++) {
      for (let k = -limit; k <= limit; k++) {
        for (let l = -limit; l <= limit; l++) {
          if (h === 0 && k === 0 && l === 0) continue;

          // d-spacing
          const gVec = mulVector(reciprocalTransform, [h, k, l]);
          const gNorm = normVector(gVec);
          if (gNorm <= 0) continue;
          const d = 1.0 / gNorm;

          if (d < 1.0) continue; // lower indexing threshold for typical XRD sweeps

          const key = `${h},${k},${l}`;
          if (visited.has(key)) continue;
          visited.add(key);

          // Bragg angle: sin(theta) = lambda / (2d)
          const sinTheta = lambda / (2 * d);
          if (sinTheta >= 1.0) continue;
          const theta = Math.asin(sinTheta);
          const twoThetaDeg = rad2deg(2 * theta);

          if (twoThetaDeg < min2Theta || twoThetaDeg > max2Theta) continue;

          // Compute powder intensity: I = |F_xrd(hkl)|^2
          // We can approximate factor using atomic numbers (Z)
          let fReal = 0;
          let fImag = 0;
          const s = sinTheta / lambda; // scattering factor

          for (const atom of structure.atoms) {
            // Decays based on Z
            const zVal = atom.symbol === "H" ? 1 : atom.symbol === "O" ? 8 : atom.symbol === "Si" ? 14 : atom.symbol === "Fe" ? 26 : atom.symbol === "Au" ? 79 : 22;
            const f_atom = Math.max(1, zVal * Math.exp(-1.5 * s * s)); // XRD scattering factor decay shape
            const phase = 2 * Math.PI * (h * atom.x + k * atom.y + l * atom.z);
            fReal += f_atom * Math.cos(phase);
            fImag += f_atom * Math.sin(phase);
          }

          const structureFactorSq = fReal * fReal + fImag * fImag;

          // Lorentz Polarization Factor for XRD powder samples:
          // LP = (1 + cos^2(2*theta)) / (sin^2(theta) * cos(theta))
          const lp = (1 + Math.pow(Math.cos(2 * theta), 2)) / (Math.pow(Math.sin(theta), 2) * Math.cos(theta));

          const rawIntensity = structureFactorSq * lp;

          items.push({
            h, k, l, d,
            intensity: rawIntensity,
            theta: twoThetaDeg, // Store 2-theta in degrees here!
            radius: 0, radiusPx: 0, structureFactorReal: fReal, structureFactorImag: fImag,
            multiplicity: 1
          } as unknown as Reflection);
        }
      }
    }

    // Merge equivalents
    const grouped: Reflection[] = [];
    for (const ref of items) {
      const existing = grouped.find((x) => Math.abs(x.theta - ref.theta) < 0.15);
      if (existing) {
        existing.intensity += ref.intensity;
        existing.multiplicity += 1;
      } else {
        grouped.push({ ...ref });
      }
    }

    // Normalize intensity
    const maxI = Math.max(...grouped.map((g) => g.intensity), 1e-4);
    grouped.forEach((g) => {
      g.intensity = (g.intensity / maxI) * 100;
    });

    setXrdPeaks(grouped.sort((a, b) => a.theta - b.theta));
  }, [structure, params]);

  useEffect(() => {
    drawXRDProfile();
  }, [xrdPeaks, broadening, selectedPeak]);

  const drawXRDProfile = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Grid margins
    const mLeft = 52;
    const mRight = 24;
    const mTop = 28;
    const mBottom = 40;

    const graphW = w - mLeft - mRight;
    const graphH = h - mTop - mBottom;

    // Background gradient
    ctx.fillStyle = "#fafafa";
    ctx.fillRect(mLeft, mTop, graphW, graphH);

    // Draw grid lines
    ctx.strokeStyle = "rgba(0,0,0,0.06)";
    ctx.lineWidth = 1;
    // vertical grid values (2-theta)
    for (let t = min2Theta + 10; t <= max2Theta; t += 10) {
      const x = mLeft + ((t - min2Theta) / (max2Theta - min2Theta)) * graphW;
      ctx.beginPath();
      ctx.moveTo(x, mTop);
      ctx.lineTo(x, mTop + graphH);
      ctx.stroke();

      // Tick text
      ctx.fillStyle = "#71717a";
      ctx.font = "9px monospace";
      ctx.textAlign = "center";
      ctx.fillText(`${t}°`, x, mTop + graphH + 14);
    }

    // horizontal grid values (Intensity)
    for (let i = 20; i <= 100; i += 20) {
      const y = mTop + graphH - (i / 100) * graphH;
      ctx.beginPath();
      ctx.moveTo(mLeft, y);
      ctx.lineTo(mLeft + graphW, y);
      ctx.stroke();

      // Intensity text
      ctx.fillStyle = "#71717a";
      ctx.font = "9px monospace";
      ctx.textAlign = "right";
      ctx.fillText(`${i}`, mLeft - 8, y + 3);
    }

    // Draw borders of chart
    ctx.strokeStyle = "#e4e4e7";
    ctx.lineWidth = 1.2;
    ctx.strokeRect(mLeft, mTop, graphW, graphH);

    // Standard axes indicators
    ctx.fillStyle = "#4b5563";
    ctx.font = "11px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText("Ángulo de Difracción 2θ (Grados)", mLeft + graphW / 2, mTop + graphH + 34);

    ctx.save();
    ctx.translate(14, mTop + graphH / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.fillText("Intensidad Relativa (%)", 0, 0);
    ctx.restore();

    if (xrdPeaks.length === 0) {
      ctx.fillStyle = "#a1a1aa";
      ctx.font = "13px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("No hay fases cargadas para simulación XRD", mLeft + graphW / 2, mTop + graphH / 2);
      return;
    }

    // GENERATE CONTINUOUS LORENTZIAN DIFFRACTOGRAM PROFILE CURVE
    // For each x value on screen, calculate simulated broadened intensity
    const points: { x: number; y: number }[] = [];
    const stepSizeTheta = (max2Theta - min2Theta) / graphW;

    for (let px = 0; px <= graphW; px++) {
      const t = min2Theta + px * stepSizeTheta;

      // Sum contributions of all Bragg diffraction peaks
      let intensityAtT = 0;

      for (const peak of xrdPeaks) {
        // peak.theta is the peak 2-theta location
        const distance = t - peak.theta;
        // Lorentzian broadening formula: L(t) = I / (1 + (t - t0)^2 / (FWHM/2)^2)
        const halfWidth = broadening / 2;
        const lorentzFactor = 1.0 / (1.0 + (distance * distance) / (halfWidth * halfWidth));
        intensityAtT += peak.intensity * lorentzFactor;
      }

      // bound intensity maximum cap to prevent scaling errors
      const displayI = Math.min(105, intensityAtT);

      const py = mTop + graphH - (displayI / 100) * graphH;
      points.push({ x: mLeft + px, y: py });
    }

    // Render continuous smoothed diffractogram curve line
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i++) {
      ctx.lineTo(points[i].x, points[i].y);
    }
    ctx.strokeStyle = "rgb(79, 70, 229)"; // Beautiful indigo diffractogram
    ctx.lineWidth = 1.8;
    ctx.stroke();

    // Fill area below profile gradient
    ctx.lineTo(mLeft + graphW, mTop + graphH);
    ctx.lineTo(mLeft, mTop + graphH);
    ctx.closePath();
    const areaGrad = ctx.createLinearGradient(0, mTop, 0, mTop + graphH);
    areaGrad.addColorStop(0, "rgba(99, 102, 241, 0.22)");
    areaGrad.addColorStop(1, "rgba(99, 102, 241, 0)");
    ctx.fillStyle = areaGrad;
    ctx.fill();

    // DRAW DISCRETE BRAGG DIFFRACTION BAR STICKS ON TOP
    xrdPeaks.forEach((p) => {
      const px = mLeft + ((p.theta - min2Theta) / (max2Theta - min2Theta)) * graphW;
      const py = mTop + graphH - (p.intensity / 100) * graphH;

      ctx.beginPath();
      ctx.moveTo(px, mTop + graphH);
      ctx.lineTo(px, py);
      ctx.strokeStyle = "rgba(249, 115, 22, 0.45)"; // discrete peak indicators (orange)
      ctx.lineWidth = 1;
      ctx.stroke();

      // Little indicator dot on peak tip
      ctx.beginPath();
      ctx.arc(px, py, 2.5, 0, 2 * Math.PI);
      ctx.fillStyle = "rgb(249, 115, 22)";
      ctx.fill();

      // Label major principal peaks
      if (p.intensity > 35) {
        ctx.fillStyle = "rgba(249, 115, 22, 0.85)";
        ctx.font = "bold 8px monospace";
        ctx.textAlign = "center";
        ctx.fillText(`(${p.h}${p.k}${p.l})`, px, py - 6);
      }
    });

    // Label selected peaks metadata if active
    if (selectedPeak) {
      const px = mLeft + ((selectedPeak.theta - min2Theta) / (max2Theta - min2Theta)) * graphW;
      const py = mTop + graphH - (selectedPeak.intensity / 100) * graphH;

      ctx.beginPath();
      ctx.arc(px, py, 6, 0, 2 * Math.PI);
      ctx.strokeStyle = "rgb(220, 38, 38)"; // red target ring
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
  };

  // Helper crystallographic math functions
  const requireLatticeTransform = (s: CrystallineStructure) => {
    const alpha = (s.alpha * Math.PI) / 180;
    const beta = (s.beta * Math.PI) / 180;
    const gamma = (s.gamma * Math.PI) / 180;
    const cosA = Math.cos(alpha);
    const cosB = Math.cos(beta);
    const cosG = Math.cos(gamma);
    const sinG = Math.sin(gamma);

    const term = 1 - cosA * cosA - cosB * cosB - cosG * cosG + 2 * cosA * cosB * cosG;
    const vol = s.a * s.b * s.c * Math.sqrt(Math.max(0, term));

    const directTransform: Matrix3x3 = [
      [s.a, s.b * cosG, s.c * cosB],
      [0, s.b * sinG, s.c * (cosA - cosB * cosG) / sinG],
      [0, 0, s.c * Math.sqrt(Math.max(0, term)) / sinG],
    ];
    return { directTransform };
  };

  const requireReciprocalLatticeTransform = (s: CrystallineStructure) => {
    const { directTransform } = requireLatticeTransform(s);
    const invDirect = invertMatrix(directTransform);
    const reciprocalTransform = transposeMatrix(invDirect);
    return { reciprocalTransform };
  };

  // Matrix vector multiplication copy
  const mulVector = (M: Matrix3x3, v: Vector3): Vector3 => [
    M[0][0]*v[0] + M[0][1]*v[1] + M[0][2]*v[2],
    M[1][0]*v[0] + M[1][1]*v[1] + M[1][2]*v[2],
    M[2][0]*v[0] + M[2][1]*v[1] + M[2][2]*v[2],
  ];

  const normVector = (v: Vector3) => Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
  const rad2deg = (r: number) => (r * 180) / Math.PI;

  const invertMatrix = (M: Matrix3x3): Matrix3x3 => {
    const det = M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) -
                M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0]) +
                M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
    if (Math.abs(det) < 1e-12) return [[1,0,0],[0,1,0],[0,0,1]];
    const invDet = 1.0 / det;
    return [
      [
        (M[1][1]*M[2][2] - M[1][2]*M[2][1]) * invDet,
        (M[0][2]*M[2][1] - M[0][1]*M[2][2]) * invDet,
        (M[0][1]*M[1][2] - M[0][2]*M[1][1]) * invDet,
      ],
      [
        (M[1][2]*M[2][0] - M[1][0]*M[2][2]) * invDet,
        (M[0][0]*M[2][2] - M[0][2]*M[2][0]) * invDet,
        (M[0][2]*M[1][0] - M[0][0]*M[1][2]) * invDet,
      ],
      [
        (M[1][0]*M[2][1] - M[1][1]*M[2][0]) * invDet,
        (M[0][1]*M[2][0] - M[0][0]*M[2][1]) * invDet,
        (M[0][0]*M[1][1] - M[0][1]*M[1][0]) * invDet,
      ]
    ];
  };

  const transposeMatrix = (M: Matrix3x3): Matrix3x3 => [
    [M[0][0], M[1][0], M[2][0]],
    [M[0][1], M[1][1], M[2][1]],
    [M[0][2], M[1][2], M[2][2]],
  ];

  // Export XRD data table to CSV format
  const exportToCSV = () => {
    if (xrdPeaks.length === 0) return;
    const header = "h,k,l,2-Theta (deg),d-spacing (A),Relative Intensity (%)\n";
    const data = xrdPeaks
      .map((p) => `${p.h},${p.k},${p.l},${p.theta.toFixed(3)},${p.d.toFixed(4)},${p.intensity.toFixed(1)}`)
      .join("\n");
    const blob = new Blob([header + data], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `XRD_difractograma_${structure?.name || "fase"}.csv`;
    link.click();
  };

  return (
    <div id="xrd-profile-panel" className="prof-panel select-none h-full flex flex-col justify-between">
      <div className="prof-panel-header px-3">
        <div className="flex items-center gap-1.5 font-bold">
          <Sliders className="w-3.5 h-3.5 text-zinc-650 dark:text-zinc-350" />
          <span>SIMULADOR DE DIFRACCIÓN DE RAYOS X (XRD)</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5 border border-zinc-200 dark:border-zinc-800 px-2 py-0.5 rounded bg-zinc-50 dark:bg-zinc-950 font-mono text-[9px]">
            <span className="text-zinc-400">FWHM:</span>
            <input
              type="range"
              min="0.08"
              max="1.20"
              step="0.02"
              value={broadening}
              onChange={(e) => setBroadening(parseFloat(e.target.value))}
              className="w-14 h-1 appearance-none bg-zinc-200 dark:bg-zinc-700 cursor-pointer accent-blue-600 rounded"
            />
            <span className="font-bold text-blue-600 dark:text-blue-400 w-10 text-right">
              {broadening.toFixed(2)}°
            </span>
          </div>
          <button
            onClick={exportToCSV}
            disabled={xrdPeaks.length === 0}
            className="cursor-pointer bg-white border border-zinc-200 hover:bg-zinc-50 dark:bg-zinc-900 dark:border-zinc-850 text-zinc-600 dark:text-zinc-300 text-[10px] h-[22px] px-2 rounded flex items-center gap-1 transition-all font-bold"
          >
            <Download className="w-3 h-3" /> EXPORTAR CSV
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-[220px] p-2 flex items-center justify-center bg-zinc-50 dark:bg-zinc-950 relative">
        <canvas ref={canvasRef} width={500} height={252} className="w-full h-full block" />
        {structure && (
          <div className="absolute top-2 right-4 text-[9px] font-mono text-zinc-400 text-right">
            <span>Cu K-α (λ = {params.xrdWavelength.toFixed(4)} Å)</span>
          </div>
        )}
      </div>

      {xrdPeaks.length > 0 && (
        <div className="p-2 border-t border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/40">
          <span className="text-[9px] font-bold text-zinc-400 uppercase tracking-widest block mb-1.5">
            PICOS DE DIFRACCIÓN XRD PRINCIPALES ({xrdPeaks.length})
          </span>
          <div className="flex items-center gap-1 overflow-x-auto pb-1 max-w-full">
            {xrdPeaks.slice(0, 10).map((peak, idx) => (
              <button
                key={idx}
                onClick={() => setSelectedPeak(selectedPeak?.theta === peak.theta ? null : peak)}
                className={`py-1 px-2.5 text-[10px] border rounded flex flex-col items-center shrink-0 transition-all cursor-pointer font-bold ${
                  selectedPeak?.theta === peak.theta
                    ? "bg-blue-50 border-blue-400 text-blue-700 dark:bg-blue-950/40 dark:border-blue-800 dark:text-blue-400"
                    : "bg-white border-zinc-200 text-zinc-600 dark:bg-zinc-900 dark:border-zinc-800 dark:text-zinc-400 hover:border-blue-500"
                }`}
              >
                <span>({peak.h}{peak.k}{peak.l})</span>
                <span className="font-mono text-[9px] mt-0.5">2θ: {peak.theta.toFixed(1)}°</span>
                <span className="font-mono text-[8.5px] text-zinc-400 font-normal">I: {peak.intensity.toFixed(0)}%</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
