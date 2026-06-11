import { CrystallineStructure, Reflection, CrystalSystem, SimulationParams, ExperimentalPeak, PhaseCandidate, Atom } from "../types";

// Matrix 3x3 helpers
export type Matrix3x3 = [[number, number, number], [number, number, number], [number, number, number]];
export type Vector3 = [number, number, number];

// Standard element weights and atomic numbers (Z)
export const ELEMENT_DATA: Record<string, { z: number; mass: number; color: string; radius: number }> = {
  H: { z: 1, mass: 1.008, color: "#FFFFFF", radius: 0.37 },
  HE: { z: 2, mass: 4.003, color: "#D9FFFF", radius: 0.32 },
  LI: { z: 3, mass: 6.941, color: "#CC80FF", radius: 1.34 },
  BE: { z: 4, mass: 9.012, color: "#C2FF00", radius: 0.90 },
  B: { z: 5, mass: 10.811, color: "#FFB5B5", radius: 0.82 },
  C: { z: 6, mass: 12.011, color: "#666666", radius: 0.77 },
  N: { z: 7, mass: 14.007, color: "#3050F8", radius: 0.75 },
  O: { z: 8, mass: 15.999, color: "#FF0D0D", radius: 0.73 },
  F: { z: 9, mass: 18.998, color: "#90E050", radius: 0.71 },
  NE: { z: 10, mass: 20.180, color: "#B3E3F5", radius: 0.69 },
  NA: { z: 11, mass: 22.990, color: "#AB5CF2", radius: 1.54 },
  MG: { z: 12, mass: 24.305, color: "#8AFF00", radius: 1.30 },
  AL: { z: 13, mass: 26.982, color: "#BFA6A6", radius: 1.18 },
  SI: { z: 14, mass: 28.086, color: "#F0C8A0", radius: 1.11 },
  P: { z: 15, mass: 30.974, color: "#FF8000", radius: 1.06 },
  S: { z: 16, mass: 32.065, color: "#FFFF30", radius: 1.02 },
  CL: { z: 17, mass: 35.453, color: "#1FF01F", radius: 0.99 },
  K: { z: 19, mass: 39.098, color: "#8F40D4", radius: 1.96 },
  CA: { z: 20, mass: 40.078, color: "#3DFF00", radius: 1.74 },
  TI: { z: 22, mass: 47.867, color: "#BFC2C7", radius: 1.36 },
  V: { z: 23, mass: 50.942, color: "#A62929", radius: 1.25 },
  CR: { z: 24, mass: 51.996, color: "#8A99C7", radius: 1.27 },
  MN: { z: 25, mass: 54.938, color: "#9C7AC7", radius: 1.39 },
  FE: { z: 26, mass: 55.845, color: "#FFA500", radius: 1.25 },
  CO: { z: 27, mass: 58.933, color: "#F090A0", radius: 1.26 },
  NI: { z: 28, mass: 58.693, color: "#50D050", radius: 1.21 },
  CU: { z: 29, mass: 63.546, color: "#C88033", radius: 1.38 },
  ZN: { z: 30, mass: 65.380, color: "#7D80B0", radius: 1.31 },
  GA: { z: 31, mass: 69.723, color: "#C28F8F", radius: 1.26 },
  GE: { z: 32, mass: 72.630, color: "#668F8F", radius: 1.22 },
  AG: { z: 47, mass: 107.868, color: "#C0C0C0", radius: 1.53 },
  BA: { z: 56, mass: 137.327, color: "#00C900", radius: 1.98 },
  W: { z: 74, mass: 183.840, color: "#2194D6", radius: 1.46 },
  PT: { z: 78, mass: 195.084, color: "#D0D0E0", radius: 1.39 },
  AU: { z: 79, mass: 196.967, color: "#FFD700", radius: 1.44 },
  U: { z: 92, mass: 238.029, color: "#008F00", radius: 1.56 },
};

