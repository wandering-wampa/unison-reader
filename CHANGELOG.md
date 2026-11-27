# Changelog

Versions before 0.11.0 were not published: the first release in this repository is 0.11.0. They are listed for the record.

## 0.14.0 (2026-11-27)

- The web client and the phone app have four sections: My Shelf (Home on the phone), eBooks, Audiobooks and Journal. Genres can be browsed for the first time, in eBooks and in Audiobooks, and a genre page on the phone offers the same genre in the other section. A series page counts finished books within its own section. On the web the header stays on one row down to 780 px.
- The server answers `/api/genres` and accepts `?format=` on the book list routes. A path under `/api` that does not exist now answers 404 in JSON, and the web page no longer answers every other unknown path.
- Play no longer overwrites a newer place from another device: a start that has not played on this device in the last two minutes asks the server first.
- Sync again during a running sync waits for the workers, then starts over, and a later Pause wins. Pausing a sync, Sync again and a restart need an admin, and the web shows those buttons to admins only.
- Server: the health route and the list of marks use one query each instead of one per shelf or book. The phone decodes covers at the size shown.
- The Wear OS app was removed, and with it signing in by a code shown on another device: the server's routes for it, the phone's Settings row and the web client's Account panel.
- A scan that would hide at least five books and more than a quarter of the books at once is held back, so a library read only in part does not hide the rest. The admin page says so and lets an admin accept it. A Calibre database that cannot be copied counts as an unreadable ebook folder for that scan.
- Asking again for a book that is already wanted widens what was asked for (ebook and audiobook make both) and keeps the new note.
- Statistics ignore a listening session longer than 24 hours or one that ends after the server's clock.
- The web client tries a failed position save again, and "Go there" moves the listening place with the text. The password-changed message says the other devices were signed out, and so does the admin's Set password dialog in the app.
- In the app, adding a book from the server no longer reads the EPUB on the phone, so a large book can be added. Highlighting several sentences sends one upload, and the reader's top and bottom fades no longer show half a line under the bar or above the dock.
- A library that cannot be read, or that lists nothing while the server knows books from it, changes nothing in a scan. A book whose files vanish from a readable folder is hidden and kept for 30 days before it is deleted, and returns as it was if the files come back. An admin can accept an empty folder as meant.
- A password change signs the person out of their other devices, and the cover fetch checks every redirect it follows.
- Web: the Pause button is readable in the dark theme, the header's search box keeps its width, an ordinary position save asks before it overwrites a newer place, the admin's password dialog is fixed, and short chapter lists no longer show the word "null".
- Phone: Home and the Library's list rows show a short status line (a failed sync's message may take two lines), My shelf shows percentages for books shelved elsewhere, a series page is one list, search waits for a pause in typing, and the reader's lines fade at the bars and the listening dock instead of being cut.
- Phone: the mini player on Home stays hidden while it would repeat Continue your story, Now listening's seek bar spans the chapter being played, a long-press on a sentence offers Highlight, and a book with audio keeps room for the listening dock so starting or stopping narration moves nothing.
- Phone: a square audiobook cover shows as a square everywhere in the app, not inside a tall frame. A long-press on a sentence no longer shows a bar at the bottom; the selection menu's first row is Copy, Highlight, Note and Listen, with Share, Select all, Define and Translate behind the arrow, and Note adds a note to the selected sentences. The Settings icon is a gear.
- Server: an audiobook with no cover of its own now gets one at the next scan, from a cover or folder picture in any letter case, any other picture in its folder, or the art embedded in its first audio file (extracted with ffmpeg into the data folder). Before, only `cover.jpg`, `cover.png`, `folder.jpg` or `folder.png` were looked for, and only when the audio changed.

## 0.13.0 (2026-11-25)

- A sync that finds under a fifth of the book's sentences in the audio (another book or edition, or a book that is not in English) now ends as `Failed: only N% of the text was found in the audio` instead of Synced, and the book page explains it and offers Sync again. The app says so too, for a book in the catalogue and for one already added. Works already stored as Synced are corrected when the server starts.
- A forgotten admin password can be reset: put an empty file named `reset-admin` in the data folder. The server reads it once at start, and the setup page then asks for a new password and the setup key.
- The apps can be built on Linux with `bootstrap.sh`, and the workflow builds both on every push.
- The README and the server guide have clearer install steps.
- A workflow runs the server's tests on every pull request. This changelog and the security and contributing notes are new.
- The documents now say that narration follow is for English books.

## 0.12.0 (2026-11-25)

- The server comes as a published Docker image, `ghcr.io/wandering-wampa/unison-reader`, for x86-64 and ARM64. The compose file in `server/deploy/` pulls it, and `.env.example` lists every setting. Building the image from a clone still works with one `docker build`.
- A new server starts with a setup page. It shows what it found in the two library folders and asks for the admin password. From outside the local network it also asks for the key in `data/token`; the key stops working once setup is done.
- Ebooks no longer need Calibre. With no `metadata.db` in the ebook folder, the server reads every EPUB under it and takes the title, authors, series and cover from the file. A Calibre library is read as before.
- In an app built without a default server, the sign-in screen opens with the address field ready and says when the address is empty or malformed. The watch app offers typing the address on its first screen.

## 0.11.0 (2026-11-25)

- A Wear OS app plays the audiobooks on your shelf from the watch alone, streamed or downloaded. It keeps the same place as the phone and the browser.
- The watch signs in with a six-digit code that you approve in the phone app or on the web client's Account page. No password is typed on the watch. This needs a server of this version or later.
- Play buttons change to pause at once, and Listen here, Continue and the headphones on Home say that they are opening.
- The server pairs an audiobook with its ebook by title, full author and series volume together, so different editions, different narrations and a second copy of the same audiobook are no longer mixed up.
- A demo library of public-domain books, for trying narration follow without a library of your own.

## 0.10.0 (2026-11-19)

- A bar along the bottom of the app: Home, Library (Books, Series, Authors and Narrators) and Lists. A mini player sits above it while a book is loaded.
- Now listening is a screen of its own, with the chapter, time left at your speed, chapter buttons, speed, sleep timer and Read from here.
- A book with only one half can be added, and a book the server is still syncing can be read and listened to.
- Asking for a book starts with a lookup that shows what the server already has. The app tells you when a requested book arrives.
- An admin can set someone's password. Passwords need eight characters or more.
- Fixes: Back returns to the previous screen with its scroll and search, changes made offline are sent later, and a failed stream says so.

## 0.9.0 (2026-11-10)

- Lists: Want to read and your own collections.
- The wanted list: ask for a book the library does not have.
- Android Auto shows My shelf, Recent and Series, and answers "play ... on Unison".
- A year in review that can be shared as a picture.
- Search inside a book, define and translate on a long press, time left in the chapter and the book, and highlights exported as Markdown.
- The app is much smaller, about 25 MB instead of 121 MB, because it reads and listens from the server. Importing files and syncing on the phone are gone; books already on the phone stay readable.
- Fixes: a page turn landing in the next chapter, and statistics counted in your own time zone.
