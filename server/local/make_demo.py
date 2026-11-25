"""Builds a demo library of public-domain books for Start-LocalServer.ps1 -Demo: thirteen ebooks from Standard
Ebooks (their editions and covers are dedicated to the public domain) and two LibriVox recordings (public domain)
that go with two of them, so the server has real pairs to sync. The pictures in the README come from this library.

    python server/local/make_demo.py <root> <downloads>

<downloads> keeps the fetched files between runs (about 250 MB); <root> gets a Calibre-shaped library, an
audiobook folder and a database seeded with two test accounts whose generated passwords are written to
credentials.txt. Both folders belong under build/, which Git ignores. Syncing needs ffmpeg and ffprobe on PATH."""
import hashlib, html, json, os, re, secrets, shutil, sqlite3, sys, time, urllib.request, zipfile

root, downloads = sys.argv[1], sys.argv[2]
ebooks, audio, data = (os.path.join(root, d) for d in ('ebooks', 'audiobooks', 'data'))
for d in (ebooks, audio, data, os.path.join(downloads, 'epub'), os.path.join(downloads, 'audio')):
    os.makedirs(d, exist_ok=True)

BOOKS = [  # Standard Ebooks path, title, author, series, index, first published, genres
    ('arthur-conan-doyle/a-study-in-scarlet', 'A Study in Scarlet', 'Arthur Conan Doyle', 'Sherlock Holmes', 1, 1887, ['Mystery']),
    ('arthur-conan-doyle/the-sign-of-the-four', 'The Sign of the Four', 'Arthur Conan Doyle', 'Sherlock Holmes', 2, 1890, ['Mystery']),
    ('arthur-conan-doyle/the-adventures-of-sherlock-holmes', 'The Adventures of Sherlock Holmes', 'Arthur Conan Doyle', 'Sherlock Holmes', 3, 1892, ['Mystery', 'Short stories']),
    ('arthur-conan-doyle/the-memoirs-of-sherlock-holmes', 'The Memoirs of Sherlock Holmes', 'Arthur Conan Doyle', 'Sherlock Holmes', 4, 1894, ['Mystery', 'Short stories']),
    ('arthur-conan-doyle/the-hound-of-the-baskervilles', 'The Hound of the Baskervilles', 'Arthur Conan Doyle', 'Sherlock Holmes', 5, 1902, ['Mystery']),
    ('arthur-conan-doyle/the-return-of-sherlock-holmes', 'The Return of Sherlock Holmes', 'Arthur Conan Doyle', 'Sherlock Holmes', 6, 1905, ['Mystery', 'Short stories']),
    ('h-g-wells/the-time-machine', 'The Time Machine', 'H. G. Wells', None, None, 1895, ['Science fiction']),
    ('h-g-wells/the-war-of-the-worlds', 'The War of the Worlds', 'H. G. Wells', None, None, 1898, ['Science fiction']),
    ('jane-austen/pride-and-prejudice', 'Pride and Prejudice', 'Jane Austen', None, None, 1813, ['Fiction']),
    ('bram-stoker/dracula', 'Dracula', 'Bram Stoker', None, None, 1897, ['Horror']),
    ('mary-shelley/frankenstein', 'Frankenstein', 'Mary Shelley', None, None, 1818, ['Horror', 'Science fiction']),
    ('lewis-carroll/alices-adventures-in-wonderland/john-tenniel', "Alice's Adventures in Wonderland", 'Lewis Carroll', None, None, 1865, ['Fantasy', 'Children']),
    ('robert-louis-stevenson/treasure-island/milo-winter', 'Treasure Island', 'Robert Louis Stevenson', None, None, 1883, ['Adventure']),
]
RECORDINGS = [  # Internet Archive item (LibriVox), title, author, the volunteer who read it
    ('studyinscarlet_0811_librivox', 'A Study in Scarlet', 'Arthur Conan Doyle', 'Laurie Anne Walden'),
    ('timemachine_sjm_librivox', 'The Time Machine', 'H. G. Wells', 'Mark Nelson'),
]


