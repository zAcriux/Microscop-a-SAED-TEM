export type CrystalSystem = "cubic" | "tetragonal" | "orthorhombic" | "hexagonal" | "monoclinic" | "triclinic";

export interface Atom {
  id: string;
  symbol: string;
  x: number; // fractional coordinate
  y: number; // fractional coordinate
  z: number; // fractional coordinate
  occupancy?: number;
}

export interface CrystallineStructure {
  id: string;
  name: string;
  spaceGroup: string;
  system: CrystalSystem;
  a: number; // in Å
  b: number; // in Å
  c: number; // in Å
  alpha: number; // in degrees
  beta: number; // in degrees
  gamma: number; // in degrees
  atoms: Atom[];
  density: number; // theoretical density in g/cm3
  formula?: string;
  cifContent?: string;
}

export interface SimulationParams {
  mode: "rings" | "spots" | "xrd";
  voltage: number; // in kV (e.g. 80, 120, 200, 300)
  cameraLength: number; // in mm
  detectorSize: number; // in mm
  resolution: number; // e.g. 512, 1024 (pixel width of canvas)
  relativeIntensity: number; // 0.1 to 10 scale
  sampleThickness: number; // in nm (used to compute Ewald relrod excitation error profile)
  zoneAxis: [number, number, number]; // [u, v, w]
  xrdWavelength: number; // in Å (e.g. 1.5406 for Cu-Ka)
  instrumentalBroadening: number; // FWHM in degrees for XRD peaks (e.g. 0.15)
  showLabels: boolean;
  minDSpacing: number; // minimum d-spacing to calculate, in Å (e.g. 0.8)
}

export interface Reflection {
  h: number;
  k: number;
  l: number;
  d: number; // d-spacing in Å
  intensity: number; // relative intensity
  radius: number; // radius on detector in mm
  radiusPx: number; // radius on detector in pixels
  theta: number; // Bragg angle theta in radians
  structureFactorReal: number;
  structureFactorImag: number;
  multiplicity: number;
  // For single crystal spots
  xPx?: number; // X position on detector canvas relative to center (px)
  yPx?: number; // Y position on detector canvas relative to center (px)
  excitationError?: number; // s_g excitation error (Å^-1)
}

export interface ExperimentalImage {
  dataUrl?: string;
  name: string;
  scale: number; // pixels per nm (or reciprocal scale)
  rawWidth: number;
  rawHeight: number;
  offsetX: number; // relative X offset of pattern center (px)
  offsetY: number; // relative Y offset of pattern center (px)
  rotation: number; // in degrees
  contrast: number; // 0.5 to 3
  brightness: number; // -100 to 100
  alpha: number; // opacity of pattern overlay, 0 to 1
  inverted: boolean;
  calibrated: boolean;
  calibrationLength?: number; // known physical distance
  calibrationUnit?: "nm" | "1/nm" | "A";
}

export interface Measurement {
  id: string;
  type: "distance" | "angle";
  points: Array<{ x: number; y: number }>; // coordinate positions on canvas
  value: number; // calculated d-spacing in Å, or angle in degrees
  label: string;
}

export interface ExperimentalPeak {
  id: string;
  d: number; // experimental d-spacing in Å
  intensity?: number; // rough relative intensity (high/med/low)
  radiusPx: number; // visual ring radius if clicked on canvas
}

export interface MatchedRef {
  experimentalD: number;
  simulatedD: number;
  errorPct: number;
  hkl: string;
  intensity: number;
}

export interface PhaseCandidate {
  structureId: string;
  structureName: string;
  system: CrystalSystem;
  matchScore: number; // 0-100%
  rmsErrorD: number; // RMS d-spacing error (Å)
  matchedPeaks: MatchedRef[];
}

export interface FFTMask {
  id: string;
  x: number; // normalized center x (0-1)
  y: number; // normalized center y (0-1)
  radius: number; // normalized radius (0-1)
  type: "pass" | "block";
}
