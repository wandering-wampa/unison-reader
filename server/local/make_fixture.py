"""Builds a throwaway library for Start-LocalServer.ps1: a tiny Calibre library (one EPUB reused under
several made-up titles), two short audiobooks of generated tones, a stand-in model file, and a database seeded with two
test accounts whose passwords are generated here and written to credentials.txt beside it. Everything goes
under build/local-server, which git ignores. Nothing here touches the real libraries or a deployed server."""
import hashlib, os, secrets, shutil, sqlite3, sys, time

root, epub = sys.argv[1], sys.argv[2]
ebooks, audio, data = (os.path.join(root, d) for d in ('ebooks', 'audiobooks', 'data'))
for d in (ebooks, audio, data):
    os.makedirs(d, exist_ok=True)
open(os.path.join(root, 'model.onnx'), 'ab').close()

BOOKS = [  # title, author, series, index, tags, year, description
    ('The Long Way Down', 'Ada Marsh', 'Harbour', 1, ['Mystery'], '2019', 'A harbour town, a missing ferryman, and a detective who would rather be fishing.'),
    ('The Short Way Up', 'Ada Marsh', 'Harbour', 2, ['Mystery'], '2021', 'The second Harbour mystery.'),
    ('Night Ferry', 'Ada Marsh', None, None, ['Mystery', 'Short stories'], '2016', 'Six crossings, six stories.'),
    ('Salt and Iron', 'Tomas Reed', None, None, ['Fantasy'], '2012', 'A blacksmith inherits a salt mine and the debt that comes with it.'),
    ('Glass Orchard', 'Tomas Reed', 'Orchard', 1, ['Fantasy'], '2018', 'The orchard grows glass, and somebody is stealing the harvest.'),
    ('A Quiet Year', 'Ines Calder', None, None, ['Memoir'], '2020', 'Twelve months in a house at the end of a road.'),
    ('Teh Mispelled Titel', 'ines calder', None, None, [], None, None),
]

meta = os.path.join(ebooks, 'metadata.db')
if not os.path.exists(meta):
    db = sqlite3.connect(meta)
    db.executescript('''
      CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, path TEXT, has_cover INTEGER DEFAULT 0, series_index REAL DEFAULT 1, pubdate TEXT);
      CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT UNIQUE); CREATE TABLE books_authors_link (book INTEGER, author INTEGER);
      CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT UNIQUE); CREATE TABLE books_series_link (book INTEGER, series INTEGER);
      CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT UNIQUE); CREATE TABLE books_tags_link (book INTEGER, tag INTEGER);
      CREATE TABLE ratings (id INTEGER PRIMARY KEY, rating INTEGER); CREATE TABLE books_ratings_link (book INTEGER, rating INTEGER);
      CREATE TABLE comments (book INTEGER, text TEXT);
      CREATE TABLE publishers (id INTEGER PRIMARY KEY, name TEXT UNIQUE); CREATE TABLE books_publishers_link (book INTEGER, publisher INTEGER);
      CREATE TABLE languages (id INTEGER PRIMARY KEY, lang_code TEXT UNIQUE); CREATE TABLE books_languages_link (book INTEGER, lang_code INTEGER);
      CREATE TABLE data (book INTEGER, format TEXT, name TEXT);
      INSERT INTO languages (id, lang_code) VALUES (1, 'eng');
    ''')
    def one(table, name):
        db.execute(f'INSERT OR IGNORE INTO {table} (name) VALUES (?)', (name,))
        return db.execute(f'SELECT id FROM {table} WHERE name = ?', (name,)).fetchone()[0]
    for i, (title, author, series, index, tags, year, about) in enumerate(BOOKS, 1):
        path = f'{author}/{title} ({i})'
        os.makedirs(os.path.join(ebooks, path), exist_ok=True)
        shutil.copyfile(epub, os.path.join(ebooks, path, 'book.epub'))
        db.execute('INSERT INTO books (id, title, path, series_index, pubdate) VALUES (?, ?, ?, ?, ?)', (i, title, path, index or 1, f'{year}-01-01' if year else '0101-01-01'))
        db.execute('INSERT INTO books_authors_link VALUES (?, ?)', (i, one('authors', author)))
        if series: db.execute('INSERT INTO books_series_link VALUES (?, ?)', (i, one('series', series)))
        for t in tags: db.execute('INSERT INTO books_tags_link VALUES (?, ?)', (i, one('tags', t)))
        if about: db.execute('INSERT INTO comments VALUES (?, ?)', (i, f'<p>{about}</p>'))
        db.execute('INSERT INTO books_languages_link VALUES (?, 1)', (i,))
        db.execute("INSERT INTO data VALUES (?, 'EPUB', 'book')", (i,))
    db.commit(); db.close()

