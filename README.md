# PanoStitch

Open-source Android panorama stitcher (Kotlin · Jetpack Compose · OpenCV 4.10).

## Features
- Multi-photo import via the system Photo Picker (up to 40), EXIF-aware
- Panorama and Flat/Scans modes, with automatic image ordering
- Tunable matching detail, confidence, input/output size, wave correction
- Auto-crop of black borders, JPEG (quality slider) or lossless PNG
- Pinch-to-zoom viewer, save to Pictures/PanoStitch, share sheet
- No permissions required (minSdk 29)

## Build
Open the folder in Android Studio (Ladybug or newer). It will sync Gradle and
generate the wrapper. Run on a device (arm64 recommended).

## Tips
- Overlap shots by ~30%, keep exposure locked, rotate around your position.
- "Not enough overlap" → lower Match confidence to ~0.6.
- Out-of-memory → reduce Input size or photo count.

## Ideas to extend
Live CameraX capture with a guidance overlay, exposure/multi-band blending
settings, spherical (360°) export, tiled processing for gigapixel output.
