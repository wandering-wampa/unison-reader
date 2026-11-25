# Third-party components

| Component | Version / revision | License | Purpose |
| --- | --- | --- | --- |
| ONNX Runtime (Android) | 1.31.1, `com.microsoft.onnxruntime:onnxruntime-android` | MIT | CPU inference for the speech model (int8 kernels, multi-threaded) |
| NVIDIA QuartzNet 15x5 English, `stt_en_quartznet15x5` 1.0.0rc1 | int8 ONNX export, SHA-256 98c3918f0c5ee620e9fc894cafc50cbb925bdddb1f153c959c1568d2319228ba | CC BY 4.0 | Bundled character-level CTC acoustic model; no runtime downloads |
| Honomiya model export | release `models-v1` (github.com/Natsume-197/Honomiya) | MIT (tooling); model stays CC BY 4.0 | ONNX conversion with the mel-spectrogram frontend fused into the graph and int8 quantization |
| libmobi | v0.12, 85dcfe803fc2a21020ddcf15c3eb66b93d388add | LGPL-3.0-or-later | Native DRM-free MOBI/KF8 reconstruction and metadata |
| Foliate JS | 78914aef4466eb960965702401634c2cb348e9b1 | MIT | Original EPUB/MOBI/KF8 rendering and navigation |
| zip.js / fflate | copies pinned by Foliate revision | BSD-3-Clause / MIT | Browser-side archive/decompression support |
| AndroidX Media3 | 1.8.0 | Apache-2.0 | ExoPlayer, media session, foreground playback, notification and headset controls |
| Material Components | 1.13.0 | Apache-2.0 | Native Material 3 widgets and reading sheets with a custom bookish palette |
| Literata | 0c2761b727a1b3a7cffd313c37f0f5163dfc7a63 | SIL OFL-1.1 | Bundled regular, italic, and semibold reading fonts; original font files |
| android-pagecurl (Harri Smatt) | 7a2c8f152bb4f1b0de3b1aa72b3cb79e1fe8e3bd | Apache-2.0 | CurlMesh/CurlPage cylindrical XYZ mesh, texture mapping, shading and shadows |
| AndroidX AppCompat / DocumentFile | 1.7.1 / 1.1.0 | Apache-2.0 | Native UI and Storage Access Framework |
| jsoup | 1.21.2 | MIT | Safe EPUB metadata/HTML parsing and immutable text index |

Primary source repositories: [ONNX Runtime](https://github.com/microsoft/onnxruntime), [QuartzNet on NGC](https://catalog.ngc.nvidia.com/orgs/nvidia/models/quartznet15x5), [Honomiya](https://github.com/Natsume-197/Honomiya), [libmobi](https://github.com/bfabiszewski/libmobi), [Foliate JS](https://github.com/johnfactotum/foliate-js), [Media3](https://github.com/androidx/media), [Material Components](https://github.com/material-components/material-components-android), [jsoup](https://github.com/jhy/jsoup).

Native/reading-engine notices are included in `app/src/main/assets/licenses`. Android dependency license metadata is also included by the build. libmobi is dynamically linked as `libmobi.so`; `Bootstrap.ps1` retrieves its exact unmodified source. The supplied CMake project rebuilds/relinks it for ARM64 and x86_64, and `adb install -r` accepts a rebuilt debug-signed APK. No restriction is imposed on modifying these libraries or reverse engineering for debugging modifications. Keep source/build instructions and licenses with any redistributed binary. The LGPL text incorporates GPLv3; both texts are included.

The speech model is a derivative of NVIDIA's CC BY 4.0 release: attribution, the license text, and the list of changes (ONNX conversion, fused mel frontend, int8 quantization) are bundled at `assets/licenses/quartznet-model-CC-BY-4.0.txt`. `Bootstrap.ps1` downloads the exact release asset and verifies its SHA-256. whisper.cpp and the ggml tiny.en weights are no longer used or bundled as of version 0.5.0; a stale `vendor/whisper` checkout can be deleted.

Test-only `lameenc` generates original sine-wave MP3 fixtures; it is not packaged in the app. libmobi's repository supplies genuine MOBI 6 and KF8 test files. Private Stephen King fixtures, their extracted content, screenshots, and transcripts are excluded from Git and APK assets.

The [android-pagecurl source](https://github.com/harism/android-pagecurl/tree/7a2c8f152bb4f1b0de3b1aa72b3cb79e1fe8e3bd) retains its copyright headers in `app/src/main/java/fi/harism/curl`. Local changes add GL texture cleanup, linear filtering, and optional non-power-of-two textures when supported by the GPU. HomescreenReader supplies its own TextureView/EGL lifecycle, gesture routing, and transactional EPUB preview/commit integration. Its license is bundled at `assets/licenses/android-pagecurl.txt`. No Moon+ Reader source or assets are used.