catalog = os.path.join(data, 'catalog.db')
if not os.path.exists(catalog):
    db = sqlite3.connect(catalog)
    # The same statement the server runs; it finds the table already there and seeds nothing over it.
    db.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, hash TEXT NOT NULL DEFAULT '', salt TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'member', created INTEGER NOT NULL)")
    lines = []
    for uid, name, role in ((1, 'admin', 'admin'), (2, 'reader', 'member')):
        password, salt = secrets.token_urlsafe(12), secrets.token_hex(16)
        digest = hashlib.pbkdf2_hmac('sha256', password.encode(), salt.encode(), 120_000, 32).hex()
        db.execute('INSERT INTO users (id, name, hash, salt, role, created) VALUES (?, ?, ?, ?, ?, ?)', (uid, name, digest, salt, role, int(time.time() * 1000)))
        lines.append(f'{name} {password}')
    db.commit(); db.close()
    with open(os.path.join(root, 'credentials.txt'), 'w') as f:
        f.write('\n'.join(lines) + '\n')
# Two audiobooks with no ebook to pair with (so nothing tries to sync them), the audio-only test books: tones,
# a different pitch for each track, written as WAV. The scanner reads their lengths through ffprobe; a machine without
# one gets a stand-in from Start-LocalServer.ps1 (server/local/ffprobe_stub.cs).
import array, math, struct, wave, zlib
AUDIO = [  # author, title, tracks as (file name, seconds, pitch), the cover's sky (top, bottom) and its light
    ('Tomas Reed', 'The Tin Lighthouse', [('01 - The Keeper', 150, 330), ('02 - The Storm', 180, 392), ('03 - The Lamp', 120, 440)], ((12, 44, 74), (4, 16, 30)), (255, 226, 150)),
    # Not by Ines Calder: check_phase9.py counts her books.
    ('Mira Voss', 'Harbour Lights', [('Harbour Lights', 240, 262)], ((92, 38, 84), (232, 120, 60)), (255, 244, 214)),
]

def cover(path, sky, light, size=360):
    """A cover made here (a sky, a light over water) so the clients have a picture to show: a plain RGB PNG."""
    top, bottom = sky
    rows = bytearray()
    for y in range(size):
        rows.append(0)
        t = y / (size - 1)
        for x in range(size):
            c = [top[i] + (bottom[i] - top[i]) * t for i in range(3)]
            d = math.hypot(x - size * .62, y - size * .34) / (size * .15)  # the light, with a soft edge
            glow = 1 if d < 1 else max(0, 1 - (d - 1) * 1.6) * .55
            if y > size * .72: glow = .35 * max(0, 1 - abs(x - size * .62) / (size * .1)) * (1 if (y // 5) % 2 else .4)  # its path on the water
            rows += bytes(int(c[i] + (light[i] - c[i]) * glow) for i in range(3))
    def chunk(kind, data): return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(bytes(rows), 9)) + chunk(b'IEND', b''))

for author, title, tracks, sky, light in AUDIO:
    folder = os.path.join(audio, author, title)
    if os.path.isdir(folder):
        if not os.path.exists(os.path.join(folder, 'cover.png')):
            cover(os.path.join(folder, 'cover.png'), sky, light)
            # The scanner looks for a cover only when the audio files changed: touch them so it looks again.
            for name in os.listdir(folder):
                if name.endswith('.wav'): os.utime(os.path.join(folder, name))
        continue
    os.makedirs(folder)
    cover(os.path.join(folder, 'cover.png'), sky, light)
    for name, seconds, pitch in tracks:
        # One second of sound: a quarter-second note, then quiet. Repeated, so a place in the track can be heard as a beat.
        rate = 8000
        second = array.array('h', (int(9000 * math.sin(2 * math.pi * pitch * i / rate) * min(1, i / 200, (rate // 4 - i) / 200)) if i < rate // 4 else 0 for i in range(rate)))
        with wave.open(os.path.join(folder, name + '.wav'), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
            w.writeframes(second.tobytes() * seconds)
print('fixture ready in', root)
