# UnisonServer

The service that owns the library. It reads your ebooks and audiobooks where they already are, pairs each
ebook with its audiobook, works out when every sentence is spoken, and serves books, audio, places, accounts
and the web client to the Android app and to browsers. It runs in Docker on any machine that is always on.

## What it needs

Two folders of books, and somewhere to keep its own data.

| | What | Layout |
|---|---|---|
| Ebooks | A Calibre library, or a folder of EPUBs | A Calibre library is Calibre's own folder, `Author/Title (id)/Title - Author.epub`, with `metadata.db` at the top; titles, authors, series and covers come from `metadata.db`. Without a `metadata.db`, every `.epub` under the folder is read, however it is arranged, and its title, authors, series, description and cover come from the EPUB itself. EPUBs are what is read in both cases. |
| Audiobooks | A folder of audiobooks | `Author/Title/` with the audio inside: M4B, M4A, MP3, FLAC, OGG, Opus, AAC or WAV, several files in numeric order. This is the layout Audiobookshelf uses, and its `metadata.json` beside the audio is read when it is there. |
| Data | An empty folder to begin with | The catalogue database, alignments, covers and backups. Everything the server writes goes here. |

Neither Calibre nor Audiobookshelf has to be running: only the folders matter, and both are mounted
read-only (see "The libraries are never written to" below). A book with one half is still served: an ebook
alone can be read and an audiobook alone can be played. Following the narration needs both, and the book has to be in English.

The machine can be anything that runs Docker; it has been run on x86-64 Linux. Aligning a book is the heavy
part and happens once: a worker runs at about 100 times real time on a six-core laptop processor (i5-11400H),
so a thirty-hour audiobook takes about twenty minutes of one worker. The speech model is English and 21 MB, and each
worker holds one chapter of audio in memory (under 200 MB for an hour-long chapter).

## Running it

The server runs as one container from the image `ghcr.io/wandering-wampa/unison-reader`, published for x86-64 and
ARM64. Docker with the Compose plugin is all the machine needs: no JDK, Android SDK or other tools, on Linux,
macOS or Windows.

1. Make an empty folder for the stack, open a terminal in it and download the two files from `server/deploy/` in
   the public repository, `compose.yaml` as it is and `.env.example` saved as `.env` (in Windows PowerShell,
   type `curl.exe` in place of `curl`):

   ```sh
   curl -fsSLO https://raw.githubusercontent.com/wandering-wampa/unison-reader/main/server/deploy/compose.yaml
   curl -fsSL https://raw.githubusercontent.com/wandering-wampa/unison-reader/main/server/deploy/.env.example -o .env
   ```

2. In the `.env`, set `UNISON_AUDIOBOOKS` and `UNISON_EBOOKS` to your two library folders. On Windows write the
   paths with forward slashes, such as `C:/Books/audiobooks`.

3. In that folder:

   ```sh
   docker compose up -d
   ```

   The first run pulls the image, about 340 MB for x86-64. The command is `docker compose` from the Compose
   plugin, not the older `docker-compose`. On Linux, `docker` may need `sudo` unless your user is in the
   `docker` group.

4. Open `http://<host>:8095` in a browser and do the first-run setup (next section).

Only those two lines of `.env` have to change. With the rest as `.env.example` has it, the server keeps its
data in `data/` beside `compose.yaml` (made on the first start) and the web client is at `http://<host>:8095`.
When either library folder is not set, Compose refuses to start and names the missing line. The file can hold a
secret (the webhook), so make it readable by you alone.

If port 8095 is already used on the host, set `UNISON_PORT` in the `.env` to a free one and use that in the
address. Other machines reach the server only if the host's firewall lets that port through. The first scan
goes on for a while after the setup page opens and the library fills in as it runs.

To update, pull the image again and run `docker compose up -d` again in that folder: the container is replaced
and the data folder is left as it is. To stay on one release, set `UNISON_IMAGE` in the `.env` to the image with that
release's tag, for example `ghcr.io/wandering-wampa/unison-reader:0.12.0`.

#### Building the image from source

To run your own changes, or on a platform the published image does not cover, clone the repository and build the
image from its top folder:

```sh
docker build -t unisonserver:local .
```

The build compiles the server, fetches the reader's pinned third-party source and the speech model (checking the
model against its SHA-256; the build stops if it does not match) and installs ffmpeg. It takes a few minutes the
first time and needs network access. The image is built for the platform you build on. Then take the `#` off the
`UNISON_IMAGE=unisonserver:local` line in the `.env` and run `docker compose up -d`.

### Deploying from Windows to a remote host

For development, `Deploy-Server.ps1` builds the distribution on a Windows machine and puts it on a Docker host
over SSH (the tools are listed in [BUILD.md](BUILD.md)). It builds the image on the host from
`server/deploy/Dockerfile`, which only adds a JRE and ffmpeg to the distribution it is given.

