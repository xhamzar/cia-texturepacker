# Cia TexturePacker

![Build](https://github.com/xhamzar/cia-texturepacker/actions/workflows/build.yml/badge.svg)

Aplikasi Android untuk menggabungkan banyak gambar sprite menjadi satu **sprite sheet / texture atlas**, lengkap dengan pixel editor built-in. Dibuat untuk kebutuhan game development.

## Screenshots

| Images | Atlas | Settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/02-images-tab.jpg" width="250"> | <img src="docs/screenshots/03-atlas-tab.jpg" width="250"> | <img src="docs/screenshots/04-settings-tab.jpg" width="250"> |

| Pixel Editor | Menu |
|:---:|:---:|
| <img src="docs/screenshots/05-pixel-editor.jpg" width="250"> | <img src="docs/screenshots/01-drawer-menu.jpg" width="250"> |

## Fitur

- **Impor Gambar** — impor banyak gambar sekaligus dari file picker
- **Auto-Pisah Sprite** — pisahkan gambar multi-sprite otomatis (connected-components pada alpha channel)
- **Auto-Transparan** — deteksi warna background dari empat sudut, jadikan transparan
- **Packing** — mode Otomatis (Shelf) dan Grid (sel 32/64/128/256)
- **Trim Transparent** — potong area kosong di sekitar sprite
- **Pixel Editor** — edit per-pixel (kuas, penghapus, undo 20x, zoom 1–64x)
- **Export** — PNG + data JSON (Phaser/PixiJS), XML, plist (Cocos2d), TXT
- **Text Editor** — lihat & edit data atlas langsung dengan syntax highlighting
- **Multi-select** — pilih banyak sprite untuk hapus sekaligus

## Download

APK terbaru tersedia di halaman [Releases](https://github.com/xhamzar/cia-texturepacker/releases). Setiap push tag `v*` otomatis membangun dan merilis APK baru via GitHub Actions.

## Build

**Kebutuhan:**
- Android Studio (terbaru) atau JDK 17 + Gradle 8.9
- Android SDK (compileSdk 35)

**Cara build:**
```bash
# Via Android Studio: buka project lalu Run > Run 'app'
# Via command line:
./gradlew :app:assembleDebug
```

APK hasil build: `app/build/outputs/apk/debug/app-debug.apk`

## Struktur Project

```
app/src/main/java/com/cia/texturepacker/
├── MainActivity.kt          # Activity utama + drawer
├── ImagesFragment.kt        # Tab Images
├── AtlasFragment.kt         # Tab Atlas
├── SettingsFragment.kt      # Tab Settings
├── PixelEditorActivity.kt   # Pixel editor
├── PixelCanvasView.kt       # Custom view canvas
├── PackViewModel.kt         # State & logic packing
├── SpritePacker.kt          # Algoritma packing
├── BackgroundRemover.kt     # Auto-transparan
├── SpriteSplitter.kt        # Auto-pisah sprite
├── DataEditorActivity.kt    # Text editor data atlas
└── ...
```

## Lisensi

Project pribadi — all rights reserved.