export function getAtomData(symbol: string) {
  const norm = symbol.trim().toUpperCase().replace(/[^A-Z]/g, "");
  return ELEMENT_DATA[norm] || { z: 22, mass: 50.0, color: "#A0A0A0", radius: 1.2 };
}

// Convert deg to rad
export const deg2rad = (deg: number) => (deg * Math.PI) / 180;
export const rad2deg = (rad: number) => (rad * 180) / Math.PI;

// Cross product of two 3D vectors
export function crossProduct(u: Vector3, v: Vector3): Vector3 {
  return [
    u[1] * v[2] - u[2] * v[1],
    u[2] * v[0] - u[0] * v[2],
    u[0] * v[1] - u[1] * v[0],
  ];
}

// Dot product
export function dotProduct(u: Vector3, v: Vector3): number {
  return u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
}

// Vector magnitude
export function vectorNorm(u: Vector3): number {
  return Math.sqrt(dotProduct(u, u));
}

// Matrix determinant
export function matrixDeterminant(M: Matrix3x3): number {
  return (
    M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) -
    M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0]) +
    M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0])
  );
}

// Invert 3x3 Matrix
export function invertMatrix(M: Matrix3x3): Matrix3x3 {
  const det = matrixDeterminant(M);
  if (Math.abs(det) < 1e-12) {
    // Return mock identity
    return [
      [1, 0, 0],
      [0, 1, 0],
      [0, 0, 1],
    ];
  }
  const invDet = 1.0 / det;
  const A = M[1][1] * M[2][2] - M[1][2] * M[2][1];
  const B = M[0][2] * M[2][1] - M[0][1] * M[2][2];
  const C = M[0][1] * M[1][2] - M[0][2] * M[1][1];
  const D = M[1][2] * M[2][0] - M[1][0] * M[2][2];
  const E = M[0][0] * M[2][2] - M[0][2] * M[2][0];
  const F = M[0][2] * M[1][0] - M[0][0] * M[1][2];
  const G = M[1][0] * M[2][1] - M[1][1] * M[2][0];
  const H = M[0][1] * M[2][0] - M[0][0] * M[2][1];
  const I = M[0][0] * M[1][1] - M[0][1] * M[1][0];

  return [
    [A * invDet, B * invDet, C * invDet],
    [D * invDet, E * invDet, F * invDet],
    [G * invDet, H * invDet, I * invDet],
  ];
}

// Transpose 3x3 Matrix
export function transposeMatrix(M: Matrix3x3): Matrix3x3 {
  return [
    [M[0][0], M[1][0], M[2][0]],
    [M[0][1], M[1][1], M[2][1]],
    [M[0][2], M[1][2], M[2][2]],
  ];
}

// Matrix-vector multiplication
export function matrixVectorMultiply(M: Matrix3x3, v: Vector3): Vector3 {
  return [
    M[0][0] * v[0] + M[0][1] * v[1] + M[0][2] * v[2],
    M[1][0] * v[0] + M[1][1] * v[1] + M[1][2] * v[2],
    M[2][0] * v[0] + M[2][1] * v[1] + M[2][2] * v[2],
  ];
}

/**
 * Computes direct-to-cartesian matrix transformation (M)
 * and its reciprocal coordinates transformation matrix (M*)
 */
export function buildLatticeTransform(
  a: number,
  b: number,
  c: number,
  alphaDeg: number,
  betaDeg: number,
  gammaDeg: number
): { directTransform: Matrix3x3; reciprocalTransform: Matrix3x3; volume: number } {
  const alpha = deg2rad(alphaDeg);
  const beta = deg2rad(betaDeg);
  const gamma = deg2rad(gammaDeg);

  const cosA = Math.cos(alpha);
  const cosB = Math.cos(beta);
  const cosG = Math.cos(gamma);
  const sinG = Math.sin(gamma);

  // Volume factor of direct cell
  const term = 1 - cosA * cosA - cosB * cosB - cosG * cosG + 2 * cosA * cosB * cosG;
  const volFactor = term > 0 ? Math.sqrt(term) : 0;
  const volume = a * b * c * volFactor;

  // Direct lattice vectors mapped onto Cartesian space
  // a is along X-axis
  // b is in XY-plane
  // c is general
  const directTransform: Matrix3x3 = [
    [a, b * cosG, c * cosB],
    [0, b * sinG, c * (cosA - cosB * cosG) / sinG],
    [0, 0, c * volFactor / sinG],
  ];

  // Reciprocal transform is the transpose of the inverse of the direct transform
  const invDirect = invertMatrix(directTransform);
  const reciprocalTransform = transposeMatrix(invDirect);

  return { directTransform, reciprocalTransform, volume };
}