1. `.\Bootstrap.ps1`, once: fetches the pinned third-party sources and the speech model.
2. In `local.properties` at the top of the checkout (a file Git ignores), say where the two libraries are
   on the Docker host:

   ```properties
   unison.audiobooks=/path/on/the/host/audiobooks
   unison.ebooks=/path/on/the/host/calibre-library
   ```

3. `.\Deploy-Server.ps1 -Target <ssh host>`: builds the distribution (`gradlew :server:installDist`), copies
   it with the model and `server/deploy/` to `/opt/stacks/unisonserver` on the host, writes the stack's `.env`
   from step 2 (with `UNISON_IMAGE=unisonserver:local`), builds the image there, runs `docker compose up -d`
   and prints the server's health answer. The SSH user needs `sudo` and Docker.

The script writes the data folder as `/opt/stacks/unisonserver/data` and the host port as 8095. To put the stack
somewhere else, change that path in the script. Running it again updates the stack and leaves the data folder as it is.

### Settings

The stack's `.env`, which `compose.yaml` reads. `server/deploy/.env.example` has every line with a note.
`Deploy-Server.ps1` writes the file from `local.properties` on the development machine, as the middle
column says:

| `.env` | From `local.properties` | What it is |
|---|---|---|
| `UNISON_IMAGE` | (the script writes `unisonserver:local`) | The image to run. The published `ghcr.io/wandering-wampa/unison-reader:latest` when absent; a version tag such as `:0.12.0` to stay on one release; `unisonserver:local` for one you built yourself. |
| `UNISON_AUDIOBOOKS` | `unison.audiobooks` | The audiobook folder on the host, mounted read-only at `/audiobooks`. Needed. |
| `UNISON_EBOOKS` | `unison.ebooks` | The ebook folder on the host (a Calibre library or a folder of EPUBs), mounted read-only at `/ebooks`. Needed. |
| `UNISON_DATA` | (the script writes `/opt/stacks/unisonserver/data`) | Where the server keeps its own files on the host, mounted at `/data`. `./data`, a folder beside `compose.yaml`, when absent. To move it later, move the folder's contents too: an empty data folder is a new server. |
| `UNISON_PORT` | (the script writes `8095`) | The port on the host. `8095` when absent. |
| `TZ` | `unison.timezone` | An IANA zone such as `Europe/Berlin`; UTC when absent or empty. Only a fallback: the app and the web send each person's own zone for their statistics. |
| `UNISON_WANTED_WEBHOOK` | `unison.wantedWebhook` | A Discord channel webhook that new wanted-list requests are posted to. A secret. Off when absent or empty. |

`local.properties` also carries `unison.defaultServer`, which is for the app and not the server: the address
the app's sign-in form starts with in builds you make yourself. It is left out of a build made with
`-PpublicBuild`.

Read by the server itself, for a setup that is not this compose file. The first three share their names with
lines of `.env`, where they are folders on the host; those are not passed into the container, and inside it
the defaults below apply:

| Variable | Default | What it is |
|---|---|---|
| `UNISON_AUDIOBOOKS`, `UNISON_EBOOKS`, `UNISON_DATA` | `/audiobooks`, `/ebooks`, `/data` | The three folders as the server sees them |
| `UNISON_MODEL` | `/app/model.onnx` | The speech model |
| `PORT` | `8080` | The port it listens on |
| `UNISON_WORKERS` | a third of the cores, between 1 and 4 (the compose file says 4) | Books aligned at the same time |
| `UNISON_MAX_EBOOK_MB` | `400` | The largest EPUB it will parse |
| `UNISON_TOKEN` | made on the first start and kept in `data/token` | The install's first key, which is also the setup key (next section). Setting up the server ends it |

### The first sign-in

A new server has one account, `admin`, and that account has no password, so nobody can sign in yet. Open the
server's address in a browser. Until some account has a password, the web client shows a setup page in place
of the sign-in form.

The page says how many ebooks and audiobooks the first scan found. It also says when the ebook folder has no
`metadata.db`, which means the EPUBs in it are read directly, and when either
folder is empty, which usually means a wrong path in `.env`. On a large library the first scan takes a while
and the numbers grow as you watch. You do not have to wait for it, and a wrong folder can be fixed
afterwards: the libraries are read again every two minutes.

Then it asks for the password for `admin` twice, eight characters or more, and signs you in. The Admin page
adds the other people in the house, each with a role (admin or member) and a password they can change.

Setup works once. From the moment any account has a password the setup page is no longer shown,
`POST /api/setup` answers 409, and the sign-in form is the only way in. The one way to open it again is
the reset for a forgotten admin password, below.

