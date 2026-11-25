"""Offline checks of a built APK: the speech model and ONNX Runtime (present and pinned in the standalone build,
absent from the default, server-reading build), fonts and licences, private-fixture exclusion, 16 KB ELF alignment."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

apk = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk")
expected_model = "98c3918f0c5ee620e9fc894cafc50cbb925bdddb1f153c959c1568d2319228ba"
with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    # The standalone build (-Pstandalone) carries the speech model; the default build must carry neither it nor ONNX Runtime.
    standalone = "assets/models/quartznet15x5-en.int8.onnx" in names
    if standalone:
        model = z.read("assets/models/quartznet15x5-en.int8.onnx")
        assert hashlib.sha256(model).hexdigest() == expected_model
        assert z.getinfo("assets/models/quartznet15x5-en.int8.onnx").compress_type == zipfile.ZIP_STORED, "model must be stored uncompressed"
    else:
        assert not any(n.startswith("assets/models/") for n in names), "the default build carries no model"
        assert not any("onnxruntime" in n for n in names if n.endswith(".so")), "the default build carries no ONNX Runtime"
        assert apk.stat().st_size < 40_000_000, "the default build should be small"
    assert not any(n.startswith("assets/models/") and n.endswith(".bin") for n in names), "whisper model must be gone"
    fonts = {
        "Literata-Regular.ttf": "0390890de9bb9d5862a6ba4125b82c61792ccc3d66b63e73eee75c1a16fcd208",
        "Literata-Italic.ttf": "198f70cc9a17bab578553fa274b81984d58c440efe26bc06f1d841c194b6691a",
        "Literata-SemiBold.ttf": "ee8f9413ebc974e1c1cfc76f6bdb9d08ddaadc66eeddd7320a65f8c581284d6d",
    }
    for name, expected in fonts.items():
        assert hashlib.sha256(z.read("assets/fonts/" + name)).hexdigest() == expected, name
    assert b"SIL OPEN FONT LICENSE" in z.read("assets/licenses/Literata-OFL.txt")
    assert b"Apache License" in z.read("assets/licenses/android-pagecurl.txt")
    assert b"Attribution 4.0 International" in z.read("assets/licenses/quartznet-model-CC-BY-4.0.txt")
    assert b"Microsoft Corporation" in z.read("assets/licenses/onnxruntime-MIT.txt")
    forbidden = ("test-fixtures/", "test-results/", "fixture.epub", "fixture.zip", "11-22-63", "11_22_63")
    assert not any(any(x in n.lower() for x in forbidden) for n in names)
    assert not any(n.startswith("assets/") and n.lower().endswith((".epub", ".mobi", ".azw3", ".m4b", ".mp3", ".zip")) for n in names)
    for name in names:
        if name.endswith(".dex"):
            data = z.read(name)
            assert not any(test in data for test in (b"ReaderInstrumentation", b"TestDocuments", b"BookishChecks", b"CurlChecks"))
    native = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
    assert {n.split("/")[1] for n in native} == {"arm64-v8a", "x86_64"}
    if standalone: assert all(any(n.endswith("/libonnxruntime.so") and n.startswith(f"lib/{abi}/") for n in native) for abi in ("arm64-v8a", "x86_64"))
    assert not any(n.endswith("/libwhisper.so") or n.endswith("/libggml.so") for n in native)
    for name in native:
        data = z.read(name)
        assert data[:6] == b"\x7fELF\x02\x01", name
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        for i in range(count):
            header = offset + i * entry_size
            if struct.unpack_from("<I", data, header)[0] == 1:
                assert struct.unpack_from("<Q", data, header + 48)[0] >= 16384, name
    print(json.dumps({
        "apk": str(apk), "bytes": apk.stat().st_size,
        "sha256": hashlib.file_digest(apk.open("rb"), "sha256").hexdigest(),
        "edition": "standalone" if standalone else "server", "model_sha256": expected_model if standalone else None, "native_libraries": native,
        "literata_fonts": "PASS (pinned hashes and bundled OFL license)",
        "licenses": [n for n in names if n.startswith("assets/licenses/")],
        "private_fixture_and_test_code_check": "PASS",
        "native_16kb_alignment": "PASS"
    }, indent=2))