/**
 * Calculates relativistic electron wavelength (Å) from voltage (kV)
 */
export function calculateElectronWavelength(voltagekV: number): number {
  const V_volts = voltagekV * 1000;
  // Relativistic wavelength formula
  // lambda = 12.2643 / sqrt(V * (1 + 0.97847e-6 * V))
  const relFactor = 1 + 0.97847e-6 * V_volts;
  return 12.2643 / Math.sqrt(V_volts * relFactor);
}

/**
 * Calculates theoretical density (g/cm³) of a crystalline structure
 */
export function calculateTheoreticalDensity(structure: Omit<CrystallineStructure, "density">): number {
  const { a, b, c, alpha, beta, gamma, atoms } = structure;
  const { volume } = buildLatticeTransform(a, b, c, alpha, beta, gamma);

  if (volume <= 0) return 0;

  // sum masses of all atoms within the unit cell
  let cellMassAmu = 0;
  for (const atom of atoms) {
    const { mass } = getAtomData(atom.symbol);
    const occupancy = atom.occupancy ?? 1.0;
    cellMassAmu += mass * occupancy;
  }

  // amu to g, Å3 to cm3, combined factor is 1.660539
  return (cellMassAmu * 1.660539) / volume;
}

/**
 * Electron atomic scattering factor f_e(s)
 * s = sin(theta) / lambda = 1 / (2 * d)
 */
export function atomicScatteringFactor(z: number, s: number): number {
  if (s < 1e-6) s = 1e-6;
  // Mott-Bethe-like simplified electron scattering approximation
  // proportional to Z^0.75 decaying at high scattering angles
  const debyeWaller = Math.exp(-0.4 * s * s);
  return (0.12 * Math.pow(z, 0.75) / (s * s + 0.05)) * debyeWaller;
}

/**
 * Generates all unique reflections hkl within a minimum d-spacing
 */