#### Who may do the setup

A new server that can be reached from the internet could be claimed by whoever opens it first. So the server
looks at where a setup request comes from:

- From the machine itself or a private network address (10.x, 172.16.x to 172.31.x, 192.168.x, link-local,
  and the IPv6 loopback, link-local and unique local ranges), the password is all it asks for.
- From any other address the page has one more field, the setup key, and the request is refused without it.
  The setup key is the contents of the file `token` in the data folder (`cat data/token` beside
  `compose.yaml`), or the value of `UNISON_TOKEN` if you set that variable. Only someone who can read the
  server's own files has it.

Both simpler rules were worse. Asking everyone for the key would send every new owner back to a terminal,
when the usual first run is a browser on the same network as the server. Allowing private addresses only
would leave a server on a rented machine, or one reached over a VPN that hands out other addresses, with no
way to be set up at all.

Wrong keys are slowed the way wrong passwords are (five from one address, then a wait that doubles), and the
setup and every refusal are written to the audit log with the caller's address.

The server can only judge the address it is shown. Behind a reverse proxy it sees the proxy, and depends on
the proxy naming the caller in `X-Forwarded-For`, `X-Real-IP` or `Forwarded`. Caddy, Traefik and Nginx Proxy
Manager send one of these as they come; a hand-written nginx `proxy_pass` does not until you add
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`. A request counts as local only when the
connection and every address named in those headers are private, so a header made up by the caller cannot
turn an outside request into a local one.

Two arrangements hide the caller altogether: a proxy that sends none of those headers, and Docker Desktop or
rootless Docker and Podman, which can hand every connection to the container from a private address of
their own. The server then cannot tell inside from outside. On those, give `admin` its password before you
forward a port or point a proxy at the server.

Until the setup is done the same key also signs in to the API as `admin`, and it is listed among `admin`'s
devices as `server`. Completing the setup ends it: the server signs that device out in the same step and deletes
`data/token`, and a restart does not make a new one. From then on only passwords and the device tokens they
produce get in. A server that was set up before this behaviour keeps its key until its `server` device is signed
out on the Account page.

#### A forgotten admin password

This is in versions after 0.12.0; the published image 0.12.0 does not have it. If nobody can sign in as an admin
any more, make an empty file named `reset-admin` in the data folder and restart the container:

```sh
touch data/reset-admin
docker compose restart
```

On Windows PowerShell the first line is `New-Item data\reset-admin`. On Linux it may need `sudo` when the data
folder belongs to root. At start the server deletes the file, removes the password of the built-in `admin`
account and signs that account out on every device. Everything else stays: the other accounts, and the admin's
own shelf, places, bookmarks and highlights.

The web client then shows the setup page again, asking for a new password for `admin`. Because other people
may already be using the server, it asks for the setup key from every address, the server's own network
included. The key is in `data/token`, which the server writes again at that start, or the value of
`UNISON_TOKEN` if you set that variable. Choosing the password ends the key and deletes that file, as after the
first setup. People with other accounts stay signed in, and the page has a "Sign in with another account" link
for them.

The reset is written to the audit log. The file is acted on once: to reset again, make it again, and make it
again if the server restarts before the new password is chosen. Only someone who can write to the data folder
can do this, and that person could already replace the database. If another admin can still sign in, they can
instead give `admin` a new password on the Admin page.

### Reaching it from outside the house

Put a reverse proxy that terminates HTTPS in front of the published port (Nginx Proxy Manager, Caddy and
Traefik all do) and give the server a hostname. That address is what goes into the app's sign-in screen and
the browser. Audio is streamed with HTTP range requests, which a proxy passes as it stands. The server takes
the caller's address from `X-Forwarded-For` only when the request reaches it from a private address, so the
sign-in throttle counts the real caller behind a proxy on the same network. What else guards the sign-in is
under "Accounts" below.

### Backups

The server writes a consistent copy of its database to `data/backups/catalog-<date>.db` at every start and
each night at 02:30, and keeps seven. The Admin page shows the newest and writes one on request. Those copies
sit on the same disk as the database, so include the data folder in whatever backs the host up. The two
libraries hold nothing of the server's and need no extra care on its account.

To restore: stop the stack, replace `data/catalog.db` with a backup file (remove `catalog.db-wal` and
`catalog.db-shm` if they are there), start it again. Accounts, shelves, places, marks and
statistics are only in the database; nothing of them is in the libraries.

## Why a server

- **Sync once, fast.** A phone aligns at about 23 times real time. A server with a few cores aligns several
  chapters at once, and does it one time for every device.
- **Small devices.** The phone keeps only the books it is given: the ebook, its alignment, and either
  streamed audio or a downloaded copy. The speech model and ONNX Runtime stay out of the app.
- **One library.** If the books already live in a Calibre library and an audiobook folder (behind
  Calibre-Web and Audiobookshelf, say), the server reads the same folders and changes nothing in them.
- **Progress everywhere.** The place is kept on the server, so any device resumes at the sentence.

## Modules

```
core/     Kotlin JVM library: Book/Passage/Track/Chapter/Anchor, EPUB parsing (jsoup),
          text normalisation, SpeechModel/Emitter/BookText/UnitAligner (CTC → anchors → Viterbi).
          No Android. ONNX Runtime and org.json are compileOnly here; each consumer supplies them.