def fetch(url, to):
    if os.path.exists(to) and os.path.getsize(to) > 0:
        return
    print('fetching', url)
    request = urllib.request.Request(url, headers={'User-Agent': 'unison-demo-library'})
    with urllib.request.urlopen(request, timeout=600) as r, open(to + '.part', 'wb') as f:
        shutil.copyfileobj(r, f)
    os.replace(to + '.part', to)


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
    for i, (path, title, author, series, index, year, genres) in enumerate(BOOKS, 1):
        name = path.replace('/', '_')
        source = os.path.join(downloads, 'epub', name + '.epub')
        fetch(f'https://standardebooks.org/ebooks/{path}/downloads/{name}.epub?source=download', source)
        folder, file = f'{author}/{title} ({i})', f'{title} - {author}'
        os.makedirs(os.path.join(ebooks, folder), exist_ok=True)
        shutil.copyfile(source, os.path.join(ebooks, folder, file + '.epub'))
        about, cover = None, 0
        with zipfile.ZipFile(source) as z:
            opf = re.search(r'full-path="([^"]+)"', z.read('META-INF/container.xml').decode()).group(1)
            package = z.read(opf).decode('utf-8')
            m = re.search(r'<dc:description[^>]*>(.*?)</dc:description>', package, re.S)
            if m: about = re.sub(r'<[^>]+>', '', html.unescape(m.group(1))).strip()
            m = re.search(r'<item[^>]*href="([^"]+\.jpe?g)"[^>]*properties="[^"]*cover-image', package)
            if m:
                with open(os.path.join(ebooks, folder, 'cover.jpg'), 'wb') as f:
                    f.write(z.read(os.path.dirname(opf) + '/' + m.group(1)))
                cover = 1
        db.execute('INSERT INTO books (id, title, path, has_cover, series_index, pubdate) VALUES (?, ?, ?, ?, ?, ?)', (i, title, folder, cover, index or 1, f'{year:04d}-01-01'))
        db.execute('INSERT INTO books_authors_link VALUES (?, ?)', (i, one('authors', author)))
        if series: db.execute('INSERT INTO books_series_link VALUES (?, ?)', (i, one('series', series)))
        for g in genres: db.execute('INSERT INTO books_tags_link VALUES (?, ?)', (i, one('tags', g)))
        if about: db.execute('INSERT INTO comments VALUES (?, ?)', (i, about))
        db.execute('INSERT INTO books_publishers_link VALUES (?, ?)', (i, one('publishers', 'Standard Ebooks')))
        db.execute('INSERT INTO books_languages_link VALUES (?, 1)', (i,))
        db.execute("INSERT INTO data VALUES (?, 'EPUB', ?)", (i, file))
    db.commit(); db.close()

for item, title, author, reader in RECORDINGS:
    folder = os.path.join(audio, author, title)
    if os.path.exists(os.path.join(folder, 'metadata.json')):
        continue
    kept = os.path.join(downloads, 'audio', item)
    os.makedirs(kept, exist_ok=True)
    os.makedirs(folder, exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(f'https://archive.org/metadata/{item}', headers={'User-Agent': 'unison-demo-library'}), timeout=60) as r:
        files = sorted(f['name'] for f in json.load(r)['files'] if f['name'].endswith('_64kb.mp3'))
    for part, name in enumerate(files, 1):
        fetch(f'https://archive.org/download/{item}/{name}', os.path.join(kept, name))
        # The file's name is what the player shows for the track, so give it a readable one.
        shutil.copyfile(os.path.join(kept, name), os.path.join(folder, f'Part {part}.mp3'))
    # The sidecar Audiobookshelf would write: it names the reader, which the files' own tags do not.
    with open(os.path.join(folder, 'metadata.json'), 'w', encoding='utf-8') as f:
        json.dump({'title': title, 'authors': [author], 'narrators': [reader], 'publisher': 'LibriVox', 'language': 'English'}, f, indent=2)

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
print('demo library ready in', root)