export function generateReflections(
  structure: CrystallineStructure,
  minDSpacing: number,
  voltagekV: number,
  cameraLengthMm: number,
  detectorSizeMm: number,
  canvasSizePx: number
): Reflection[] {
  const { a, b, c, alpha, beta, gamma, atoms } = structure;
  const { reciprocalTransform } = buildLatticeTransform(a, b, c, alpha, beta, gamma);
  const lambda = calculateElectronWavelength(voltagekV);

  const lambdaL = cameraLengthMm * lambda; // constant in mm*Å
  const conversionFactorPxPerMm = canvasSizePx / detectorSizeMm;

  const reflections: Reflection[] = [];
  const visited = new Set<string>();

  // Determine an outer grid limit for indices h, k, l
  const limitH = Math.ceil(a / minDSpacing) + 1;
  const limitK = Math.ceil(b / minDSpacing) + 1;
  const limitL = Math.ceil(c / minDSpacing) + 1;

  for (let h = -limitH; h <= limitH; h++) {
    for (let k = -limitK; k <= limitK; k++) {
      for (let l = -limitL; l <= limitL; l++) {
        if (h === 0 && k === 0 && l === 0) continue;

        // Compute direct reciprocal vector components (recip-Cartesian)
        const gVector = matrixVectorMultiply(reciprocalTransform, [h, k, l]);
        const gMagnitude = vectorNorm(gVector); // length = 1 / d
        if (gMagnitude <= 0) continue;

        const d = 1.0 / gMagnitude;
        if (d < minDSpacing) continue;

        // Group together equivalents for rings, but keep indices recorded
        const key = `${h},${k},${l}`;
        if (visited.has(key)) continue;
        visited.add(key);

        // Theoretical Bragg angle: theta = arcsin(lambda / (2 * d))
        const sinTheta = lambda / (2 * d);
        if (sinTheta >= 1.0) continue; // outside Bragg limit
        const theta = Math.asin(sinTheta);

        // Calculate structure factor F(hkl)
        // F(hkl) = sum( f_j * exp(2*pi*i*(h*xj + k*yj + l*zj)) )
        let fReal = 0;
        let fImag = 0;
        const s = 1.0 / (2 * d); // scattering index

        for (const atom of atoms) {
          const { z } = getAtomData(atom.symbol);
          const f_fe = atomicScatteringFactor(z, s);
          const phase = 2 * Math.PI * (h * atom.x + k * atom.y + l * atom.z);
          const occ = atom.occupancy ?? 1.0;

          fReal += f_fe * Math.cos(phase) * occ;
          fImag += f_fe * Math.sin(phase) * occ;
        }

        const intensity = fReal * fReal + fImag * fImag;

        // Calculate location on detector
        // Ring radius R = L * tan(2*theta)
        const radiusMm = cameraLengthMm * Math.tan(2 * theta);
        const radiusPx = radiusMm * conversionFactorPxPerMm;

        reflections.push({
          h,
          k,
          l,
          d,
          intensity,
          radius: radiusMm,
          radiusPx,
          theta,
          structureFactorReal: fReal,
          structureFactorAlt: intensity, // storing intensity
          structureFactorImag: fImag,
          multiplicity: 1, // calculated dynamically in XRD grouping
        } as unknown as Reflection);
      }
    }
  }

  // Sort reflections by d-spacing descending
  return reflections.sort((a, b) => b.d - a.d);
}

/**
 * Returns merged reflections grouped by unique d-spacing (for rings / XRD powder profiles)
 */
export function getPowderReflections(reflections: Reflection[], errorTolerance = 0.005): Reflection[] {
  const grouped: Reflection[] = [];

  for (const ref of reflections) {
    // Find if another equivalent d-spacing is already in grouped
    const existing = grouped.find((item) => Math.abs(item.d - ref.d) < errorTolerance);
    if (existing) {
      existing.intensity += ref.intensity;
      existing.multiplicity += 1;
    } else {
      grouped.push({
        ...ref,
        multiplicity: 1,
      });
    }
  }

  // Normalize intensities so that max is 100
  const maxIntensity = Math.max(...grouped.map((r) => r.intensity), 1e-6);
  for (const r of grouped) {
    r.intensity = (r.intensity / maxIntensity) * 100;
  }

  return grouped.sort((a, b) => b.d - a.d);
}

/**
 * Projects single crystal spots (SAED) aligned with a Zone Axis [u,v,w]
 */
