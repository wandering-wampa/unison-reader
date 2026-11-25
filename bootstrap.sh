#!/bin/sh
# Fetches the pinned third-party sources and the speech model; with --install-native-tools also the NDK and CMake.
# Bootstrap.ps1 does the same on Windows and holds the same pins: change them in both.
set -eu
cd "$(dirname "$0")"

install_native=0
for argument in "$@"; do
    case "$argument" in
        --install-native-tools) install_native=1 ;;
        *) echo "Usage: sh ./bootstrap.sh [--install-native-tools]" >&2; exit 2 ;;
    esac
done

fetch_source() { # name url ref commit
    destination="vendor/$1"
    if [ ! -e "$destination" ]; then
        git init -q "$destination"
        git -C "$destination" remote add origin "$2"
        if ! git -C "$destination" fetch -q --depth 1 origin "$3"; then
            echo "Download failed: $1" >&2
            exit 1
        fi
        git -C "$destination" checkout -q --detach FETCH_HEAD
    fi
    actual=$(git -C "$destination" rev-parse HEAD)
    if [ "$actual" != "$4" ]; then
        echo "Unexpected source revision in $destination. Preserve local changes and check out $4." >&2
        exit 1
    fi
}

mkdir -p vendor app/src/main/assets/models
fetch_source libmobi https://github.com/bfabiszewski/libmobi.git v0.12 85dcfe803fc2a21020ddcf15c3eb66b93d388add
fetch_source foliate-js https://github.com/johnfactotum/foliate-js.git 78914aef4466eb960965702401634c2cb348e9b1 78914aef4466eb960965702401634c2cb348e9b1

# Speech model: NVIDIA QuartzNet 15x5 English (CC BY 4.0), int8 ONNX export with the mel
# frontend inside the graph, from the Honomiya models-v1 release.
model=app/src/main/assets/models/quartznet15x5-en.int8.onnx
model_hash=98c3918f0c5ee620e9fc894cafc50cbb925bdddb1f153c959c1568d2319228ba

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d ' ' -f 1
    else
        shasum -a 256 "$1" | cut -d ' ' -f 1
    fi
}

if [ ! -e "$model" ]; then
    curl -fL --retry 3 -o "$model.pending" 'https://github.com/Natsume-197/Honomiya/releases/download/models-v1/quartznet15x5-en.model.int8.onnx'
    if [ "$(sha256_of "$model.pending")" != "$model_hash" ]; then
        rm -f "$model.pending"
        echo "Speech model checksum failed." >&2
        exit 1
    fi
    mv "$model.pending" "$model"
fi
if [ "$(sha256_of "$model")" != "$model_hash" ]; then
    echo "Speech model checksum failed." >&2
    exit 1
fi

if [ "$install_native" = 1 ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "ANDROID_HOME is not set: point it at the Android SDK to use --install-native-tools." >&2
        exit 1
    fi
    sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$sdkmanager" ]; then
        echo "sdkmanager not found at $sdkmanager: install the Android SDK command-line tools first." >&2
        exit 1
    fi
    "$sdkmanager" 'ndk;27.2.12479018' 'cmake;3.22.1'
fi
echo "Pinned native library, reading engine, and offline speech model are ready."
