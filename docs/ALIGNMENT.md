# How the alignment works

The engine lives in `core` (`SpeechAlignment.kt`) and runs on the server for server books and on the device for local imports. It is a chapter-level forced-alignment pipeline in the spirit of [Storyteller's CTC aligner](https://storyteller-platform.dev/blog/20260920_quartznet/) and [Honomiya](https://github.com/Natsume-197/Honomiya).

1. **Units.** Each audio chapter is a unit; chapters over 40 minutes are split with 30 s of overlap. A finished unit is committed atomically; an interrupted one is redone.
2. **Emissions.** Audio is decoded to 16 kHz mono (never written to disk) and run through NVIDIA QuartzNet 15x5 (int8, ONNX Runtime) in 38 s windows with 2 s of acoustic context. One pass gives per-character log-probabilities every 20 ms; there is no autoregressive decoding.
3. **Anchors.** A greedy decode of the emissions is matched against the normalised book text (lower-case ASCII, digits spelled out, abbreviations expanded) using five-word phrases that occur exactly once in the book. The longest chain that is monotonic in both text and audio, filtered for a plausible narration rate, locates the chapter and typically anchors every second word.
4. **Forced alignment.** Between anchors (thinned to about every 30 words) the exact text is aligned to the emissions with CTC Viterbi, giving a frame-accurate start and end for every word, including words the greedy decode misheard. Headings just before the first anchored sentence are included so chapter openings are covered.
5. **Sentences.** A sentence is stored only if every word aligned, its duration is plausible for its length, and its mean log-probability is above a floor; otherwise it stays unmatched rather than guessed. A measured 270 ms model lag is subtracted from starts, and consecutive sentences are made contiguous so narration-follow never falls into a gap.

No text-percentage or audio-duration estimation is used anywhere. Confidence values are mean per-frame probabilities, not calibrated timestamp certainty.

## Sentence lists

The server's parse of an EPUB is the sentence list the alignment indexes. Android's sentence splitter differs from the JVM's by a few sentences per book, so a server book on the device always takes the server's list (`/api/works/{id}/passages`); the reader finds sentences in the page by their text, so highlighting needs nothing else.

## Measured

- Emulator, 30 h 40 m book: 24,696 of 24,819 sentences matched, no ordering violations, 80.8 minutes wall time (23× real time including decoding).
- Server (i5-11400H, four workers of three threads): about 100 to 120 times real time per worker.