export function getSingleCrystalSpots(
  structure: CrystallineStructure,
  params: SimulationParams
): Reflection[] {
  const { a, b, c, alpha, beta, gamma, atoms } = structure;
  const { directTransform, reciprocalTransform } = buildLatticeTransform(a, b, c, alpha, beta, gamma);

  // Relativistic wavelength
  const lambda = calculateElectronWavelength(params.voltage);

  // Convert zone axis indices [u,v,w] to real space vector
  const u_v_w = params.zoneAxis;
  const zoneVectorReal = matrixVectorMultiply(directTransform, u_v_w);
  const zoneLength = vectorNorm(zoneVectorReal);
  if (zoneLength <= 0) return [];

  // normalized electron beam direction vector is our z_beam orthogonal axis
  const e_z: Vector3 = [
    zoneVectorReal[0] / zoneLength,
    zoneVectorReal[1] / zoneLength,
    zoneVectorReal[2] / zoneLength,
  ];

  // Find two orthogonal spanning vectors on the flat screen (e_x and e_y)
  // Let's standard find any non-parallel template vector
  const t: Vector3 = Math.abs(e_z[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0];
  const tDotEZ = dotProduct(t, e_z);
  const projX: Vector3 = [t[0] - tDotEZ * e_z[0], t[1] - tDotEZ * e_z[1], t[2] - tDotEZ * e_z[2]];
  const projXLen = vectorNorm(projX);
  const e_x: Vector3 = [projX[0] / projXLen, projX[1]/projXLen, projX[2]/projXLen];

  // e_y is cross product (e_z x e_x)
  const e_y = crossProduct(e_z, e_x);

  const rawRefs = generateReflections(
    structure,
    params.minDSpacing,
    params.voltage,
    params.cameraLength,
    params.detectorSize,
    params.resolution
  );

  const spotsList: Reflection[] = [];
  const t_nm = params.sampleThickness; // sample thickness in nm
  const conversionFactorPxPerMm = params.resolution / params.detectorSize;

  for (const ref of rawRefs) {
    // Project the reciprocal lattice point hkl onto the detector vectors
    const gVec = matrixVectorMultiply(reciprocalTransform, [ref.h, ref.k, ref.l]);

    // Reciprocal Cartesian components
    const gx = dotProduct(gVec, e_x);
    const gy = dotProduct(gVec, e_y);
    const gz = dotProduct(gVec, e_z); // distance along the electron beam

    // Ewald sphere relaxation factor:
    // Ewald sphere is centered at -1/lambda along the electron beam vector (e_z)
    // Excitation error (s_g) accounts for curvature: s_g = gz + (lambda * |g|^2) / 2
    const gSq = dotProduct(gVec, gVec);
    const s_g = gz + (lambda * gSq) / 2.0;

    // Intensity decay due to excitation error (thickness effect, relrod extension)
    // Sinc or Gaussian decay approximation
    const thicknessTerm = Math.exp(-Math.pow(Math.PI * s_g * t_nm, 2));

    const spotIntensity = ref.intensity * thicknessTerm * params.relativeIntensity;
    if (spotIntensity < 1e-4) continue; // too weak to render

    // Calculate spot location on detector canvas from center (X_px, Y_px)
    // dist_mm = CameraLength * lambda * g_perp
    // converting g_perp (Å^-1) to displacement in mm
    const L_lambda = params.cameraLength * lambda;
    const xMm = L_lambda * gx;
    const yMm = L_lambda * gy;

    const xPx = xMm * conversionFactorPxPerMm;
    const yPx = yMm * conversionFactorPxPerMm;

    spotsList.push({
      ...ref,
      intensity: spotIntensity,
      xPx,
      yPx,
      excitationError: s_g,
    });
  }

  return spotsList;
}

/**
 * Automatical indexation: Searches loaded structures for matches with experimental rings / spacings
 */
export function phaseIdentification(
  expPeaks: ExperimentalPeak[],
  loadedStructures: CrystallineStructure[],
  params: SimulationParams
): PhaseCandidate[] {
  if (expPeaks.length === 0) return [];

  const candidates: PhaseCandidate[] = [];

  for (const struct of loadedStructures) {
    // Generate simulated powder peaks
    const rawRefs = generateReflections(
      struct,
      params.minDSpacing,
      params.voltage,
      params.cameraLength,
      params.detectorSize,
      params.resolution
    );
    const simRefs = getPowderReflections(rawRefs);

    let totalErrorSqr = 0;
    let peaksMatchedCount = 0;
    const matchedPeaksList: PhaseCandidate["matchedPeaks"] = [];

    for (const expPeak of expPeaks) {
      // Find the closest simulated d-spacing
      let closestRef: Reflection | null = null;
      let minDiff = Infinity;

      for (const simRef of simRefs) {
        const diff = Math.abs(simRef.d - expPeak.d);
        if (diff < minDiff) {
          minDiff = diff;
          closestRef = simRef;
        }
      }

      // Check if candidate is relatively close (within 15% spacing tolerance)
      if (closestRef && minDiff / expPeak.d < 0.15) {
        peaksMatchedCount++;
        const errorPct = (minDiff / expPeak.d) * 100;
        totalErrorSqr += minDiff * minDiff;

        matchedPeaksList.push({
          experimentalD: expPeak.d,
          simulatedD: closestRef.d,
          errorPct,
          hkl: `(${closestRef.h} ${closestRef.k} ${closestRef.l})`,
          intensity: closestRef.intensity,
        });
      }
    }

    if (peaksMatchedCount > 0) {
      const rmsErrorD = Math.sqrt(totalErrorSqr / peaksMatchedCount);
      // Matching Score is a compound of d-spacing matching coverage and accuracy
      const matchCoverageRatio = peaksMatchedCount / expPeaks.length;
      const averageError = matchedPeaksList.reduce((sum, p) => sum + p.errorPct, 0) / peaksMatchedCount;
      const errorFactor = Math.max(0, 100 - averageError * 12); // heavier penalty for high errors

      const matchScore = parseFloat((matchCoverageRatio * errorFactor).toFixed(1));

      candidates.push({
        structureId: struct.id,
        structureName: struct.name,
        system: struct.system,
        matchScore: Math.min(100, Math.max(0, matchScore)),
        rmsErrorD,
        matchedPeaks: matchedPeaksList.sort((a,b) => a.experimentalD - b.experimentalD),
      });
    }
  }

  // Sort candidates by matchScore descending (best candidate first)
  return candidates.sort((a, b) => b.matchScore - a.matchScore);
}

/**
 * 2D Fast Fourier Transform (FFT) on complex pixel arrays (Radix-2)
 */
export function compute2DFFT(
  realInput: Float32Array,
  size: number,
  inverse = false
): { real: Float32Array; imag: Float32Array } {
  const real = new Float32Array(realInput);
  const imag = new Float32Array(size * size); // starts with 0s

  // Cooley-Tukey Radix-2 1D FFT helper
  const fft1D = (re: Float32Array, im: Float32Array, n: number, inv: boolean) => {
    let j = 0;
    for (let i = 0; i < n; i++) {
      if (i < j) {
        let temp = re[i]; re[i] = re[j]; re[j] = temp;
        temp = im[i]; im[i] = im[j]; im[j] = temp;
      }
      let m = n >> 1;
      while (m >= 2 && j >= m) {
        j -= m;
        m >>= 1;
      }
      j += m;
    }

    for (let len = 2; len <= n; len <<= 1) {
      const angle = (inv ? 2 : -2) * Math.PI / len;
      const wlenRe = Math.cos(angle);
      const wlenIm = Math.sin(angle);
      const half = len >> 1;

      for (let i = 0; i < n; i += len) {
        let wRe = 1;
        let wIm = 0;
        for (let k = 0; k < half; k++) {
          const uRe = re[i + k];
          const uIm = im[i + k];
          const targetRe = re[i + k + half];
          const targetIm = im[i + k + half];

          const tRe = targetRe * wRe - targetIm * wIm;
          const tIm = targetRe * wIm + targetIm * wRe;

          re[i + k] = uRe + tRe;
          im[i + k] = uIm + tIm;
          re[i + k + half] = uRe - tRe;
          im[i + k + half] = uIm - tIm;

          const nextWRe = wRe * wlenRe - wIm * wlenIm;
          wIm = wRe * wlenIm + wIm * wlenRe;
          wRe = nextWRe;
        }
      }
    }

    if (inv) {
      for (let i = 0; i < n; i++) {
        re[i] /= n;
        im[i] /= n;
      }
    }
  };

  // Perform 1D FFT along each Row
  const rowRe = new Float32Array(size);
  const rowIm = new Float32Array(size);
  for (let r = 0; r < size; r++) {
    const rowOffset = r * size;
    for (let c = 0; c < size; c++) {
      rowRe[c] = real[rowOffset + c];
      rowIm[c] = imag[rowOffset + c];
    }
    fft1D(rowRe, rowIm, size, inverse);
    for (let c = 0; c < size; c++) {
      real[rowOffset + c] = rowRe[c];
      imag[rowOffset + c] = rowIm[c];
    }
  }

  // Perform 1D FFT along each Column
  const colRe = new Float32Array(size);
  const colIm = new Float32Array(size);
  for (let c = 0; c < size; c++) {
    for (let r = 0; r < size; r++) {
      colRe[r] = real[r * size + c];
      colIm[r] = imag[r * size + c];
    }
    fft1D(colRe, colIm, size, inverse);
    for (let r = 0; r < size; r++) {
      real[r * size + c] = colRe[r];
      imag[r * size + c] = colIm[r];
    }
  }

  return { real, imag };
}

/**
 * Shuffles 2D spectral centers for natural display (FFTShift / IFFTShift)
 */
export function fftShift(array: Float32Array, size: number): Float32Array {
  const output = new Float32Array(size * size);
  const half = size / 2;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const nx = (x + half) % size;
      const ny = (y + half) % size;
      output[ny * size + nx] = array[y * size + x];
    }
  }
  return output;
}