app/      The Android app. Depends on core; keeps MediaCodec decoding, SQLite, UI, and the
          on-device sync as the offline fallback. Gains a server mode.
server/   Ktor service. Depends on core; adds onnxruntime (JVM), ffmpeg (in the image) for decoding,
          SQLite via JDBC for its catalog, Calibre metadata.db reading, the scanner and the API.
```

The moved code keeps the `dev.homescreenreader` package for now so the app needs no import
changes; a package rename is a later cleanup. The Android package id stays `dev.homescreenreader`
forever, so installed libraries carry over.

## Server design

**Scanner.** Walks the audiobook root (`Author/Title/`, tracks ordered numerically, durations and
chapters from ffprobe) and the ebooks: a Calibre library through `metadata.db` (books, authors, formats, covers), or, when the folder has no `metadata.db`, every EPUB under it through its own OPF metadata and cover. The two are never mixed. In the second case an EPUB is opened again only when its size or modification time changes, and covers are written to the data folder.
Pairs an audiobook with an ebook through `BookIdentity`: conservative title aliases, full authors,
and known series/volume metadata. Persistence keys and work IDs are unchanged by these comparison
rules. Each title becomes a *work* with zero or one ebook and zero or one audiobook; the
shelves the app shows (Ready, Getting ready, Missing a piece) fall out of that.

**Metadata.** Each work carries subtitle, description, published year, publisher, language, rating, genres, any number of series with a sequence, authors and narrators. Sources are ranked: Calibre's `metadata.db`, or the EPUB's own metadata when there is no `metadata.db` (3), Audiobookshelf's `metadata.json` beside the audio (2), the audio files' tags (1), the folder name (0); a field set by a stronger source is never overwritten by a weaker one, and a weaker source only fills blanks. Nothing is written back to either library.

**Catalog.** SQLite at `/data/catalog.db`: works, files, pairing overrides, sync state per unit
(the same unit model as the app: one unit per audiobook chapter or track), anchors, device
positions. Parsed ebook passages are cached as JSON per work so the app and the aligner see the
same sentence ids.

**Sync.** The ported engine. A worker pool aligns units in parallel (one ONNX session per worker,
`threads = cores / workers`), decoding each unit's audio span with `ffmpeg -ss -t -f f32le -ac 1
-ar 16000`. A paired work syncs automatically when first seen and whenever either file changes;
progress is visible through the API. Alignment VERSION is shared with the app through `core`. When every
unit is done but fewer than a fifth of the book's sentences were found in the audio (the audiobook is another
book or edition, or the book is not in English), the status is `Failed: only N% of the text was found in the
audio` and the work stays on the Getting ready shelf; a server that already held such a work as Synced
changes it the next time it starts. Sync again (`POST /api/works/{id}/sync?restart=true`) tries it over.

**Accounts.** `users` (name, PBKDF2-SHA256 password hash with a per-user salt, role admin or member),
`tokens` (one per signed-in device, revocable) and `shelf` (user, work, state reading/queued/finished,
added, finished_at) live beside the catalog. The install's original token belongs to the built-in
`admin` until the setup ends it; positions recorded
before accounts are the admin's. `POST /api/login` with name, password and a device name returns a
device token; everything else under `/api` needs a token. Admins create and remove members and set
passwords; each person's shelf, finished list and positions are their own.

**Sign-in is hardened** for a server that can be reached from the internet: only the SHA-256 of each device token is
stored, so the database alone signs nobody in; a token idle for thirty days (browser) or a year (app)
expires; tokens are never read from the query string, and the web client gets an `HttpOnly`,
`SameSite=Strict` cookie at sign-in that the server accepts only for covers, audio, the ebook and the two
page-hide beacons (position, sessions), the places a browser cannot send a header; sign-in is throttled per
address and per name (five misses, then a lockout doubling from 30 s to 15 min, answered with 429 and
`Retry-After`), an unknown name costs the same time as a wrong password, and the proxy's
`X-Forwarded-For` is trusted only when the request comes from a private address; passwords are eight
characters or more; every response carries a content security policy (scripts from the server only,
styles and fonts from the server and Google Fonts, frames and media from the server and blob URLs), no
framing, no sniffing, no referrer; the public health answer no longer lists what is syncing; the install's
original token is seeded only on a fresh install, ends when the first-run setup is done and can be revoked like any device before that; the first-run setup
takes a password alone only from a private address and needs the setup key from anywhere else ("The first
sign-in" above); and sign-ins, the setup,
failures, lockouts, expiries, device sign-outs and account changes go to an `audit` table that admins see
on the web client's Account page (`GET /api/users/audit`). Not yet: a second factor (TOTP or passkeys).

**API** (JSON, bearer token):

| Route | Purpose |
|---|---|
| `GET /` (and any non-API path) | the web client, a static single-page app packaged in the server jar (`web/`), with foliate-js at `/foliate/` |
| `GET /api/health` | version, library counts, sync queue |
| `GET /api/works?q=…&shelf=…` | the library with pairing and sync state (the shelves); `q` searches title, subtitle, authors, narrators and series |
| `GET /api/series`, `/api/series/{name}` | every series (size, synced count, total audio, authors, a cover) and a series' works in sequence order |
| `GET /api/authors`, `/api/authors/{name}` | authors with counts, and an author's works grouped by series then standalone |
| `GET /api/narrators`, `/api/narrators/{name}` | the same for narrators |
| `GET /api/works/{id}` | one work: metadata, tracks, chapters, sync summary |
| `GET /api/works/{id}/ebook` | the EPUB |
| `GET /api/works/{id}/passages` | parsed sentences (the app's passage list) |
| `GET /api/works/{id}/alignment` | anchors (passage → start/end ms) |
| `GET /api/works/{id}/audio/{n}` | track `n`, with HTTP ranges for streaming and seeking |
| `GET/PUT /api/works/{id}/position` | reading passage and audio position, kept per user and device; GET answers the newest from any device with `by` (that device's name); PUT with `?base=` (the server time of the place the client last had) answers 409 with the newer place when another device moved the book after it |
| `GET /api/works/{id}/speed`, `PUT /api/works/{id}/speed` (speed) | my listening speed for the book, 0.5 to 3; GET gives 1 with `updated` 0 when never set; also in the work detail as `speed` |
| `POST /api/login` | name + password + device → token and user; `web: true` also sets the browser cookie; throttled |
| `GET /api/setup`, `POST /api/setup` (password, key, device, web) | the first run, no token needed. GET answers `needed` (true while no account has a password), `key` (true when this caller must also send the setup key), `reset` (true after the admin password was reset, until a new one is chosen; `needed` is then true too), `minPassword`, and `library`: what the scan has found so far (`ebooks`, `audiobooks`, `paired`, `scanned` once a pass has ended, `calibre` when the ebook folder has a `metadata.db`, `ebookFolderEmpty`, `audiobookFolderEmpty`). POST gives the built-in `admin` its password and answers as a sign-in does (token and user, and the cookie with `web: true`). 403 without the right key from an address that is not private, 400 for a password under eight characters, 429 after five wrong keys from one address, and 409 once any account has a password, after which GET answers only `needed: false` |
| `POST /api/link/start` (device), `POST /api/link/claim` (secret) | Wear OS sign-in, the watch's side, no token needed. `start` answers a six-digit `code` to show, a `secret` to ask with, when the code `expires` and how often to ask (`poll`, seconds); 429 when five codes already wait from that address, or a hundred in all. `claim` answers `{status: "waiting"}` until a signed-in person approves the code, then once `{status: "approved", token, user}`; 410 when the code has ended, been used, or never was |
| `GET /api/me/link/{code}`, `POST /api/me/link/{code}` | Wear OS sign-in, the signed-in side: which device shows this code (`device`, `address`, `created`), and yes, sign it in as me. 404 for a code nobody is waiting behind; five wrong codes in a row and the person waits, as with wrong passwords (429) |
| `POST /api/me/cookie` | sets the browser cookie for a token a browser already holds |
| `GET /api/users/audit` | admin: recent sign-ins, failures, lockouts and account changes |
| `GET /api/me`, `DELETE /api/me/tokens/{prefix}`, `POST /api/me/password` | who I am and my devices; sign a device out; change my password (the body carries `current` and `password`; a wrong `current` is 403, a missing one 400, repeated wrong tries 429) |
| `GET /api/me/shelf`, `PUT /api/me/shelf/{work}` (state, finishedAt), `DELETE /api/me/shelf/{work}` | my shelf: reading, queued or finished with its date |
| `GET /api/me/series` | each series I have finished or started a book in: total, finished, for the book after the last finished one in series order: `current` if it is started, otherwise `next` (nothing advances past an unfinished book; neither once the last book is reached) and when I last finished one, newest first |
| `GET /api/me/collections`, `POST /api/me/collections` (name), `PUT /api/me/collections/{id}` (name), `DELETE /api/me/collections/{id}` | my lists: Want to read first (`kind` "want", always there, cannot be renamed or removed), then the collections I made (`kind` "list") by name, each with its works' ids, newest added first. Names are one to sixty characters and unique per person whatever the case |
| `PUT/DELETE /api/me/collections/{id}/works/{work}` | put a work in a list or take it out; `{id}` is a collection's number or `want`. Putting a work on the shelf (`PUT /api/me/shelf/{work}`, any state) takes it off Want to read |
| `GET /api/lookup?q=<words>` | looking a book up before asking for it (any signed-in person): what Audible and Open Library say about the words typed (title, authors, narrators, series, year, publisher, genres, summary, a cover address), and for each answer the library's own work when it has the book (`work`, `hasEbook`, `hasAudio`) and whether it is already on the wanted list (`wanted`); `errors` names a catalogue that did not answer. Only the words typed leave the house |
| `GET /api/lookup/image?url=<cover address>` | a cover the lookup offered, fetched by the server (the two catalogues' image hosts only) |
| `GET /api/requests`, `POST /api/requests` (title, author, note, kind ebook/audiobook/both) | the household's wanted list: every request with its status (wanted, arrived, declined), who asked, where (web or app) and the work once it arrived. Asking for a book already wanted answers with that request; asking for one the library already has answers `arrived` at once |
| `PUT /api/requests/{id}` (status, work), `DELETE /api/requests/{id}` | admin: settle a request by hand; the person who asked or an admin: remove it |
| `GET /api/works/{id}/meta`, `PUT /api/works/{id}/meta` | admin: the metadata overlay. GET gives the work as shown (`current`, with `edited` naming the corrected fields) and as the libraries describe it (`source`). PUT takes any of title, subtitle, authors, narrators, series (name, sequence), description, publishedYear, publisher, language, genres, cover (an address from a lookup), and `reset` (fields to put back); a value equal to the source removes the correction |
| `GET /api/works/{id}/meta/lookup?title=…&author=…`, `GET /api/works/{id}/meta/image?url=…` | admin: what Audible's catalogue and Open Library say about the book (candidates to take into the overlay), and a candidate's cover fetched through the server (the page loads images from the server only) |
| `GET/POST /api/users`, `DELETE /api/users/{id}`, `POST /api/users/{id}/password` | admin: the household's accounts |
| `GET /api/users/overview`, `PUT /api/users/{id}/role` (admin/member), `DELETE /api/users/{id}/tokens`, `DELETE /api/users/{id}/data` (shelf, marks, positions, sessions), `GET /api/users/audit` | admin: everyone with devices, last activity, shelf counts and time with books; change a role (never your own, never the built-in admin's, never the last admin's); sign a person out of every device; the activity log |
| `GET /api/me/marks/{work}`, `PUT /api/me/marks/{work}` (passage, kind bookmark/highlight, note, text, created), `DELETE /api/me/marks/{work}/{kind}/{passage}` | my bookmarks and highlights on a work, one per sentence and kind |
| `GET /api/me/stats`, `GET /api/me/year`, `GET /api/me/year/{yyyy}` with `X-Timezone: <IANA zone>` | days and hours are counted in the asker's time zone (the server's own when the header is absent or unknown) |
| `GET /api/me/year`, `GET /api/me/year/{yyyy}` | the year in review (this year, or the one asked for): totals, days active, longest streak, biggest day, favourite hour, most-read author, months, every day of the year, top books, books finished that year, the years with anything, and the active days of every year for the heat-maps |
| `GET /api/me/marks` | everything I marked, by work (id, title, author, sentence count, marks in reading order), the most recently marked book first; the web's Highlights page |
| `POST /api/me/sessions`, `GET /api/me/stats` | listening and reading sessions from the app (batches; under three seconds dropped; a resend is stored once), and the numbers made from them: today/week/month/year/all totals, streak and longest streak, days with books, finished count, 365 days, hours of day, top books |
| `POST /api/scan` | admin: read the libraries again (audited) |
| `GET /api/pairing` | admin: audiobooks waiting for an ebook (with same-author ebook suggestions), ebooks without audio, audio-only marks |
| `PUT /api/works/{id}/pair` (ebookWork), `DELETE /api/works/{id}/pair` | admin: pair an audiobook with an ebook by title and author keys (kept across rescans), or remove the manual pairing |
| `PUT/DELETE /api/works/{id}/audio-only` | admin: mark an audiobook as having no ebook to wait for (ready as it is), or clear the mark |
| `GET /api/users/backups`, `POST /api/users/backup` | admin: the database backups in `data/backups` (nightly at 02:30 and at start, seven kept), or write one now |
| `POST /api/works/{id}/sync` | start or resume syncing; `DELETE` cancels |
| `PUT /api/works/{id}/pair` | manual pairing override |

Files are served with ETags so the app can keep a downloaded copy and know when it is stale.

**Deployment.** The image holds a JRE, ffmpeg, the built distribution and the speech model; nothing is
compiled on the host. The steps, the settings and the first sign-in are under "Running it" above.

**The libraries are never written to.** Both roots are mounted `:ro`, and Calibre's `metadata.db`
is copied into the data folder before SQLite opens it, so no journal or lock files ever appear in
the Calibre library. Calibre, Audiobookshelf and anything else that uses the same folders are unaffected. Memory: the model is
21 MB; each worker holds one unit of audio (a chapter at 16 kHz float is under 200 MB an hour).

**Wear OS sign-in.** A watch is a poor place to type a password, so it signs in by a code (`DeviceLinks.kt`). The watch asks for a code and shows it; a person already signed in (the phone app's Settings, or the web's Account page) types the code, is shown which device asked and from which address, and approves; the watch, which has been asking every two seconds with a secret only it holds, is then given a device token of its own for that person, exactly as a sign-in by password would give it (a year's idle life, revocable from the device list). A code is six digits, lasts ten minutes (two more once approved, so the watch can collect) and works once. Codes wait in memory only: a restart drops them and the watch asks again. The secret is kept as its SHA-256, and the token is made at the moment the watch collects it, so no token waits on the server. Typing a code needs a sign-in sent as a bearer token (the browser's cookie does not count), approvals and the sign-in that follows go to the audit log (`sign-in code approved`, `signed in` with the detail `by code`), and `python server\local\check_link.py` exercises all of it against the local server.

**Lists.** `collections` (id, user, name, kind, created, updated) and `collection_works` (collection, work, added) hold each person's Want to read and hand-made collections; a list is its owner's alone. Want to read is for a book not picked up yet: shelving a work removes it there. Reset progress and removing a person clear their lists.

**Pairing and identity.** The scanner plans pairs before updating works. Existing pairs and explicit
admin overrides remain authoritative; audio-only choices remain respected. New matches use folder
and Audiobookshelf `metadata.json` titles/authors, and must be unique in both directions. Conservative
aliases cover ampersands, publisher descriptors, anniversary labels, collection counts and exact
known series/volume wrappers. Different authors, contradictory collection counts/volumes, and
different performances or meaningful editions stay separate. A second untracked audio copy cannot
steal an existing narration or create a false missing-ebook entry; already catalogued copies retain
their IDs. Source library files are never renamed or edited.

Lookup and wanted-list settlement share these comparison rules. Settlement uses the persisted
catalog title key, so a display title such as `Book 1 - Leviathan Wakes` or an admin's title overlay
cannot hide the available book. It requires the requested medium and an unambiguous work; surnames
alone are insufficient. The existing scan on startup and every two minutes performs settlement;
there is no separate repair timer.

**Posting the wanted list to Discord.** With `UNISON_WANTED_WEBHOOK` set (a Discord channel webhook's address; see "Settings" above), `WantedPost` says each new request in that channel, about two seconds after it is made, as plain message text from the webhook under the name `Unison`:

`Request · type: <ebook|audiobook> · title: <title>[ by <author>][ · from: <who asked>]`

The separator is a space, a middle dot (U+00B7) and a space. A request for both forms is two messages, `type: ebook` then `type: audiobook`. Runs of white space in a title, author or name become one space and a middle dot becomes a hyphen, so a message is always one line with these fields in this order; the title part is cut at 300 characters. `allowed_mentions` is empty: nothing in a title pings anyone. A request for a book the library already has (it arrives at once) is never posted, nor is one taken back within those two seconds, nor anything that was on the list before the webhook was first set. A post the webhook refuses stops the pass and is tried again, in order, after the next change to the list and with every scan (two minutes); a 429 waits as long as Discord says, once. Nothing is read from Discord, and the server does not know or care what reads the channel. Without the variable the feature is off (`Wanted list posting: off` in the log).

**The wanted file.** Whatever is still wanted is also written to `wanted.json` in the data folder, whole and moved into place, at start and after every change to the list: `{"version": 1, "written": <ms>, "requests": [{"id", "title", "author", "kind": ebook|audiobook|both, "note", "requester", "created": <ms>}]}`, oldest first. It is there for another program on the same host to read without a key to this server; a request leaves the file when it arrives (the scan finds the book), is declined or is removed. The server reads nothing back from it.

**The wanted list.** `requests` holds what the household asked for (title, author, note, kind, who, source, status, the work once arrived). After every scan `Lists.settle` marks a wanted request arrived when a work with the scanner's same title key (and the author's key or last name, when the request names one) has the form asked for: the ebook, the audiobook, or both. An admin can also settle or decline by hand.

**The metadata overlay.** `overrides` (work, field, value, updated) holds an admin's corrections, one row per field, laid over what Calibre and the audiobook folders say whenever works are read (`Catalog.decorate`), so the library, series, authors, narrators and search all show the corrected book. Nothing is written to the libraries; the work's id and the scanner's pairing keys do not change. A chosen cover is fetched into `data/works/<id>/cover-override.*` from Audible's or Open Library's image hosts only, and `coverVersion` on the work lets the app fetch it again. Lookups send only the title and author the admin typed.

## The app's server mode

- **Sign in**: a sign-in screen on first launch (and after Sign out or an expired token) with the
  server filled in (a build with no default server shows the address field open instead), a Change
  button for another address, name and password; the device
  token it receives is kept. Settings keeps Sign in as someone else, Sign out, devices and password.
  The link `unisonreader://connect?url=…&token=…` opened from a browser or
  `adb shell am start -a android.intent.action.VIEW -d "<link>"`. The connection and a generated
  device name live in the reading preferences.
