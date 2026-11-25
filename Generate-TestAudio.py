"""Generate original tones; requires lameenc (test-only, never an APK dependency)."""
import array
import math
import pathlib
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).parent / "test-results/python"))
import lameenc

out = pathlib.Path(__file__).parent / "test-results/audio"
out.mkdir(parents=True, exist_ok=True)
for index, frequency in [(2, 440), (10, 660), (1, 330)]:
    pcm = array.array("h", (int(2000 * math.sin(2 * math.pi * frequency * n / 44100)) for n in range(44100 * 3)))
    encoder = lameenc.Encoder()
    encoder.set_bit_rate(64)
    encoder.set_in_sample_rate(44100)
    encoder.set_channels(1)
    (out / f"Track {index}.mp3").write_bytes(encoder.encode(pcm.tobytes()) + encoder.flush())
with zipfile.ZipFile(out / "tones.zip", "w") as archive:
    for file in out.glob("*.mp3"):
        archive.write(file, file.name)
