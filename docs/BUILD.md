# Build and install

The build needs JDK 21, Android SDK 36, NDK 27.2.12479018 and CMake 3.22.1; Gradle 8.13, AGP 8.13.2 and Kotlin 2.2.21 come through the wrapper. The scripts are PowerShell and were written on Windows.

`Enter-Android.ps1` puts the tools on PATH for one terminal. It expects one folder that holds the JDK (`jdk-21*`) and the SDK (`sdk`), and keeps the Gradle cache, the emulator images and the debug signing key beside them. It finds that folder from the `UNISON_ANDROID_TOOLS` environment variable, or else as the folder above `sdk.dir` in `local.properties` (the file at the top of the checkout that Git ignores).

```powershell
.\Bootstrap.ps1 -InstallNativeTools   # first build: pinned third-party sources and the speech model
. .\Enter-Android.ps1
.\gradlew.bat assembleDebug
adb -e install -r app\build\outputs\apk\debug\app-debug.apk   # emulator; -d for the phone
```

The default build reads and listens from a UnisonServer and is about 25 MB (debug-signed, for Android 15 and later): the server does the sentence sync, so the speech model and ONNX Runtime are not in it, and importing files is off. Books imported and synced on the device by an earlier version stay readable. Install with `install -r`; never uninstall, so the library and positions stay.

`local.properties` may carry `unison.defaultServer=your.server.address`, which the sign-in form of your own builds then starts with. `-PpublicBuild` leaves it out, for an APK you hand to other people.

`.\gradlew.bat :app:assembleDebug -Pstandalone` builds the app that also imports books and syncs them on the device (about 121 MB: the 21 MB speech model plus ONNX Runtime for ARM64 and x86_64). Both editions come out as `app-debug.apk`, and the packager patches the previous APK in place: delete `app\build\outputs\apk\debug\app-debug.apk` and `app\build\intermediates\incremental\packageDebug` when changing edition, or the file keeps its old size (`Release.ps1` does this itself). `python Verify-Apk.py` says which edition an APK is and checks it accordingly.

`Bootstrap.ps1` fetches `vendor/` (Foliate-js at a pinned commit, native sources) and the model, checking hashes. `vendor/`, models, build output and the test books are not in Git. `Start-Emulator.ps1` opens the `HomescreenReader_API36` emulator with sound; `-Headless` runs it silently for tests.

## Server

`.\Start-LocalServer.ps1` runs the server on this machine against a throwaway library (seven made-up ebooks cut from the EPUB in `test-fixtures/books` and two short audiobooks of generated tones; a machine without ffprobe gets a stand-in for it, `server\local\ffprobe_stub.cs`) at `http://localhost:8099`, with two test accounts whose generated passwords are in `build\local-server\credentials.txt`: the place to try server and web changes before a deploy. `python server\local\check_phase9.py [--online]` exercises the lists, the wanted list and the metadata overlay against it.

`.\Start-LocalServer.ps1 -Demo` runs it against a demo library instead: thirteen public-domain ebooks from Standard Ebooks and two LibriVox recordings, fetched on the first run (about 250 MB) and kept under `build\demo-downloads`. The two pairs are synced for real, which takes a minute or two and needs ffmpeg and ffprobe on PATH and the speech model from `Bootstrap.ps1`. `python server\local\seed_demo.py http://localhost:8099 build\demo-server` then gives its `admin` a shelf and a year of made-up reading. It is the quickest way to see narration follow in a browser without a library of your own, and the README's browser pictures come from it.

`.\Deploy-Server.ps1 -Target <ssh host>` builds `:server:installDist`, ships it to a Docker host over SSH and brings up the `unisonserver` stack (Temurin 21 JRE, ffmpeg, the model). The audiobook and Calibre folders are mounted read-only and are never written to. The web client is packaged inside the server jar and served at the root. Running it, the settings, the design and the API are in [SERVER.md](SERVER.md).

## Linux and macOS

The apps build with plain Gradle, without the PowerShell scripts. This path is what the repository's workflow runs on Ubuntu on every push; macOS has not been tried.

You need JDK 21 and the Android SDK, found through `ANDROID_HOME` or `sdk.dir` in `local.properties`. The build asks for platform 36 (`compileSdk`), the NDK 27.2.12479018 and CMake 3.22.1, as set in `app/build.gradle.kts`; the SDK's command-line tools must be in `cmdline-tools/latest` for the next step to install the last two. The Android Gradle plugin downloads the build-tools it needs if the SDK's licences are accepted (`sdkmanager --licenses`).

```sh
sh ./bootstrap.sh --install-native-tools   # pinned third-party sources, the speech model, the NDK and CMake
sh ./gradlew :app:assembleDebug -PpublicBuild --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk        # add -e or -d to pick one device
```

`bootstrap.sh` does what `Bootstrap.ps1` does and holds the same pins; leave out `--install-native-tools` if the NDK and CMake are already installed. Run the wrapper through `sh`, since it may arrive without its executable bit. `-PpublicBuild` ignores any server address in `local.properties`.

`Enter-Android.ps1`, `Start-Emulator.ps1`, `Test-Emulator.ps1`, `Tap-Emulator.ps1`, `Start-LocalServer.ps1`, `Deploy-Server.ps1`, `Test-Compose.ps1` and `Release.ps1` are Windows tools for developing the project and have no counterpart here. The server needs none of them: [SERVER.md](SERVER.md) covers "Running it" and "Building the image from source", and `sh ./gradlew --configure-on-demand :server:installDist` builds it without the Android SDK.

## Modules

| Module | What it is |
|---|---|
| `app` | The Android app (Kotlin, Media3, Foliate-js, ONNX Runtime). Package id `dev.homescreenreader`, unchanged from the project's earlier name so installed libraries carry over. |
| `core` | Shared Kotlin JVM code: book model, EPUB parsing, the alignment engine. |
| `server` | UnisonServer (Ktor 3, SQLite): scanning, pairing, alignment, books, audio, positions, accounts, statistics, the web client. |
| `server/src/main/resources/web` | The web client. Plain HTML, CSS and JavaScript, no build step. Foliate-js is copied in at build time. |