- **On your server**, a section under the local shelves: synced works not yet on the device, each
  with **Stream** and **Download**, and a line for what the server is still syncing.
- **Adding a work** fetches the EPUB and parses it on the device with the same parser the server
  used (so sentence ids agree), takes the server's alignment as the book's sync (one unit, "Synced"),
  the cover, and either server URLs for the tracks (streamed through the player with the token on
  each request) or downloaded copies. The book then lives on the Ready shelf like any other, with its
  server work remembered.
- **Position**: pulled when the book's details open and before the Shelf's Continue opens a book (up to
  1.5 s). A newer place from another device is taken when nothing moved here since the last exchange;
  when both moved, close together this device's is kept, far apart (two minutes of audio or thirty
  sentences) the person is asked: Go there or Stay here. Pushed every twenty seconds while the book is
  open and when the app goes to the background, with the place it was based on: a save refused because
  another device moved is still sent when close or playing here, otherwise held for the question. A
  place with audio but no sentence takes the sentence narrated there from the alignment. The web saves
  only places it moved to (an idle tab never re-sends), with the narrated sentence while listening; a
  player tab that already had the book loaded checks before resuming (taken quietly when it had not
  moved, asked when both moved apart), and a reader tab checks when it comes back into view (asked,
  never moved by itself). The app's playback service saves every twenty seconds while playing and on
  pause, so listening from the player sheet or the lock screen reaches the other devices.