/**
 * Parse CIF (Crystallographic Information File)
 * Extremely resilient client-side parser
 */
export function parseCIF(cifContent: string, fileName: string): CrystallineStructure {
  let a = 5.4;
  let b = 5.4;
  let c = 5.4;
  let alpha = 90;
  let beta = 90;
  let gamma = 90;
  let spaceGroup = "Unknown";
  let system: CrystalSystem = "cubic";

  const atoms: Atom[] = [];
  const lines = cifContent.split("\n");

  const cleanNum = (str: string): number => {
    // Strip parenthesized error bounds: e.g. "5.4307(2)" -> 5.4307
    return parseFloat(str.replace(/\(.*?\)/g, "").trim());
  };

  const cleanStr = (str: string): string => {
    return str.replace(/['"]/g, "").trim();
  };

  let inLoop = false;
  const loopHeaders: string[] = [];
  let loopDataRows: string[][] = [];

  for (let i = 0; i < lines.length; i++) {
    const rawLine = lines[i];
    const line = rawLine.trim();

    if (line === "" || line.startsWith("#")) continue;

    // Direct cell parameter matches
    if (line.startsWith("_cell_length_a")) {
      a = cleanNum(line.split(/\s+/)[1]);
    } else if (line.startsWith("_cell_length_b")) {
      b = cleanNum(line.split(/\s+/)[1]);
    } else if (line.startsWith("_cell_length_c")) {
      c = cleanNum(line.split(/\s+/)[1]);
    } else if (line.startsWith("_cell_angle_alpha")) {
      alpha = cleanNum(line.split(/\s+/)[1]);
    } else if (line.startsWith("_cell_angle_beta")) {
      beta = cleanNum(line.split(/\s+/)[1]);
    } else if (line.startsWith("_cell_angle_gamma")) {
      gamma = cleanNum(line.split(/\s+/)[1]);
    } else if (
      line.startsWith("_space_group_name_H-M_alt") ||
      line.startsWith("_symmetry_space_group_name_H-M") ||
      line.startsWith("_space_group.P_S_G_name_H-M") ||
      line.startsWith("_space_group_name_Hall")
    ) {
      const parts = line.split(/\s+/);
      parts.shift(); // remove label
      spaceGroup = cleanStr(parts.join(" "));
    }

    // Checking loops
    if (line.startsWith("loop_")) {
      inLoop = true;
      // process accumulated previous loop
      if (loopHeaders.length > 0) {
        processLoop(loopHeaders, loopDataRows, atoms);
      }
      loopHeaders.length = 0;
      loopDataRows = [];
      continue;
    }

    if (inLoop) {
      if (line.startsWith("_")) {
        loopHeaders.push(line);
      } else {
        // Collect data rows
        // If a new tag starts outside loop_, clean up state
        if (line.startsWith("data_") || line.startsWith("_cell") || line.startsWith("_space_group")) {
          inLoop = false;
          processLoop(loopHeaders, loopDataRows, atoms);
          loopHeaders.length = 0;
          loopDataRows = [];
        } else {
          // parse data row
          const matchQuote = line.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g);
          if (matchQuote) {
            loopDataRows.push(matchQuote);
          }
        }
      }
    }
  }

  // EOF cleanup
  if (loopHeaders.length > 0) {
    processLoop(loopHeaders, loopDataRows, atoms);
  }

  // Deduce system crystal from parameters if not parsed
  if (Math.abs(alpha - 90) < 0.2 && Math.abs(beta - 90) < 0.2 && Math.abs(gamma - 90) < 0.2) {
    if (Math.abs(a - b) < 0.01 && Math.abs(b - c) < 0.01) {
      system = "cubic";
    } else if (Math.abs(a - b) < 0.01) {
      system = "tetragonal";
    } else {
      system = "orthorhombic";
    }
  } else if (Math.abs(alpha - 90) < 0.2 && Math.abs(beta - 90) < 0.2 && Math.abs(gamma - 120) < 0.2) {
    system = "hexagonal";
  } else if (Math.abs(alpha - 90) < 0.2 && Math.abs(gamma - 90) < 0.2) {
    system = "monoclinic";
  } else {
    system = "triclinic";
  }

  const baseName = fileName.replace(/\.cif$/i, "");
  const formula = atoms.length > 0 
    ? Array.from(new Set(atoms.map(a => a.symbol))).slice(0, 4).join("")
    : "AB";

  const draft: Omit<CrystallineStructure, "density"> = {
    id: `cif_${Date.now()}_${Math.floor(Math.random()*1000)}`,
    name: baseName.charAt(0).toUpperCase() + baseName.slice(1),
    spaceGroup,
    system,
    a,
    b,
    c,
    alpha,
    beta,
    gamma,
    atoms: atoms.slice(0, 120), // capped for safety/performance
    formula,
    cifContent,
  };

  const density = calculateTheoreticalDensity(draft);

  return {
    ...draft,
    density,
  };
}

function processLoop(headers: string[], rows: string[][], atoms: Atom[]) {
  const fileSymbolIndex = headers.findIndex(h => h === "_atom_site_type_symbol" || h === "_atom_site_label");
  const xIndex = headers.findIndex(h => h === "_atom_site_fract_x");
  const yIndex = headers.findIndex(h => h === "_atom_site_fract_y");
  const zIndex = headers.findIndex(h => h === "_atom_site_fract_z");
  const occIndex = headers.findIndex(h => h === "_atom_site_occupancy");

  if (fileSymbolIndex === -1 || xIndex === -1 || yIndex === -1 || zIndex === -1) {
    return; // incomplete atoms block
  }

  for (const row of rows) {
    if (row.length < headers.length) continue;

    const rawSym = row[fileSymbolIndex].replace(/['"]/g, "").trim();
    // Parse element symbol, e.g. "Si1" or "O_z" -> "Si", "O"
    let symbol = rawSym.replace(/[_0-9+-]/g, "");
    if (symbol.length > 2) symbol = symbol.substring(0, 2);
    // Capitalize first letter, lowercase second
    symbol = symbol.charAt(0).toUpperCase() + symbol.slice(1).toLowerCase();

    // Confirm is standard chemical element
    if (symbol === "" || !ELEMENT_DATA[symbol.toUpperCase()]) {
      // Fallback fallback
      symbol = "Fe";
    }

    const cleanVal = (str: string): number => {
      return parseFloat(str.replace(/\(.*?\)/g, "").trim());
    };

    const x = cleanVal(row[xIndex]);
    const y = cleanVal(row[yIndex]);
    const z = cleanVal(row[zIndex]);
    const occupancy = occIndex !== -1 ? Math.min(1.0, Math.max(0, cleanVal(row[occIndex]))) : 1.0;

    if (isNaN(x) || isNaN(y) || isNaN(z)) continue;

    atoms.push({
      id: `atom_${atoms.length}_${Date.now()}_${Math.floor(Math.random()*100)}`,
      symbol,
      x,
      y,
      z,
      occupancy
    });
  }
}
