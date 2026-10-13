# Decorstr

**Decorstr** is a high-performance Android application that performs real-time **decorrelation stretch** directly on live RAW camera streams (`ImageFormat.RAW_SENSOR` 16-bit Bayer and `ImageFormat.RAW10`). Designed for field geology, mineralogical survey, materials science, and non-destructive engineering inspection, it enhances faint chromatic variances and subtle spectral boundaries at 30–60 FPS.

---

## Key Features

- **Direct RAW Sensor Pipeline**: Operates directly on uncompressed 16-bit Bayer sensor data before standard ISP tone mapping, gamma compression, or color clipping occur.
- **Hardware-Accelerated GLES 3.0**: Real-time bilinear demosaicing, dynamic affine color space whitening transforms ($y = \mathbf{W}x + b$), tone normalization, and display gamma in an OpenGL ES 3.0 fragment shader.
- **Native C++ Engine**: Jacobi symmetric $3 \times 3$ eigendecomposition, covariance estimation, temporal smoothing with eigenvector phase alignment, and noise-floor regularization running in $< 0.3\text{ ms}$ on ARM64.
- **Physical Multi-Camera Ingestion**: Generically discovers and streams from all available physical optical sensors on multi-camera devices (e.g. Ultra-Wide 0.6x, Main Wide 1x, Optical Telephoto 3x) via Android's `LOGICAL_MULTI_CAMERA` physical stream architecture.
- **Per-Sensor White Balance Calibration**: Derives physical sensor calibration gains from D65 reference illuminants and sensor color calibration transforms, neutralizing silicon Bayer sensitivity across different camera modules.
- **13 Spectral & Chromatic Enhancement Modes**: Custom-tailored transforms for geology, hydrothermal alteration, metallurgy, and fluid inspection.
- **Interactive In-App Field Guide**: Built-in reference manual explaining target minerals, engineering applications, and mathematical derivations accessible directly from the top header.
- **Still Photo Capture**: One-tap capture saving both the full-resolution processed JPEG and the uncompressed raw Bayer DNG file.

---

## Enhancement Modes & Applications

| Mode | Filter Code | Primary Inspection Focus | Spectral / Color Science |
| :--- | :---: | :--- | :--- |
| **General** | `LAB` | Broad geological mapping, multi-mineral outcrops, landscape surveys | CIE $L^*a^*b^*$ decorrelation; whitens chromaticity ($a^*, b^*$) while preserving perceptual lightness ($L^*$). |
| **Reds** | `CRGB` | Iron oxides (hematite), gossans, rust/corrosion inspection, alteration halos | NASA JPL linear RGB decorrelation stretch; equalizes channel variances. |
| **Red Ochre** | `YRD` | Hydrothermal alteration, ferruginous sandstone, desert varnish | Red difference chroma boost: $[Y, 0.5(U-0.5)+0.5, 2.0(V-0.5)+0.5]$. |
| **Faint Reds** | `YRE` | Incipient corrosion, micro-oxidation, trace hydrothermal veins | Aggressive red difference boost: $[Y, 0.35(U-0.5)+0.5, 4.0(V-0.5)+0.5]$. |
| **Yellows** | `YYE` | Limonite, goethite, sulfur deposits, jarosite, chemical leaching | Inverts and amplifies yellow-blue chroma $2.5\times$: $[Y, -2.5(U-0.5)+0.5, 0.8(V-0.5)+0.5]$. |
| **Blues** | `YBL` | Azurite, chrysocolla, vivianite, copper patina, weld heat tinting, fluid leaks | Amplifies blue-difference chroma $3.0\times$ while suppressing red/luminance noise ($0.7\times$). |
| **Greens** | `YGN` | Malachite, chlorite alteration, nickel blooms (annabergite), fuchsite, coolant | Orthogonal Green-Magenta axis rotation in YUV with $2.5\times$ variance boost along pure green vector. |
| **Blacks** | `YBK` | Manganese dendrites, carbonaceous seams, structural fractures, soot | Luminance contrast amplified $2.2\times$ with moderate chroma suppression ($0.5\times$). |
| **Whites** | `YWE` | Calcite/carbonate veining, quartz veins, evaporites, concrete efflorescence | High-reflectance luminance amplified $2.5\times$ with chroma $0.7\times$. |
| **Yellow / Gen** | `YDT` | Mixed mineral assemblages, drill core logging, structural engineering | Rec.601 YUV decorrelation stretch with equal $1:1:1$ channel weighting. |
| **Deep Reds** | `RGB0` | Outcrops in deep shade, basaltic rock formations, deep fissures | Zero-mean RGB decorrelation stretch ($\mathbf{b} = \mathbf{0}$); preserves deep shadow blackness. |
| **Spectral** | `PCA` | Lithological classification, distinguishing distinct mineral phases | Direct Principal Component Analysis; maps 1st, 2nd, and 3rd eigenvectors to R, G, B. |
| **Neutral** | `RAW` | Calibrated physical baseline & ground truth | Daylight neutral RAW sensor stream with sRGB display gamma, without stretching. |

---

## Architecture & Pipeline

```
Camera2 RAW Frame (16-bit Bayer)
  │
  ├──► Downsample Grid (~160x120 quads)
  │      └──► Estimate Mean μ & Covariance Σ (3x3)
  │      └──► Temporal EMA Smoothing (Σ_smooth)
  │      └──► Jacobi Eigendecomposition & Phase Alignment
  │      └──► Whitening Matrix Synthesis (W = V · S · V^T, Bias b)
  │      └──► Upload (W, b) Uniforms to GPU
  │
  └──► Upload RAW Buffer to GL_R16UI Texture
         └──► Fragment Shader (decorstr.frag)
                ├── Bilinear Bayer Demosaic (RGGB/GBRG/BGGR/GRBG)
                ├── Per-Sensor White Balance & Baseline Calibration
                ├── Forward Color Space Transform (LAB / YUV / RGB)
                ├── Apply Whitening Transform (y = W · x + b)
                ├── Inverse Color Space Transform
                └── Display Gamma Correction (sRGB)
```

---

## Building & Installation

### Prerequisites
- **Android SDK**: API 34+ (minSdk 26, targetSdk 34)
- **Android NDK**: Version `27.2.12479018`
- **JDK**: Java 21 (`OpenJDK 21`)
- **CMake**: Version `3.22.1+`
- **Device Requirements**: Camera2 RAW-capable Android smartphone (`REQUEST_AVAILABLE_CAPABILITIES_RAW`).

### Build via Gradle
```bash
# Set Java 21 environment
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License
MIT License. See `LICENSE` for details.
