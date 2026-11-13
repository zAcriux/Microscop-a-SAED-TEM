<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/c76e78a9-ccbb-462d-9eec-a7948258e564

## Run Locally

**Prerequisites:**  Node.js


1. Install dependencies:
   `npm install`
2. Set the `GEMINI_API_KEY` in [.env.local](.env.local) to your Gemini API key
3. Run the app:
   `npm run dev`

---

## TEM Lab (Android)

Native Android app for TEM/HRTEM/SEM/STEM micrograph analysis (DM3/DM4/TIFF reading, interactive FFT with
d-spacing measurement, Fourier filtering/IFFT, particle analysis with dynamic histograms, PDF reports).
See [`tem-lab-android/README.md`](tem-lab-android/README.md). Installable APK: `tem-lab-android/dist/`.
