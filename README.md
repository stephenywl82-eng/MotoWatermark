# Moto Watermark

[![Release](https://img.shields.io/github/v/release/stephenywl82-eng/MotoWatermark?style=flat-square)](https://github.com/stephenywl82-eng/MotoWatermark/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/stephenywl82-eng/MotoWatermark/total?style=flat-square)](https://github.com/stephenywl82-eng/MotoWatermark/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)

Add authentic Motorola-style watermarks to your photos — device model, camera parameters, date, and owner handle, laid out to match Motorola's own camera output.

> 中文说明见下方 [Chinese section](#中文说明).

## Features

- **Six watermark styles** — Standard, Corner, Classic, Transparent, F1, and Pure (transparent background, white/black text).
- **Signature script** — renders the handwritten `signature` wordmark for Signature-series models, pixel-aligned to the reference.
- **Device model**
  - Auto-detects the model code from EXIF and maps it to a marketing name.
  - Manual picker with free-text input plus a searchable preset list — add any model (e.g. `edge 80 ultra`) without recompiling.
  - Import an external JSON device library to replace the built-in mapping.
- **Camera parameters** — focal length, aperture, shutter speed, ISO, plus date and an `@owner` handle; each can be toggled.
- **Accurate typography** — strict baseline alignment between the Motorola wordmark, device text, and signature; sizes tuned against official reference images.
- **Material 3 Expressive UI** — dynamic color (Material You), capsule chips, expressive shapes and type, light/dark theme, and English / 简体中文 locales.
- **Export** — save as PNG or JPEG, share directly.

## Download

Grab the latest signed APK from the [Releases page](https://github.com/stephenywl82-eng/MotoWatermark/releases/latest).

Requires Android 8.0 (API 26) or newer.

## Device library format

The device library is a simple JSON object mapping a model code (or full model name) to the name shown on the watermark:

```json
{
  "XT2603": "Signature",
  "XT2653": "Razr 70 Ultra",
  "motorola signature": "Signature"
}
```

Open **Model → Import JSON** in the app to load a file. Imported entries are merged over the built-in table and persisted locally. **Reset** restores the built-in list. The built-in library lives at `app/src/main/assets/devices.json`.

Names containing `signature` are rendered with the script wordmark; all other names use the default bold font.

## Building

Android Studio (Giraffe+) or Gradle 8.13 with the Android Gradle Plugin 8.2.2.

```bash
./gradlew :app:assembleRelease
```

The release build is signed; place your `release.jks` and configure `keystore.properties` (see `app/build.gradle.kts`) before building.

## Tech stack

Kotlin · Android Views + View Binding · Material Components (Material 3) · AppCompat · CameraX · ExifInterface · Palette · Kotlin Coroutines · R8

## Third-party

- [Montserrat](https://fonts.google.com/specimen/Montserrat) font (SIL Open Font License 1.1), bundled in `app/src/main/assets/`.
- Material Components for Android — Apache License 2.0.

## Author

史蒂文 (stephenywl82-eng)

## License

[MIT](LICENSE)

---

## 中文说明

给照片添加摩托罗拉风格的相机水印：机型、拍摄参数、日期和署名，排版对齐摩托罗拉原生相机输出。

功能：6 种水印样式、Signature 手签花体、EXIF 自动识别机型 + 手动输入/预设选择、外部 JSON 机型库、参数/日期/署名开关、M3 Expressive 界面、浅色/深色主题、中英双语、PNG/JPEG 导出与分享。

下载：前往 [Releases](https://github.com/stephenywl82-eng/MotoWatermark/releases/latest) 获取最新 APK，需 Android 8.0 及以上。

机型库：一个 JSON 对象，键为机型代号或完整型号名，值为水印显示名（格式见上文）。在「机型 → Import JSON」导入，导入项会覆盖内置表并本地保存；「重置」恢复内置列表。名称含 `signature` 时用花体渲染，其余用默认粗体。

作者：史蒂文
