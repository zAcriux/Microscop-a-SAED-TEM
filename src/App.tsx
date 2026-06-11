import React, { useState, useEffect, useMemo, useRef } from "react";
import {
  CrystallineStructure,
  SimulationParams,
  ExperimentalImage,
  Measurement,
  ExperimentalPeak,
  Reflection,
  PhaseCandidate,
  CrystalSystem,
} from "./types";
import {
  parseCIF,
  calculateElectronWavelength,
  generateReflections,
  getPowderReflections,
  getSingleCrystalSpots,
  phaseIdentification,
} from "./utils/crystalMath";

// Custom premium components
import DiffractionPatternCanvas from "./components/DiffractionPatternCanvas";
import XRDProfileChart from "./components/XRDProfileChart";
import FFTAnalysisPanel from "./components/FFTAnalysisPanel";

// Lucide-icons
import {
  Atom,
  Sliders,
  Table,
  Upload,
  Layers,
  Settings,
  Flame,
  Search,
  Database,
  Grid,
  TrendingUp,
  CircleDot,
  FileSpreadsheet,
  Download,
  Info,
  ChevronRight,
  Sparkles,
  RefreshCw,
  Sun,
  Moon,
  Plus,
} from "lucide-react";

// Pre-loaded crystal structures
const PRELOADED_PHASES: CrystallineStructure[] = [
  {
    id: "si_fcc",
    name: "Silicio (Si)",
    spaceGroup: "Fd-3m (227)",
    system: "cubic",
    a: 5.431,
    b: 5.431,
    c: 5.431,
    alpha: 90,
    beta: 90,
    gamma: 90,
    atoms: [
      { id: "si_1", symbol: "Si", x: 0, y: 0, z: 0 },
      { id: "si_2", symbol: "Si", x: 0.5, y: 0.5, z: 0 },
      { id: "si_3", symbol: "Si", x: 0.5, y: 0, z: 0.5 },
      { id: "si_4", symbol: "Si", x: 0, y: 0.5, z: 0.5 },
      { id: "si_5", symbol: "Si", x: 0.25, y: 0.25, z: 0.25 },
      { id: "si_6", symbol: "Si", x: 0.75, y: 0.75, z: 0.25 },
      { id: "si_7", symbol: "Si", x: 0.75, y: 0.25, z: 0.75 },
      { id: "si_8", symbol: "Si", x: 0.25, y: 0.75, z: 0.75 },
    ],
    density: 2.33,
    formula: "Si",
  },
  {
    id: "au_fcc",
    name: "Oro (Au)",
    spaceGroup: "Fm-3m (225)",
    system: "cubic",
    a: 4.078,
    b: 4.078,
    c: 4.078,
    alpha: 90,
    beta: 90,
    gamma: 90,
    atoms: [
      { id: "au_1", symbol: "Au", x: 0, y: 0, z: 0 },
      { id: "au_2", symbol: "Au", x: 0.5, y: 0.5, z: 0 },
      { id: "au_3", symbol: "Au", x: 0.5, y: 0, z: 0.5 },
      { id: "au_4", symbol: "Au", x: 0, y: 0.5, z: 0.5 },
    ],
    density: 19.3,
    formula: "Au",
  },
  {
    id: "fe_bcc",
    name: "Hierro (Fe-BCC)",
    spaceGroup: "Im-3m (229)",
    system: "cubic",
    a: 2.866,
    b: 2.866,
    c: 2.866,
    alpha: 90,
    beta: 90,
    gamma: 90,
    atoms: [
      { id: "fe_1", symbol: "Fe", x: 0, y: 0, z: 0 },
      { id: "fe_2", symbol: "Fe", x: 0.5, y: 0.5, z: 0.5 },
    ],
    density: 7.87,
    formula: "Fe",
  },
  {
    id: "tio2_rutile",
    name: "Rutilo (TiO2)",
    spaceGroup: "P42/mnm (136)",
    system: "tetragonal",
    a: 4.593,
    b: 4.593,
    c: 2.959,
    alpha: 90,
    beta: 90,
    gamma: 90,
    atoms: [
      { id: "ti_1", symbol: "Ti", x: 0, y: 0, z: 0 },
      { id: "ti_2", symbol: "Ti", x: 0.5, y: 0.5, z: 0.5 },
      { id: "o_1", symbol: "O", x: 0.305, y: 0.305, z: 0 },
      { id: "o_2", symbol: "O", x: 0.695, y: 0.695, z: 0 },
      { id: "o_3", symbol: "O", x: 0.195, y: 0.805, z: 0.5 },
      { id: "o_4", symbol: "O", x: 0.805, y: 0.195, z: 0.5 },
    ],
    density: 4.25,
    formula: "TiO2",
  },
  {
    id: "c_graphite",
    name: "Grafito (C)",
    spaceGroup: "P63/mmc (194)",
    system: "hexagonal",
    a: 2.464,
    b: 2.464,
    c: 6.711,
    alpha: 90,
    beta: 90,
    gamma: 120,
    atoms: [
      { id: "c_1", symbol: "C", x: 0, y: 0, z: 0 },
      { id: "c_2", symbol: "C", x: 0, y: 0, z: 0.5 },
      { id: "c_3", symbol: "C", x: 0.333, y: 0.667, z: 0 },
      { id: "c_4", symbol: "C", x: 0.667, y: 0.333, z: 0.5 },
    ],
    density: 2.26,
    formula: "C",
  },
  {
    id: "nacl_rock",
    name: "Sal Gema (NaCl)",
    spaceGroup: "Fm-3m (225)",
    system: "cubic",
    a: 5.64,
    b: 5.64,
    c: 5.64,
    alpha: 90,
    beta: 90,
    gamma: 90,
    atoms: [
      { id: "na_1", symbol: "Na", x: 0, y: 0, z: 0 },
      { id: "na_2", symbol: "Na", x: 0.5, y: 0.5, z: 0 },
      { id: "na_3", symbol: "Na", x: 0.5, y: 0, z: 0.5 },
      { id: "na_4", symbol: "Na", x: 0, y: 0.5, z: 0.5 },
      { id: "cl_1", symbol: "Cl", x: 0.5, y: 0, z: 0 },
      { id: "cl_2", symbol: "Cl", x: 0, y: 0.5, z: 0 },
      { id: "cl_3", symbol: "Cl", x: 0, y: 0, z: 0.5 },
      { id: "cl_4", symbol: "Cl", x: 0.5, y: 0.5, z: 0.5 },
    ],
    density: 2.16,
    formula: "NaCl",
  },
];

