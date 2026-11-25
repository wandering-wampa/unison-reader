# Changelog

Versions before 0.11.0 were not published: the first release in this repository is 0.11.0. They are listed for the record.

## Unreleased

- A forgotten admin password can be reset: put an empty file named `reset-admin` in the data folder. The server reads it once at start, and the setup page then asks for a new password and the setup key.
- The README and the server guide have clearer install steps.
- A workflow runs the server's tests on every pull request. This changelog and the security and contributing notes are new.
- The apps can be built on Linux with `bootstrap.sh`, and the workflow builds both on every push.

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