- Importing files and syncing on the device itself are in the standalone build only (`-Pstandalone`, see
  [BUILD.md](BUILD.md)).

## The watch app

Unison for Wear OS (`wear/`, its own app id `dev.homescreenreader.wear`, Wear OS 6 and later) listens to the server's audiobooks from the watch alone, over Wi-Fi or LTE. It shares `ServerClient` with the phone app (the class lives in `core`).

- **Sign in** by a code (above). The server's address comes from `unison.defaultServer` at build time and can be typed on the watch.
- **The shelf**: the books the person is reading or has queued that have audio (`GET /api/me/shelf`, then each work's detail, kept on the watch and asked for again after twelve hours). A book that leaves the shelf leaves the watch unless its audio was downloaded.
- **Download** puts a book's tracks on the watch through a foreground service, one book at a time; each track is fetched with `Range` so a dropped connection costs only what had not arrived (`ServerClient.resume`; the server's ETag says whether the file is still the same one). **Listen** without a download streams, with the watch's token on each request.
- **The network**: beside its phone a watch talks through the phone's Bluetooth, which is too slow for audio, so while a book downloads or streams the app asks for Wi-Fi or LTE and binds itself to it. Away from the phone it is on one of those already.
- **The place** follows the rule the phone and the web follow: a newer place from another device is taken when nothing moved on the watch since the last exchange; both moved and close (two minutes of audio), the watch's is kept and sent; far apart, the watch asks (Go there or Stay here; leaving the question changes nothing and it asks again). Saved every second on the watch, sent every twenty seconds while playing and on pause, with `?base=`; what could not be sent goes when the shelf is next refreshed. The watch starts at the place's audio time, as the phone's and the web's players do; the text's place never decides it (someone can be reading in one part and listening in another), except for a book that was read and never listened to, which starts where the reading is. A place travels whole, text and audio, so what the watch sends as the sentence matters to the other devices: when text and audio were together as the watch took the place (within thirty sentences, by the alignment fetched with the book), it sends the sentence being narrated and they stay together; when they were apart, it sends the text's place back exactly as it was.
- **Speed** is the book's own (`/speed`), **listening time** is sent as sessions for the statistics, and signing out on the watch sends what it still owes, removes its token from the server and deletes its books.