export default function App() {
  const [theme, setTheme] = useState<"light" | "dark">("dark");
  const [structures, setStructures] = useState<CrystallineStructure[]>(PRELOADED_PHASES);
  const [activeStructureId, setActiveStructureId] = useState<string>(PRELOADED_PHASES[0].id);

  // Active workspace tab
  const [activeTab, setActiveTab] = useState<"tem" | "xrd" | "fft">("tem");

  // Interactive Tools selection
  const [activeTool, setActiveTool] = useState<"select" | "measure_dist" | "measure_angle" | "add_peak">("select");

  // Simulation settings
  const [params, setParams] = useState<SimulationParams>({
    mode: "rings",
    voltage: 200, // kV
    cameraLength: 350, // mm
    detectorSize: 80, // mm
    resolution: 512, // px
    relativeIntensity: 1.5,
    sampleThickness: 15, // nm (Ewald Sphere relrod error)
    zoneAxis: [0, 0, 1],
    xrdWavelength: 1.5406, // Cu-Ka
    instrumentalBroadening: 0.25,
    showLabels: true,
    minDSpacing: 0.8,
  });

  // Experimental backdrop settings
  const [expImage, setExpImage] = useState<ExperimentalImage>({
    name: "Ninguna",
    scale: 0.75, // background scale multiplier
    rawWidth: 0,
    rawHeight: 0,
    offsetX: 0,
    offsetY: 0,
    rotation: 0,
    contrast: 1.0,
    brightness: 0,
    alpha: 0.5,
    inverted: false,
    calibrated: false,
  });

  const fileInputRef = useRef<HTMLInputElement>(null);
  const cifInputRef = useRef<HTMLInputElement>(null);

  // User measurements and indexed peaks
  const [measurements, setMeasurements] = useState<Measurement[]>([]);
  const [expPeaks, setExpPeaks] = useState<ExperimentalPeak[]>([]);

  // Selected hover reflection tooltip
  const [hoveredRef, setHoveredRef] = useState<Reflection | null>(null);

  // Auto-calculated active structure matching
  const activeStructure = useMemo(() => {
    return structures.find((s) => s.id === activeStructureId) || null;
  }, [structures, activeStructureId]);

  // Compute calculated hkl rings listing
  const currentTableRings = useMemo(() => {
    if (!activeStructure) return [];
    const reflections = generateReflections(
      activeStructure,
      params.minDSpacing,
      params.voltage,
      params.cameraLength,
      params.detectorSize,
      params.resolution
    );
    return getPowderReflections(reflections);
  }, [activeStructure, params]);

  // Automated Phase candidate analysis based on clicked experimental d-spacings
  const candidatePhases = useMemo(() => {
    if (expPeaks.length === 0) return [];
    return phaseIdentification(expPeaks, structures, params);
  }, [expPeaks, structures, params]);

  // Apply a suggested candidate phase as the primary direct simulation model
  const applyCandidatePhase = (candidate: PhaseCandidate) => {
    setActiveStructureId(candidate.structureId);
  };

  // Drag-and-drop experimental image handling
  const handleImageUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const dataUrl = event.target?.result as string;
      const img = new Image();
      img.src = dataUrl;
      img.onload = () => {
        setExpImage({
          name: file.name,
          dataUrl,
          scale: 1.0,
          rawWidth: img.width,
          rawHeight: img.height,
          offsetX: 0,
          offsetY: 0,
          rotation: 0,
          contrast: 1.0,
          brightness: 0,
          alpha: 0.6,
          inverted: false,
          calibrated: true,
        });
      };
    };
    reader.readAsDataURL(file);
  };

  // Upload custom CIF files
  const handleCIFUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      const reader = new FileReader();
      reader.onload = (event) => {
        const text = event.target?.result as string;
        try {
          const loaded = parseCIF(text, file.name);
          // Insert into states
          setStructures((prev) => {
            const index = prev.findIndex((p) => p.name.toLowerCase() === loaded.name.toLowerCase());
            if (index !== -1) {
              const updated = [...prev];
              updated[index] = loaded;
              return updated;
            }
            return [...prev, loaded];
          });
          setActiveStructureId(loaded.id);
        } catch (err) {
          alert(`Error al procesar el archivo CIF: ${file.name}. Asegúrese que el formato sea válido.`);
        }
      };
      reader.readAsText(file);
    }
  };

  // Export results to CSV helper
  const handleCSVExport = () => {
    if (currentTableRings.length === 0) return;
    const headers = "h,k,l,d-spacing (A),Radius (mm),Relative Intensity (%)\n";
    const data = currentTableRings
      .map((r) => `${r.h},${r.k},${r.l},${r.d.toFixed(4)},${r.radius.toFixed(2)},${r.intensity.toFixed(1)}`)
      .join("\n");

    const blob = new Blob([headers + data], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `Indexacion_${activeStructure?.name || "fase"}.csv`;
    link.click();
  };

  // Remove a crystal phase
  const removeStructure = (id: string) => {
    if (structures.length <= 1) {
      alert("La aplicación debe contener al menos una estructura cristalina.");
      return;
    }
    setStructures((prev) => prev.filter((s) => s.id !== id));
    if (activeStructureId === id) {
      const remaining = structures.filter((s) => s.id !== id);
      setActiveStructureId(remaining[0].id);
    }
  };

  // Helper theme toggle class modification
  useEffect(() => {
    const root = window.document.documentElement;
    if (theme === "dark") {
      root.classList.add("dark");
    } else {
      root.classList.remove("dark");
    }
  }, [theme]);

  return (
    <div className="min-h-screen flex flex-col bg-zinc-50 dark:bg-zinc-950 text-zinc-800 dark:text-zinc-100 transition-colors duration-200 font-sans antialiased text-xs">
      {/* HEADER SECTION - Professional Polish Premium Slate */}
      <header className="h-12 bg-[#1e293b] border-b border-[#0f172a] flex items-center justify-between px-4 text-white shrink-0 select-none shadow-[0_2px_4px_rgba(0,0,0,0.15)] z-20">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-[#2563eb] rounded flex items-center justify-center shadow-inner">
            <Atom className="w-4.5 h-4.5 text-white rotate-12" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-bold text-sm tracking-widest text-white uppercase font-sans">CRYSTAL-X</span>
              <span className="text-[10px] text-zinc-400 font-bold px-1.5 py-0.2 bg-zinc-900/60 rounded">DIFFRACTOTEM</span>
            </div>
            <p className="text-[9.5px] text-zinc-450 font-normal mt-[-2px] tracking-wide">
              Análisis de Difracción TEM / SAED y XRD
            </p>
          </div>
        </div>

        {/* Workspace selector inside header */}
        <div className="flex items-center gap-0.5 bg-[#0f172a]/70 p-0.5 rounded border border-zinc-750/30">
          <button
            onClick={() => setActiveTab("tem")}
            className={`cursor-pointer px-3 py-1 rounded text-[11px] font-bold tracking-wide transition-all duration-100 gap-1.5 flex items-center ${
              activeTab === "tem"
                ? "bg-[#2563eb] text-white shadow-sm"
                : "text-zinc-400 hover:text-white hover:bg-zinc-800/40"
            }`}
          >
            <CircleDot className="w-3.5 h-3.5" /> TEM SAED
          </button>
          <button
            onClick={() => setActiveTab("xrd")}
            className={`cursor-pointer px-3 py-1 rounded text-[11px] font-bold tracking-wide transition-all duration-100 gap-1.5 flex items-center ${
              activeTab === "xrd"
                ? "bg-[#2563eb] text-white shadow-sm"
                : "text-zinc-400 hover:text-white hover:bg-zinc-800/40"
            }`}
          >
            <TrendingUp className="w-3.5 h-3.5" /> XRD Rayos X
          </button>
          <button
            onClick={() => setActiveTab("fft")}
            className={`cursor-pointer px-3 py-1 rounded text-[11px] font-bold tracking-wide transition-all duration-100 gap-1.5 flex items-center ${
              activeTab === "fft"
                ? "bg-[#2563eb] text-white shadow-sm"
                : "text-zinc-400 hover:text-white hover:bg-zinc-800/40"
            }`}
          >
            <Layers className="w-3.5 h-3.5" /> Filtrado FFT
          </button>
        </div>

        {/* Global actions and badges */}
        <div className="flex items-center gap-3">
          <span className="text-[10.5px] text-zinc-350 bg-zinc-900/50 px-2 py-0.5 rounded border border-zinc-800/50 hidden md:inline">
            Project: <b className="text-zinc-200">SAED_SrTiO3_04</b>
          </span>

          <div className="bg-[#2563eb] text-white font-bold text-[9px] px-2 py-0.5 rounded tracking-wider animate-pulse uppercase shadow-[0_0_10px_rgba(37,99,235,0.4)]">
            LIVE SIMULATION
          </div>

          <div className="h-4 w-[1px] bg-zinc-700/60" />

          {/* Theme switcher */}
          <button
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            className="cursor-pointer p-1.5 hover:bg-zinc-800/80 rounded border border-zinc-700 text-zinc-300 transition-all"
            title="Cambiar tema visual"
          >
            {theme === "dark" ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-indigo-405" />}
          </button>
        </div>
      </header>

      {/* MAIN CONTAINER PANELS ROW */}
      <main className="flex-1 overflow-y-auto p-4 md:p-5 flex flex-col lg:flex-row gap-5 max-w-[1600px] w-full mx-auto">
        
        {/* SIDEBAR BLOCK (CONFIGURATION AND CIFS) */}
        <section id="sidebar-block" className="w-full lg:w-80 shrink-0 flex flex-col gap-5">
          
          {/* PANEL 1: LOAD CRYSTALLINE CIFS AND PHASES */}
          <div className="prof-panel select-none">
            <div className="prof-panel-header px-3">
              <span className="flex items-center gap-1.5 font-bold">
                <Database className="w-3.5 h-3.5 text-zinc-600 dark:text-zinc-300" /> FASES Y CARGA CIF
              </span>
              <button
                onClick={() => cifInputRef.current?.click()}
                className="cursor-pointer text-[10px] font-bold text-blue-600 dark:text-blue-400 flex items-center gap-1 hover:underline transition-all"
              >
                <Upload className="w-3 h-3" /> IMPORTAR CIF
              </button>
              <input
                ref={cifInputRef}
                type="file"
                accept=".cif"
                onChange={handleCIFUpload}
                multiple
                className="hidden"
              />
            </div>

            <div className="p-3 flex flex-col gap-3">
              {/* List of active crystals databases */}
              <div className="border border-zinc-200 dark:border-zinc-800 bg-zinc-50/50 dark:bg-zinc-950/20 rounded overflow-y-auto max-h-[175px] p-1 flex flex-col gap-1">
                {structures.map((s) => (
                  <div
                    key={s.id}
                    onClick={() => setActiveStructureId(s.id)}
                    className={`cursor-pointer px-2.5 py-1.5 rounded flex items-center justify-between transition-all group ${
                      activeStructureId === s.id
                        ? "bg-white dark:bg-zinc-800 border border-[#cbd5e1] dark:border-zinc-700 border-l-4 border-l-blue-600 dark:border-l-blue-500 text-zinc-900 dark:text-white shadow-xs"
                        : "text-zinc-500 hover:bg-white/50 dark:hover:bg-zinc-800/50"
                    }`}
                  >
                    <div className="flex flex-col gap-0.5 max-w-[80%]">
                      <span className="text-[11px] font-bold leading-tight truncate">{s.name}</span>
                      <span className="text-[9px] text-zinc-400 italic font-mono">
                        {s.system} · {s.spaceGroup}
                      </span>
                    </div>
                    {structures.length > 1 && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          removeStructure(s.id);
                        }}
                        className="cursor-pointer opacity-0 group-hover:opacity-100 hover:text-red-500 transition-all px-1.5 text-zinc-400 font-bold"
                        title="Quitar fase"
                      >
                        ×
                      </button>
                    )}
                  </div>
                ))}
              </div>

              {activeStructure && (
                <div className="p-2.5 bg-blue-50/40 dark:bg-zinc-950/40 border border-blue-100/15 dark:border-zinc-800 rounded font-mono text-[10px] space-y-1">
                  <span className="font-bold text-blue-600 dark:text-blue-400 text-[10.5px] block truncate uppercase tracking-wider mb-1">
                    Metadatos Cristalográficos:
                  </span>
                  <div className="grid grid-cols-2 gap-y-1 gap-x-1.5 text-zinc-650 dark:text-zinc-350">
                    <span>SIST: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.system}</b></span>
                    <span>Z: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.atoms.length}</b></span>
                    <span>a: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.a.toFixed(3)} Å</b></span>
                    <span>b: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.b.toFixed(3)} Å</b></span>
                    <span>c: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.c.toFixed(3)} Å</b></span>
                    <span>DENSITY: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure.density.toFixed(2)}</b></span>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* PANEL 2: SIMULATION PARAMETERS */}
          <div className="prof-panel select-none animate-fade-in">
            <div className="prof-panel-header px-3">
              <span className="flex items-center gap-1.5 font-bold">
                <Sliders className="w-3.5 h-3.5 text-zinc-600 dark:text-zinc-300" /> PARÁMETROS TEM / SAED
              </span>
            </div>

            <div className="p-3 flex flex-col gap-3.5">
              {/* Voltage kV Select */}
              <div className="space-y-1">
                <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider block">
                  Voltaje de Aceleración (kV)
                </label>
                <div className="grid grid-cols-4 gap-0.5">
                  {[80, 120, 200, 300].map((kv) => (
                    <button
                      key={kv}
                      onClick={() => setParams((p) => ({ ...p, voltage: kv }))}
                      className={`cursor-pointer py-1 text-[10px] rounded font-mono font-bold border transition-all ${
                        params.voltage === kv
                          ? "bg-blue-650 border-blue-600 text-white bg-blue-600 font-bold shadow-xs"
                          : "bg-zinc-100 dark:bg-zinc-850 border-zinc-200 dark:border-zinc-800 text-zinc-600 hover:bg-zinc-200/50"
                      }`}
                    >
                      {kv} kV
                    </button>
                  ))}
                </div>
              </div>

              {/* Mode selection Rings/Spots */}
              <div className="space-y-1.5">
                <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider block">
                  Tipo de Patrón Simulado
                </label>
                <div className="grid grid-cols-2 gap-0.5">
                  <button
                    onClick={() => setParams((p) => ({ ...p, mode: "rings" }))}
                    className={`cursor-pointer py-1 text-[10.5px] font-bold rounded border transition-all ${
                      params.mode === "rings"
                        ? "bg-blue-600 border-blue-600 text-white font-bold"
                        : "bg-zinc-100 dark:bg-zinc-850 border-zinc-200 dark:border-zinc-800 text-zinc-600 hover:bg-zinc-200/50"
                    }`}
                  >
                    Policristal (Rings)
                  </button>
                  <button
                    onClick={() => setParams((p) => ({ ...p, mode: "spots" }))}
                    className={`cursor-pointer py-1 text-[10.5px] font-bold rounded border transition-all ${
                      params.mode === "spots"
                        ? "bg-blue-600 border-blue-600 text-white font-bold"
                        : "bg-zinc-100 dark:bg-zinc-850 border-zinc-200 dark:border-zinc-800 text-zinc-600 hover:bg-zinc-200/50"
                    }`}
                  >
                    Monocristal (Spots)
                  </button>
                </div>
                <div className="flex items-center justify-between pt-1 border-t border-zinc-200/55 dark:border-zinc-800/60">
                  <span className="text-[9.5px] font-bold text-zinc-500 uppercase tracking-wider">
                    Planos hkl (Índices)
                  </span>
                  <button
                    onClick={() => setParams((p) => ({ ...p, showLabels: !p.showLabels }))}
                    className={`cursor-pointer px-3 py-1 text-[9px] font-black rounded-full border transition-all tracking-wider ${
                      params.showLabels
                        ? "bg-emerald-50 border-emerald-300 text-emerald-750 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-800"
                        : "bg-zinc-100 border-zinc-200 dark:bg-zinc-900 dark:border-zinc-800 text-zinc-500"
                    }`}
                    title="Ocultar o mostrar los planos indexados hkl sobre los anillos o spots simulados"
                  >
                    {params.showLabels ? "✓ PLANOS ACTIVOS" : "✗ PLANOS OCULTOS"}
                  </button>
                </div>
              </div>

              {/* Mode selection Zone axis (spots mode only) */}
              {params.mode === "spots" && (
                <div className="space-y-2 p-2 bg-zinc-50 dark:bg-zinc-950/80 rounded border border-zinc-200 dark:border-zinc-800 flex flex-col gap-1">
                  <span className="text-[9.5px] font-bold text-zinc-550 dark:text-zinc-400 uppercase tracking-wider block text-center">
                    Eje de Zona [u v w]
                  </span>
                  <div className="grid grid-cols-3 gap-1.5">
                    {[0, 1, 2].map((idx) => (
                      <div key={idx} className="flex items-center gap-1">
                        <span className="text-[9.5px] font-mono text-zinc-400 font-bold uppercase">
                          {idx === 0 ? "u" : idx === 1 ? "v" : "w"}:
                        </span>
                        <input
                          type="number"
                          step="1"
                          value={params.zoneAxis[idx]}
                          onChange={(e) => {
                            const val = parseInt(e.target.value) || 0;
                            setParams((p) => {
                              const next = { ...p };
                              const nextZone = [...next.zoneAxis] as [number, number, number];
                              nextZone[idx] = val;
                              next.zoneAxis = nextZone;
                              return next;
                            });
                          }}
                          className="prof-input w-full text-center py-0.5 font-bold"
                        />
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Slider camera properties */}
              <div className="space-y-2">
                <div className="space-y-0.5">
                  <div className="flex justify-between text-[10px]">
                    <span className="text-zinc-500 font-medium">CAMERA LENGTH (L)</span>
                    <span className="font-mono font-bold text-blue-600 dark:text-blue-400">
                      {params.cameraLength} mm
                    </span>
                  </div>
                  <input
                    type="range"
                    min="150"
                    max="1200"
                    step="10"
                    value={params.cameraLength}
                    onChange={(e) => setParams((p) => ({ ...p, cameraLength: parseInt(e.target.value) }))}
                    className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                  />
                </div>

                <div className="space-y-0.5">
                  <div className="flex justify-between text-[10px]">
                    <span className="text-zinc-500 font-medium">DETECTOR SIZE</span>
                    <span className="font-mono font-bold text-blue-600 dark:text-blue-400">
                      {params.detectorSize} mm
                    </span>
                  </div>
                  <input
                    type="range"
                    min="40"
                    max="200"
                    step="5"
                    value={params.detectorSize}
                    onChange={(e) => setParams((p) => ({ ...p, detectorSize: parseInt(e.target.value) }))}
                    className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                  />
                </div>

                {params.mode === "spots" && (
                  <div className="space-y-0.5">
                    <div className="flex justify-between text-[10px]">
                      <span className="text-zinc-500 font-medium">ESPESOR MUESTRA (relrods)</span>
                      <span className="font-mono font-bold text-blue-600 dark:text-blue-400">
                        {params.sampleThickness} nm
                      </span>
                    </div>
                    <input
                      type="range"
                      min="2"
                      max="80"
                      step="1"
                      value={params.sampleThickness}
                      onChange={(e) => setParams((p) => ({ ...p, sampleThickness: parseInt(e.target.value) }))}
                      className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                    />
                  </div>
                )}

                <div className="space-y-0.5">
                  <div className="flex justify-between text-[10px]">
                    <span className="text-zinc-500 font-medium">GLOW INTENSIDAD</span>
                    <span className="font-mono font-bold text-blue-600 dark:text-blue-400">
                      {params.relativeIntensity.toFixed(1)}x
                    </span>
                  </div>
                  <input
                    type="range"
                    min="0.1"
                    max="5.0"
                    step="0.1"
                    value={params.relativeIntensity}
                    onChange={(e) => setParams((p) => ({ ...p, relativeIntensity: parseFloat(e.target.value) }))}
                    className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                  />
                </div>
              </div>
            </div>
          </div>
          
          {/* PANEL 3: EXPERIMENTAL MICROGRAPH CONTROLS */}
          <div className="prof-panel select-none animate-fade-in">
            <div className="prof-panel-header px-3">
              <span className="flex items-center gap-1.5 font-bold">
                <Settings className="w-3.5 h-3.5 text-zinc-600 dark:text-zinc-300" /> IMAGEN EXPERIMENTAL
              </span>
              <button
                onClick={() => fileInputRef.current?.click()}
                className="cursor-pointer text-[10px] text-blue-600 dark:text-blue-400 flex items-center gap-1 hover:underline font-bold"
              >
                <Plus className="w-3 h-3" /> CARGAR MICRO
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                onChange={handleImageUpload}
                className="hidden"
              />
            </div>

            <div className="p-3">
              {expImage.dataUrl ? (
                <div className="space-y-3">
                  <div className="p-2 bg-zinc-50 dark:bg-zinc-950/65 border border-zinc-200 dark:border-zinc-800 rounded">
                    <span className="text-[9.5px] font-mono text-zinc-500 dark:text-zinc-400 block truncate" title={expImage.name}>
                      ARCH: <b className="text-zinc-700 dark:text-zinc-200">{expImage.name}</b>
                    </span>
                  </div>

                  <div className="space-y-2">
                    <div className="space-y-0.5">
                      <div className="flex justify-between text-[10px]">
                        <span className="text-zinc-500 font-medium tracking-tight">ESCALA DE IMAGEN</span>
                        <span className="font-mono text-blue-600 dark:text-blue-400 font-bold">{expImage.scale.toFixed(3)}x</span>
                      </div>
                      <input
                        type="range"
                        min="0.1"
                        max="3.0"
                        step="0.05"
                        value={expImage.scale}
                        onChange={(e) => setExpImage((p) => ({ ...p, scale: parseFloat(e.target.value) }))}
                        className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                      />
                    </div>

                    <div className="space-y-0.5">
                      <div className="flex justify-between text-[10px]">
                        <span className="text-zinc-500 font-medium tracking-tight">ROTACIÓN IMAGEN</span>
                        <span className="font-mono text-blue-600 dark:text-blue-400 font-bold">{expImage.rotation}°</span>
                      </div>
                      <input
                        type="range"
                        min="-180"
                        max="180"
                        step="1"
                        value={expImage.rotation}
                        onChange={(e) => setExpImage((p) => ({ ...p, rotation: parseInt(e.target.value) }))}
                        className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                      />
                    </div>

                    <div className="space-y-0.5">
                      <div className="flex justify-between text-[10px]">
                        <span className="text-zinc-500 font-medium tracking-tight">CONTRASTE</span>
                        <span className="font-mono text-blue-600 dark:text-blue-400 font-bold">{expImage.contrast.toFixed(1)}x</span>
                      </div>
                      <input
                        type="range"
                        min="0.4"
                        max="2.5"
                        step="0.1"
                        value={expImage.contrast}
                        onChange={(e) => setExpImage((p) => ({ ...p, contrast: parseFloat(e.target.value) }))}
                        className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                      />
                    </div>

                    <div className="space-y-0.5">
                      <div className="flex justify-between text-[10px]">
                        <span className="text-zinc-500 font-medium tracking-tight">TRANSPARENCIA (OPACIDAD)</span>
                        <span className="font-mono text-blue-600 dark:text-blue-400 font-bold">{Math.round((expImage.alpha ?? 0.6) * 100)}%</span>
                      </div>
                      <input
                        type="range"
                        min="0.1"
                        max="1.0"
                        step="0.05"
                        value={expImage.alpha ?? 0.6}
                        onChange={(e) => setExpImage((p) => ({ ...p, alpha: parseFloat(e.target.value) }))}
                        className="w-full h-1 bg-zinc-200 dark:bg-zinc-700 rounded appearance-none cursor-pointer accent-blue-600"
                      />
                    </div>

                    <div className="flex items-center justify-between pt-2 border-t border-zinc-200 dark:border-zinc-800">
                      <button
                        onClick={() => setExpImage((prev) => ({ ...prev, inverted: !prev.inverted }))}
                        className={`cursor-pointer px-2 py-0.5 rounded text-[10px] font-bold border transition-all ${
                          expImage.inverted
                            ? "bg-blue-50 text-blue-650 border-blue-200 dark:bg-zinc-800 dark:text-blue-400 dark:border-zinc-700 bg-blue-100"
                            : "bg-zinc-150 border-zinc-200 dark:bg-zinc-800 dark:text-zinc-700 text-zinc-650"
                        }`}
                      >
                        Invertir Contraste
                      </button>
                      <div className="flex items-center gap-1.5 text-[9.5px]">
                        <button
                          onClick={() => {
                            setExpImage((p) => ({
                              ...p,
                              scale: 1.0,
                              rotation: 0,
                              contrast: 1.0,
                              brightness: 0,
                              offsetX: 0,
                              offsetY: 0,
                              inverted: false,
                              alpha: 0.6,
                            }));
                          }}
                          className="cursor-pointer text-zinc-500 hover:text-zinc-850 dark:hover:text-zinc-350 font-bold hover:underline"
                        >
                          Restablecer
                        </button>
                        <span className="text-zinc-300 dark:text-zinc-700 select-none">|</span>
                        <button
                          onClick={() => {
                            setExpImage({
                              name: "Ninguna",
                              scale: 0.75,
                              rawWidth: 0,
                              rawHeight: 0,
                              offsetX: 0,
                              offsetY: 0,
                              rotation: 0,
                              contrast: 1.0,
                              brightness: 0,
                              alpha: 0.5,
                              inverted: false,
                              calibrated: false,
                            });
                            if (fileInputRef.current) {
                              fileInputRef.current.value = "";
                            }
                          }}
                          className="cursor-pointer text-red-500 dark:text-red-400 hover:text-red-700 dark:hover:text-red-350 font-bold hover:underline"
                        >
                          Eliminar
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="p-4 border border-dashed border-zinc-200 dark:border-zinc-800 rounded text-center flex flex-col items-center justify-center bg-zinc-50/50 dark:bg-zinc-950/20 text-zinc-500">
                  <span className="text-[10.5px]">Sugerencia: puedes cargar micrografías experimentales para realizar análisis e indexación superpuestos</span>
                </div>
              )}
            </div>
          </div>
        </section>

        {/* WORKSPACE AREA */}
        <section id="workspace-block" className="flex-1 flex flex-col gap-5 min-w-0">
          
          {/* TAB 1: TEM OVERLAY AND SIMULATION */}
          {activeTab === "tem" && (
            <div className="flex flex-col gap-5">
              
              {/* DOUBLE COLUMN PANELS */}
              <div className="grid grid-cols-1 gap-5">
                
                {/* Simulated overlays patterns canvas */}
                <div className="w-full">
                  <DiffractionPatternCanvas
                    structure={activeStructure}
                    params={params}
                    expImage={expImage}
                    setExpImage={setExpImage}
                    measurements={measurements}
                    setMeasurements={setMeasurements}
                    expPeaks={expPeaks}
                    setExpPeaks={setExpPeaks}
                    activeTool={activeTool}
                    hoveredRef={hoveredRef}
                    setHoveredRef={setHoveredRef}
                  />
                </div>
              </div>

              {/* ACTION TOOLS DECK */}
              <div className="p-4 bg-white/70 dark:bg-zinc-900/60 border border-zinc-200/50 dark:border-zinc-850/65 rounded-2xl flex flex-wrap items-center justify-between gap-3 shadow-xs">
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setActiveTool("select")}
                    className={`cursor-pointer px-3.5 py-2 rounded-xl text-xs font-semibold flex items-center gap-1.5 transition-all ${
                      activeTool === "select"
                        ? "bg-indigo-600 text-white shadow-sm"
                        : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 text-zinc-600 dark:text-zinc-300"
                    }`}
                  >
                    <Search className="w-4 h-4" /> Cursor hkl
                  </button>
                  <button
                    onClick={() => setActiveTool("measure_dist")}
                    className={`cursor-pointer px-3.5 py-2 rounded-xl text-xs font-semibold flex items-center gap-1.5 transition-all ${
                      activeTool === "measure_dist"
                        ? "bg-indigo-600 text-white shadow-sm"
                        : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 text-zinc-600 dark:text-zinc-300"
                    }`}
                  >
                    <Sliders className="w-4 h-4" /> Medir d-spacing
                  </button>
                  <button
                    onClick={() => setActiveTool("add_peak")}
                    className={`cursor-pointer px-3.5 py-2 rounded-xl text-xs font-semibold flex items-center gap-1.5 transition-all ${
                      activeTool === "add_peak"
                        ? "bg-indigo-600 text-white shadow-sm"
                        : "bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 text-zinc-600 dark:text-zinc-300"
                    }`}
                  >
                    <ChevronRight className="w-4 h-4" /> Marcar anillos exp.
                  </button>
                </div>
                
                <div className="flex items-center gap-2 text-zinc-500 text-[10px]">
                  <Sparkles className="w-4 h-4 text-orange-400" />
                  <span>Para identificar fases de forma automática, selecciona "Marcar anillos exp." y haz click sobre las franjas.</span>
                </div>
              </div>

              {/* TWO COLUMN INDEXING TABLES */}
              <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
                
                {/* Simulated Miller Indexing table */}
                <div className="p-4 bg-white/70 dark:bg-zinc-900/60 border border-zinc-200/50 dark:border-zinc-850/60 rounded-2xl shadow-sm flex flex-col gap-3 min-w-0">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-zinc-900 dark:text-white flex items-center gap-1.5 uppercase tracking-wider">
                      <Table className="w-4.5 h-4.5 text-indigo-500" /> Tabla de Indexación (Teórica)
                    </span>
                    <button
                      onClick={handleCSVExport}
                      className="cursor-pointer bg-white border border-zinc-200 hover:bg-zinc-50 dark:bg-zinc-800 dark:border-zinc-700 dark:hover:bg-zinc-700/80 text-zinc-600 dark:text-zinc-300 text-[10.5px] px-2.5 py-1.5 rounded-lg flex items-center gap-1 transition-all font-semibold"
                    >
                      <Download className="w-3.5 h-3.5" /> Descargar CSV
                    </button>
                  </div>

                  <div className="overflow-x-auto border border-zinc-200/60 dark:border-zinc-800 rounded-xl overflow-hidden max-h-[220px]">
                    <table className="w-full border-collapse text-left text-xs bg-white dark:bg-zinc-950/20">
                      <thead>
                        <tr className="bg-zinc-50 dark:bg-zinc-900 text-zinc-500 font-bold border-b border-zinc-200/60 dark:border-zinc-800">
                          <th className="px-3.5 py-2 font-mono">h k l</th>
                          <th className="px-3.5 py-2">d-spacing (Å)</th>
                          <th className="px-3.5 py-2">Radio (mm)</th>
                          <th className="px-3.5 py-2 text-right">Intensidad Relativa</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-zinc-150/40 dark:divide-zinc-850">
                        {currentTableRings.map((r, idx) => (
                          <tr
                            key={idx}
                            className="hover:bg-indigo-50/20 dark:hover:bg-indigo-950/20 transition-all"
                          >
                            <td className="px-3.5 py-2 font-mono font-bold text-zinc-800 dark:text-zinc-200">{`(${r.h} ${r.k} ${r.l})`}</td>
                            <td className="px-3.5 py-2 font-mono">{r.d.toFixed(4)}</td>
                            <td className="px-3.5 py-2 font-mono">{r.radius.toFixed(2)}</td>
                            <td className="px-3.5 py-2 font-mono text-right font-medium text-emerald-600 dark:text-emerald-450">
                              {r.intensity.toFixed(1)}%
                            </td>
                          </tr>
                        ))}
                        {currentTableRings.length === 0 && (
                          <tr>
                            <td colSpan={4} className="px-3.5 py-6 text-center text-zinc-400 italic">
                              No hay reflexiones teóricas calculadas.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  </div>
                </div>

                {/* Candidate Phase identification rank table */}
                <div className="p-4 bg-white/70 dark:bg-zinc-900/60 border border-zinc-200/50 dark:border-zinc-850/60 rounded-2xl shadow-sm flex flex-col gap-3 min-w-0">
                  <span className="text-xs font-bold text-zinc-900 dark:text-white flex items-center gap-1.5 uppercase tracking-wider">
                    <Database className="w-4.5 h-4.5 text-indigo-500" /> Comparador Automático de Fases
                  </span>

                  {expPeaks.length === 0 ? (
                    <div className="flex-1 flex flex-col items-center justify-center p-6 border border-dashed border-zinc-200 dark:border-zinc-800 text-center text-zinc-500 rounded-xl bg-zinc-50/50 dark:bg-zinc-950/20">
                      <Search className="w-8 h-8 text-zinc-400 dark:text-zinc-600 mb-2" />
                      <span className="text-xs font-bold text-zinc-700 dark:text-zinc-300">
                        No se han marcado anillos experimentales
                      </span>
                      <p className="text-[10px] text-zinc-400 max-w-xs mt-1">
                        Utiliza el cursor de marcar anillos para registrar distancias en la imagen. La aplicación clasificará las fases candidatas según los d-spacings seleccionados.
                      </p>
                    </div>
                  ) : (
                    <div className="flex flex-col gap-3 flex-1 min-h-[220px]">
                      <div className="text-[10px] text-zinc-400 flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-orange-500 animate-pulse" />
                        <span>Comparando {expPeaks.length} anillos contra {structures.length} estructuras disponibles.</span>
                      </div>

                      <div className="overflow-x-auto border border-zinc-200/60 dark:border-zinc-800 rounded-xl max-h-[178px]">
                        <table className="w-full border-collapse text-left text-xs bg-white dark:bg-zinc-950/20">
                          <thead>
                            <tr className="bg-zinc-50 dark:bg-zinc-900 text-zinc-500 font-bold border-b border-zinc-200/60 dark:border-zinc-800">
                              <th className="px-3.5 py-2">Fase Candidata</th>
                              <th className="px-3.5 py-2 text-center">Score de Coincidencia</th>
                              <th className="px-3.5 py-2">Error RMS d (Å)</th>
                              <th className="px-3.5 py-2 text-right">Acción</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-zinc-150/40 dark:divide-zinc-850">
                            {candidatePhases.map((cand, idx) => (
                              <tr key={idx} className="hover:bg-indigo-50/20 dark:hover:bg-indigo-950/20 transition-all">
                                <td className="px-3.5 py-2.5">
                                  <div className="flex flex-col">
                                    <span className="font-bold text-zinc-800 dark:text-zinc-200">{cand.structureName}</span>
                                    <span className="text-[9.5px] text-zinc-400">{cand.system}</span>
                                  </div>
                                </td>
                                <td className="px-3.5 py-2.5 text-center">
                                  <div className="inline-flex items-center justify-center font-bold px-2.5 py-1 text-[10.5px] rounded-full bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20">
                                    {cand.matchScore}%
                                  </div>
                                </td>
                                <td className="px-3.5 py-2.5 font-mono">{cand.rmsErrorD.toFixed(4)}</td>
                                <td className="px-3.5 py-2.5 text-right">
                                  <button
                                    onClick={() => applyCandidatePhase(cand)}
                                    className="cursor-pointer bg-indigo-500 hover:bg-indigo-600 text-white text-[10px] px-2 py-1 rounded font-bold transition-all shadow-xs"
                                  >
                                    Cargar Fase
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: XRD POWDER ANALYSIS */}
          {activeTab === "xrd" && (
            <div className="flex flex-col gap-5">
              <div className="p-4 bg-white/70 dark:bg-zinc-900/60 border border-zinc-200/50 dark:border-zinc-850/60 rounded-2xl shadow-sm flex flex-col gap-3">
                <span className="text-xs font-bold text-zinc-900 dark:text-white flex items-center gap-1.5 uppercase tracking-wider">
                  <TrendingUp className="w-4.5 h-4.5 text-indigo-500" /> Analítica y Perfil de Bragg de Rayos X (XRD)
                </span>
                <p className="text-xs text-zinc-500 dark:text-zinc-400 max-w-3xl leading-relaxed">
                  Esta herramienta simula el difractograma de polvo de la estructura seleccionada utilizando una fuente de Cu-Kα. Modifica el factor de ensanchamiento de picos abajo para simular los efectos relacionados al tamaño de cristalita (Efecto Scherrer).
                </p>
              </div>

              <XRDProfileChart structure={activeStructure} params={params} />
            </div>
          )}

          {/* TAB 3: HRTEM IMAGE FFT/IFFT FILTERS */}
          {activeTab === "fft" && (
            <div className="flex flex-col gap-5">
              <div className="p-4 bg-white/70 dark:bg-zinc-900/60 border border-zinc-200/50 dark:border-zinc-850/60 rounded-2xl shadow-sm flex flex-col gap-3">
                <span className="text-xs font-bold text-zinc-900 dark:text-white flex items-center gap-1.5 uppercase tracking-wider">
                  <Layers className="w-4.5 h-4.5 text-indigo-500" /> Procesamiento Digital de Micrografías de Alta Resolución
                </span>
                <p className="text-xs text-zinc-500 dark:text-zinc-400 max-w-3xl leading-relaxed">
                  La Transformada Rápida de Fourier (2D FFT) mapea la imagen de la red cristalina en el espacio recíproco mostrando los spots de difracción correspondientes. Aplica máscaras pasa-banda o circulares específicas para eliminar el ruido de fondo amorfo del material, y realiza la transformada inversa (IFFT) para reconstruir los planos cristalinos filtrados.
                </p>
              </div>

              <FFTAnalysisPanel image={expImage} />
            </div>
          )}
        </section>
      </main>

      {/* STATUS BAR - Professional Polish Footer */}
      <footer className="h-6 bg-[#f8fafc] dark:bg-[#111827] border-t border-[#cbd5e1] dark:border-[#334155] flex items-center justify-between px-3 text-[10px] text-[#64748b] dark:text-zinc-450 shrink-0 select-none font-mono">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-emerald-500 inline-block animate-pulse" />
          <span>Fase Activa: <b className="text-zinc-800 dark:text-zinc-200">{activeStructure ? `${activeStructure.name} (${activeStructure.system})` : "Ninguna"}</b></span>
          {activeStructure && (
            <span className="text-zinc-400 dark:text-zinc-550 hidden sm:inline">
              | a={activeStructure.a.toFixed(3)} Å, b={activeStructure.b.toFixed(3)} Å, c={activeStructure.c.toFixed(3)} Å
            </span>
          )}
        </div>
        <div className="flex items-center gap-4">
          <span className="hidden md:inline">Voltaje: <b className="text-zinc-700 dark:text-zinc-300">{params.voltage} kV</b></span>
          <span className="hidden md:inline">Simulación: <b className="text-zinc-700 dark:text-zinc-300">{params.mode.toUpperCase()}</b></span>
          <span>Resolución: <b className="text-zinc-700 dark:text-zinc-300">{params.resolution}px</b></span>
          <span className="text-[9.5px] font-bold bg-zinc-200 dark:bg-zinc-800 text-zinc-650 dark:text-zinc-350 px-1.5 py-0.2 rounded">
            STATUS: READY
          </span>
        </div>
      </footer>
    </div>
  );
}
