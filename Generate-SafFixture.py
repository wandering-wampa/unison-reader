"""Create original, disposable EPUBs for emulator tests (document picker, shelf staging)."""
from pathlib import Path
import sys
import uuid
import zipfile

root = Path(__file__).parent


def make(out: Path, title: str, sentence: str):
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w") as z:
        z.comment = str(uuid.uuid4()).encode()  # A fresh import on repeated test runs.
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("book.opf", f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">{uuid.uuid4()}</dc:identifier><dc:title>{title}</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>')
        z.writestr("a.xhtml", f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head><body><p>{sentence}</p></body></html>')
    print(out)


if len(sys.argv) > 1 and sys.argv[1] == "shelves":
    # Shelf staging: an ebook-only book, and an ebook whose title matches the paired audio ZIP.
    make(root / "test-results/shelf-ebook.epub", "An Ebook Waiting for Audio", "This original sentence belongs to an ebook that has no audiobook yet.")
    make(root / "test-results/shelf-pair.epub", "shelf-pair", "This original sentence pairs with a tiny synthetic audiobook.")
    audio = root / "test-results/audio"
    with zipfile.ZipFile(root / "test-results/shelf-solo.zip", "w") as z:
        z.comment = str(uuid.uuid4()).encode()
        z.write(audio / "Track 1.mp3", "Track 1.mp3")
    with zipfile.ZipFile(root / "test-results/shelf-pair.zip", "w") as z:
        z.comment = str(uuid.uuid4()).encode()
        z.write(audio / "Track 1.mp3", "Track 1.mp3")
        z.write(audio / "Track 2.mp3", "Track 2.mp3")
    print("shelf fixtures ready")
else:
    make(root / "test-results/Reader-SAF.epub", "Reader SAF fixture", "This original sentence verifies a safe import from Downloads.")
