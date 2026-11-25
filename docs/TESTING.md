# Testing

Server identity regressions run without an Android SDK:

```sh
./gradlew --configure-on-demand :server:test :server:installDist
```

`BookIdentityTest` exercises scanner pairing plans, sidecars, conflicting copies/editions, manual
choices, lookup identity and SQLite-backed request settlement. `CatalogReplayKt` in the test sources
can additionally scan a **disposable SQLite backup** using the distribution's runtime classpath plus
the compiled test classes. Arguments are the disposable data directory, ebook root and audiobook
root. It requires a `.disposable-replay` marker, starts no HTTP server/workers/notifications, and
asserts that existing work IDs and pairings survive. Run with networking disabled and both source
libraries mounted read-only; compare personal-state tables with the untouched backup afterwards.

Use the `HomescreenReader_API36` emulator (`Start-Emulator.ps1` if it is not running). The tests drive the app through Android instrumentation and adb only; nothing touches the desktop.

Every change is also exercised by hand before it is reported: the app's real controls on the emulator (`.\Tap-Emulator.ps1 -List`, `-Text`, `-Desc`, `-Match`), the web in a browser, and the stored state on both: offline and in the background, on both devices, both answers to every question the app asks, and the open tab reloaded after a deploy.

```powershell
.\Test-Emulator.ps1 -Modes curl,bookish,reader
.\Test-Emulator.ps1 -Modes server -ServerUrl https://unison.example.org -ServerToken <token>
python Verify-Apk.py
```

| Mode | What it checks |
|---|---|
| `reader` | Original EPUB rendering, live sentence highlights, shared state; then play, pause, resume and page turns driven from the reader's own controls while the screen is sampled every 50 ms. Fails on any movement that is not a logged follow scroll or a turn the test made. Also asserts the Listen here rule and that the selection menu goes when narration starts. |
| `server` | Adds the smallest synced work from the server, streams it, round-trips a position, creates and removes a member account, posts sessions and reads statistics back. Restores the fixture as the active book. |
| `curl` | First-drag latency and cached-page behaviour of the curl turn, commit and cancel, cache invalidation, compact control labels. |
| `bookish` | The eight turn styles, gestures, appearance, activity recreation. |
| `benchmark` | Syncs one unit (or the whole book with `-FullSync`), checks ordering and cancellation safety, writes `alignment-evidence.txt`. |
| `playback` | Foreground, background and screen-off audio with media-session commands. |
| `sleep` | The sleep timer on the real service: end of chapter (stop on the next chapter's first word, fade, volume restored), a shake after the stop plays on, a shake extends a length timer. Needs the fixture active (it has chapters). |
| `sleepshake` | Leaves a 30 s timer running and waits 25 s for a real accelerometer shake; shake the emulator from the host meanwhile: `adb -e emu sensor set acceleration 28:4:9.8`, then `-27:-3:9.8`, a few times, then `0:9.81:0`. |
| `auto` | What Android Auto does, with the platform's media browser: connects to the playback service's library, reads the tabs (My shelf, Recent, Series) and their lists with covers, plays the active book by its id from the saved place, pauses, and plays it again from a spoken search. The active book must have audio. `-e browse only` reads the lists without playing, for a run in airplane mode (Series then comes from what the phone kept). |
| `core`, `samples`, `formats`, `failure`, `saf`, `service`, `resume`, `cleanup` | Import and format handling, synthetic audio, failure paths, the document picker, the sync service, interrupted syncs, removal of synthetic test books. These and `benchmark` exercise importing and on-device sync: they need the standalone build (`-Pstandalone`, see BUILD.md), not the default one. |

Server and web changes are tried first on a local server: `.\Start-LocalServer.ps1`, then `python server\local\check_phase9.py --online`, and the web at `http://localhost:8099` in a browser with the test accounts from `build\local-server\credentials.txt`.

The reader tests run against one ebook and its audiobook, which are not in the repository (they are a commercial book): supply your own copies as `test-fixtures/books/11_22_63 - Stephen King.epub` and `test-fixtures/audiobooks/11-22-63.zip`. The script copies them to the emulator and never removes them. `-Modes shelves` stages sample books for every shelf state; `-Modes cleanup` removes them. The `Capture-*.ps1` scripts take adb screenshots of the library, sync and reading states.

Output goes to the ignored `test-results/` folder. Emulator numbers are not phone numbers. If a UI walkthrough is needed, the tap zones matter: with the reader controls hidden, a tap in the right quarter of the page turns it.

The watch app is driven the same way on the Wear OS emulator (`Start-Emulator.ps1 -Watch`), against the local server and its two test audiobooks. `server\local\check_link.py` and `check_resume.jsh` check the server's side of Wear OS sign-in and the watch's resumable download without a device.
