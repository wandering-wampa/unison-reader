/* Unison web client — "Reading Room": sign in, browse the library as shelves of covers, listen with
   position sync, open the reader. One file, no build step. The reader lives in reader.js and is
   loaded when a book is opened. */
(() => {
const $ = (s, r = document) => r.querySelector(s);
const el = (tag, attrs = {}, ...children) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') n.className = v; else if (k === 'html') n.innerHTML = v; else if (k.startsWith('on')) n.addEventListener(k.slice(2), v); else if (v !== null && v !== undefined && v !== false) n.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) n.append(c.nodeType ? c : document.createTextNode(String(c)));
  return n;
};
const fmtClock = ms => { const s = Math.max(0, Math.round(ms / 1000)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' : '') + String(m).padStart(h ? 2 : 1, '0') + ':' + String(x).padStart(2, '0'); };
const fmtHours = ms => { const h = ms / 3600000; return h >= 10 ? Math.round(h) + ' h' : h >= 1 ? h.toFixed(1) + ' h' : Math.round(ms / 60000) + ' min'; };
const fmtLong = ms => { const m = Math.round(ms / 60000); return m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} m` : `${m} min`; };
const fmtDate = t => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
// A notice that leads somewhere: stays longer, and a click goes there.
const notice = (text, href, label) => { toast(`${text}  ·  ${label} ›`, 15000); const t = $('#toast'); t.style.cursor = 'pointer'; t.onclick = () => { t.hidden = true; location.hash = href; }; };
const fmtWhen = t => { const d = Date.now() - t; return d < 60000 ? 'just now' : d < 3600000 ? `${Math.round(d / 60000)} min ago` : d < 86400000 ? `${Math.round(d / 3600000)} h ago` : fmtDate(t); };
const fmtSpeed = v => (Number.isInteger(v) ? v + '.0' : String(v)) + '×';
const toast = (text, ms = 3000) => { const t = $('#toast'); t.onclick = null; t.style.cursor = ''; t.textContent = text; t.hidden = false; clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, ms); };

// ---------------------------------------------------------------- Theme: system, light or dark
const THEMES = ['system', 'light', 'dark'];
const themeIcon = { system: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"/></svg>', light: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/></svg>', dark: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/></svg>' };
const theme = { get() { return localStorage.getItem('unison.theme') || 'system'; }, set(v) { v === 'system' ? localStorage.removeItem('unison.theme') : localStorage.setItem('unison.theme', v); v === 'system' ? delete document.documentElement.dataset.theme : document.documentElement.dataset.theme = v; this.draw(); },
  draw() { const b = $('#theme'); if (b) { b.innerHTML = themeIcon[this.get()]; b.title = { system: 'Following the system (click for light)', light: 'Light (click for dark)', dark: 'Dark (click to follow the system)' }[this.get()]; } } };
$('#theme').onclick = () => theme.set(THEMES[(THEMES.indexOf(theme.get()) + 1) % THEMES.length]);
theme.draw();
// The sign-in logo follows the chosen theme, and the system's setting when none is chosen.
const logoSrc = () => { const t = document.documentElement.dataset.theme; return (t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches) ? '/brand/logo-dark.webp' : '/brand/logo-light.webp'; };

// ---------------------------------------------------------------- API
const store = { get token() { return localStorage.getItem('unison.token') || ''; }, set token(v) { v ? localStorage.setItem('unison.token', v) : localStorage.removeItem('unison.token'); },
  get user() { try { return JSON.parse(localStorage.getItem('unison.user') || 'null'); } catch { return null; } }, set user(v) { v ? localStorage.setItem('unison.user', JSON.stringify(v)) : localStorage.removeItem('unison.user'); } };
async function api(path, opts = {}) {
  const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
  if (store.token) headers.Authorization = 'Bearer ' + store.token;
  headers['X-Device'] = deviceId();
  // Days and hours in the statistics are counted where the reader is, not where the server is.
  try { headers['X-Timezone'] = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch {}
  if (opts.body && typeof opts.body !== 'string') { opts.body = JSON.stringify(opts.body); headers['Content-Type'] = 'application/json'; }
  const r = await fetch(path, Object.assign({}, opts, { headers }));
  if (r.status === 401 && path !== '/api/login') { store.token = ''; store.user = null; localStorage.removeItem('unison.cookie'); route(); throw new Error('Sign in first'); }
  if (!r.ok) { let m = r.statusText, body = null; try { body = await r.json(); m = body.message || m; } catch {} throw Object.assign(new Error(m), { status: r.status, body }); }
  const ct = r.headers.get('content-type') || '';
  return ct.includes('json') ? r.json() : r;
}
// One id per browser, so positions and sessions are kept per device like the app's.
const deviceId = () => { let d = localStorage.getItem('unison.device'); if (!d) { d = 'web-' + Math.random().toString(36).slice(2, 10); localStorage.setItem('unison.device', d); } return d; };
// Covers, audio and the beacons carry no token: the sign-in cookie (HttpOnly, same site) signs for them.
const coverUrl = id => `/api/works/${id}/cover`;
const audioUrl = (id, n) => `/api/works/${id}/audio/${n}`;
// A request that must survive the tab closing; the cookie signs for it.
// A save sent as the page goes away. `base` (the server time of the place this browser last knew) lets the server refuse it when another device has saved since: a stale tab closing must not move the book.
const beacon = (path, body, base) => navigator.sendBeacon?.(`${path}?device=${encodeURIComponent(deviceId())}${Number.isFinite(base) ? '&base=' + base : ''}`, new Blob([JSON.stringify(body)], { type: 'application/json' }));

// The server's work record says which files it has by path; the client wants plain flags.
const norm = w => Object.assign(w, { hasEbook: !!w.ebookPath, hasAudio: !!w.audioDir || w.duration > 0, audioOnly: !!w.audioOnly });
const rows = list => list.map(r => Object.assign(norm(r.work), { sync: r.shelf }));
const cache = { works: null, shelf: null, lists: null, details: new Map(), stats: null, chapters: new Map(), anchors: new Map() };
// The sentence being narrated at an audio time, from the book's alignment (anchors sorted by start); null without one.
function sentenceAt(id, ms) {
  const a = cache.anchors.get(id); if (!a?.length) return null;
  let lo = 0, hi = a.length - 1; while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (a[mid].start <= ms) lo = mid; else hi = mid - 1; }
  return a[lo].start <= ms ? a[lo].passage : a[0].passage;
}
async function works() { return cache.works || (cache.works = rows(await api('/api/works'))); }
async function shelf(force) { if (force || !cache.shelf) cache.shelf = await api('/api/me/shelf'); return cache.shelf; }
async function detail(id, force) { if (force || !cache.details.has(id)) { const d = await api(`/api/works/${id}`); norm(d.work).sync = d.shelf; cache.details.set(id, d); } return cache.details.get(id); }

// Chapters as the book names them: the ebook's table of contents placed in the audio through the alignment.
// Falls back to the audio file's own chapter marks (often just numbers, and off by the front matter) and then to tracks.
async function loadBookChapters(d) {
  const id = d.work.id;
  if (cache.chapters.has(id)) return cache.chapters.get(id);
  let result = null;
  if (d.shelf === 'ready' && d.work.hasEbook && d.tracks.length) {
    try {
      const [stored, anchors] = await Promise.all([api(`/api/works/${id}/passages`), api(`/api/works/${id}/alignment`)]);
      cache.anchors.set(id, [...anchors].sort((a, b) => a.start - b.start));
      anchors.sort((a, b) => a.passage - b.passage);
      const list = [];
      for (const t of stored.toc || []) {
        // The first measured sentence at or after the heading, within a page or so.
        let lo = 0, hi = anchors.length - 1; while (lo < hi) { const mid = (lo + hi) >> 1; if (anchors[mid].passage < t.passage) lo = mid + 1; else hi = mid; }
        const a = anchors[lo]; if (!a || a.passage - t.passage > 40) continue;
        const start = Math.max(0, a.start - 4000);
        if (list.length && start <= list[list.length - 1].start + 15000) continue;
        list.push({ title: t.title, start });
      }
      if (list.length > 1) { if (list[0].start > 60000) list.unshift({ title: 'Front matter', start: 0 }); else list[0].start = 0; result = list; }
    } catch {}
  }
  cache.chapters.set(id, result);
  return result;
}
const chaptersOf = d => {
  const book = cache.chapters.get(d.work.id);
  const list = book ? book.map(c => ({ title: c.title, start: c.start })) : d.chapters?.length > 1 ? d.chapters.map(c => ({ title: c.title, start: c.start })) : d.tracks.map((t, i) => ({ title: t.name || `Part ${i + 1}`, start: d.tracks.slice(0, i).reduce((a, x) => a + x.duration, 0) }));
  const total = d.tracks.reduce((a, t) => a + t.duration, 0);
  return list.map((c, i) => Object.assign(c, { end: list[i + 1]?.start ?? total, index: i }));
};
const chapterAt = (d, ms) => { const cs = chaptersOf(d); let i = 0; while (i < cs.length - 1 && ms >= cs[i + 1].start) i++; return cs[i] || null; };
// Chapter files are often just numbered; say "Chapter 12" rather than "012".
const chapterName = c => c ? (/^\d+$/.test(c.title.trim()) ? `Chapter ${+c.title}` : c.title) : '';

// ---------------------------------------------------------------- Sessions: what the Stats page counts
const sessions = {
  queue: (() => { try { return JSON.parse(localStorage.getItem('unison.sessions') || '[]'); } catch { return []; } })(),
  open: null,
  save() { localStorage.setItem('unison.sessions', JSON.stringify(this.queue)); },
  start(work, kind) { this.end(); this.open = { work, kind, started: Date.now(), sentences: 0, pages: 0 }; },
  end() { const s = this.open; if (!s) return; this.open = null; const done = Object.assign({}, s, { ended: Date.now() }); if (done.ended - done.started >= 3000) { this.queue.push(done); this.save(); } },
  count(what, n = 1) { if (this.open) this.open[what] += n; },
  async flush(withBeacon) {
    if (this.open && withBeacon) { const s = Object.assign({}, this.open, { ended: Date.now() }); if (s.ended - s.started >= 3000) this.queue.push(s); this.open.started = Date.now(); this.open.sentences = 0; this.open.pages = 0; }
    if (!this.queue.length || !store.token) return;
    const batch = this.queue; this.queue = []; this.save();
    if (withBeacon) { beacon('/api/me/sessions', batch); return; }
    try { await api('/api/me/sessions', { method: 'POST', body: batch }); cache.stats = null; } catch { this.queue = batch.concat(this.queue); this.save(); }
  },
};
setInterval(() => sessions.flush(false), 60000);

// ---------------------------------------------------------------- Player
const audio = $('#audio');
const SPEEDS = [0.75, 0.8, 1, 1.1, 1.2, 1.25, 1.5, 1.75, 2];
const SLEEPS = [['off', 'Sleep'], [15, 'Sleep · 15 min'], [30, 'Sleep · 30 min'], [60, 'Sleep · 1 h'], ['chapter', 'Sleep · end of chapter']];
const player = {
  work: null, detail: null, tracks: [], track: 0, speed: Number(localStorage.getItem('unison.speed') || 1), pushed: '', timer: null, listeners: new Set(), sleep: { mode: 'off', at: 0 },
  offset(n) { let t = 0; for (let i = 0; i < n; i++) t += this.tracks[i].duration; return t; },
  total() { return this.offset(this.tracks.length); },
  globalMs() { return this.work ? this.offset(this.track) + Math.round(audio.currentTime * 1000) : 0; },
  isPlaying() { return !!this.work && !audio.paused; },
  onTime(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); },
  chapters() { return this.detail ? chaptersOf(this.detail) : []; },
  chapterAt(ms) { return this.detail ? chapterAt(this.detail, ms) : null; },
  async open(id, { play = false, at = null } = {}) {
    const d = await detail(id, true);
    if (!d.tracks.length) { toast('This book has no audio'); return; }
    await loadBookChapters(d);
    const changed = !this.work || this.work.id !== id;
    if (changed) {
      if (this.work) { this.push(); sessions.end(); }
      this.work = d.work; this.detail = d; this.tracks = d.tracks; this.track = -1;
      // The book's own speed from the server; a book never set starts at the last speed chosen here.
      this.speed = d.speed?.updated > 0 ? d.speed.speed : Number(localStorage.getItem('unison.speed') || 1);
      $('#now').href = `#/listen/${id}`; $('#now b').textContent = d.work.title; $('#now').hidden = false;
      if ('mediaSession' in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: d.work.title, artist: [d.work.author, d.work.narrators?.length ? 'read by ' + d.work.narrators.join(', ') : null].filter(Boolean).join(' · '), album: 'Unison', artwork: d.work.cover ? [{ src: location.origin + coverUrl(id), sizes: '512x512', type: 'image/jpeg' }] : [] });
    } else this.detail = d;
    const start = at !== null ? at : (changed ? (d.position?.audio || 0) : this.globalMs());
    if (changed || at !== null) await this.seekTo(start, false);
    if (changed) { this.pushed = `${id}:${this.globalMs()}`; places.known.set(id, d.position?.updated || 0); }
    else if (at === null && !(await this.catchUp())) { this.line(); return; }
    audio.playbackRate = this.speed;
    if (play) audio.play().catch(() => toast('Tap play to start'));
    this.line();
  },
  trackFor(ms) { let n = 0; while (n < this.tracks.length - 1 && ms >= this.offset(n + 1)) n++; return n; },
  async seekTo(ms, keepPlaying = true) {
    ms = Math.max(0, Math.min(ms, this.total() - 500));
    const n = this.trackFor(ms); const wasPlaying = !audio.paused;
    if (n !== this.track) {
      this.track = n; audio.src = audioUrl(this.work.id, n);
      await new Promise(res => { const done = () => { audio.removeEventListener('loadedmetadata', done); res(); }; audio.addEventListener('loadedmetadata', done); audio.load(); setTimeout(res, 4000); });
    }
    audio.currentTime = (ms - this.offset(n)) / 1000;
    if (keepPlaying && wasPlaying) audio.play().catch(() => {});
    this.line(); for (const fn of this.listeners) fn(this.globalMs());
  },
  toggle() { if (!this.work) return; if (!audio.paused) { audio.pause(); return; } this.catchUp().then(go => { if (go) audio.play().catch(() => {}); }); },
  /**
   * Before resuming a book this tab already had loaded: the server's newest place. Taken quietly when nothing moved
   * here since the last save; kept when both moved but stayed close; otherwise asked. False when the person dismissed
   * the question (nothing plays).
   */
  async catchUp() {
    if (!this.work) return true;
    const id = this.work.id;
    const remote = await api(`/api/works/${id}/position`).catch(() => null);
    if (!remote || remote.updated <= (places.known.get(id) || 0)) return true;
    return (await this.reconcile(remote, `${id}:${this.globalMs()}` !== this.pushed)) !== 'later';
  },
  /**
   * The rules for a newer place from another device. `moved`: this tab moved since its last save. Resolves 'there',
   * 'here', 'kept' (close enough, kept here) or 'later' (dismissed: nothing changed).
   */
  async reconcile(remote, moved) {
    const id = this.work.id;
    const g = this.globalMs();
    const here = { passage: sentenceAt(id, g) ?? 0, audio: g };
    const take = async () => { places.known.set(id, remote.updated); await this.seekTo(remote.audio, true); this.pushed = `${id}:${this.globalMs()}`; };
    if (!moved) { await take(); return 'there'; }
    if (!places.apart(here, remote)) { places.known.set(id, remote.updated); this.push(true); return 'kept'; }
    const choice = await places.ask(this.detail, remote, here);
    if (choice === 'there') await take();
    else if (choice === 'here') { places.known.set(id, remote.updated); this.push(true); }
    return choice;
  },
  setSpeed(v) {
    this.speed = v; localStorage.setItem('unison.speed', v); audio.playbackRate = v;
    if (this.work) { const id = this.work.id; api(`/api/works/${id}/speed`, { method: 'PUT', body: { speed: v } }).then(s => { const d = cache.details.get(id); if (d) d.speed = s; }).catch(() => {}); }
  },
  cycleSpeed() { this.setSpeed(SPEEDS[(SPEEDS.indexOf(this.speed) + 1) % SPEEDS.length]); },
  cycleSleep() { const i = SLEEPS.findIndex(([m]) => m === this.sleep.mode); const [mode] = SLEEPS[(i + 1) % SLEEPS.length]; this.sleep = { mode, at: typeof mode === 'number' ? Date.now() + mode * 60000 : 0 }; toast(mode === 'off' ? 'Sleep timer off' : this.sleepLabel()); },
  sleepLabel() { return SLEEPS.find(([m]) => m === this.sleep.mode)[1]; },
  // The only trace of the player outside its page and the reader: a small "now playing" chip in the header.
  line() {
    if (!this.work) return;
    const g = this.globalMs();
    $('#now span').textContent = `${fmtClock(g)} · ${chapterName(this.chapterAt(g))}`;
    $('#now').classList.toggle('playing', !audio.paused);
  },
  async push(force) {
    if (!this.work) return;
    const g = this.globalMs(); const key = `${this.work.id}:${g}`;
    if (!force && key === this.pushed) return;
    const prev = this.pushed; this.pushed = key;
    const passage = reader.passageForAudio ? reader.passageForAudio(g) : sentenceAt(this.work.id, g) ?? (await detail(this.work.id)).position?.passage ?? 0;
    const id = this.work.id;
    const r = await savePlace(id, { passage, audio: g }).catch(() => null);
    // A failed save is not a saved place: put the old value back (unless a later push has set its own) so the timer, the next push and the page-hide beacon try again.
    if (!r) { if (this.pushed === key) this.pushed = prev; return; }
    if (!r.conflict) { places.known.set(id, r.saved.updated); return; }
    // Another device saved since this tab last knew: the save is dropped and the person is asked, once at a time.
    // A question that was dismissed is not asked again for the same newer place.
    if (this.asking || this.work?.id !== id || this.dismissed === `${id}:${r.conflict.updated}`) return;
    this.asking = true;
    try { if (await this.reconcile(r.conflict, true) === 'later') this.dismissed = `${id}:${r.conflict.updated}`; } finally { this.asking = false; }
  },
  close() { this.push(); sessions.end(); audio.pause(); audio.removeAttribute('src'); this.work = null; this.detail = null; $('#now').hidden = true; for (const fn of this.listeners) fn(null); },
};
audio.addEventListener('timeupdate', () => {
  player.line(); const g = player.globalMs(); for (const fn of player.listeners) fn(g);
  const s = player.sleep;
  if (s.mode !== 'off' && !audio.paused) {
    const ch = player.chapterAt(g);
    const due = typeof s.mode === 'number' ? Date.now() >= s.at : (ch && g >= ch.end - 400);
    if (due) { audio.pause(); player.sleep = { mode: 'off', at: 0 }; toast('Sleep timer: paused'); }
  }
});
audio.addEventListener('play', () => { player.line(); clearInterval(player.timer); player.timer = setInterval(() => player.push(), 20000); if (player.work) sessions.start(player.work.id, 'listen'); for (const fn of player.listeners) fn(player.globalMs()); });
audio.addEventListener('pause', () => { player.line(); clearInterval(player.timer); player.push(); sessions.end(); sessions.flush(false); for (const fn of player.listeners) fn(player.globalMs()); });
audio.addEventListener('ended', () => { if (player.track < player.tracks.length - 1) player.seekTo(player.offset(player.track + 1)).then(() => audio.play()); else { player.push(); sessions.end(); } });
// The connection came back: a save that failed while offline is tried again without the person doing anything.
window.addEventListener('online', () => { if (player.work) player.push(); });
window.addEventListener('pagehide', () => {
  if (player.work && `${player.work.id}:${player.globalMs()}` !== player.pushed) beacon(`/api/works/${player.work.id}/position`, { passage: reader.passageForAudio?.(player.globalMs()) ?? sentenceAt(player.work.id, player.globalMs()) ?? 0, audio: player.globalMs() }, audio.paused ? places.known.get(player.work.id) : undefined);
  reader.module?.beacon?.();
  sessions.flush(true);
});
if ('mediaSession' in navigator) {
  navigator.mediaSession.setActionHandler('play', () => { if (audio.paused) player.toggle(); });
  navigator.mediaSession.setActionHandler('pause', () => audio.pause());
  navigator.mediaSession.setActionHandler('seekbackward', () => player.seekTo(player.globalMs() - 30000));
  navigator.mediaSession.setActionHandler('seekforward', () => player.seekTo(player.globalMs() + 30000));
}

// An ordinary position save. It names the server time of the place this browser last knew (`base`), so the server
// refuses it when another device has saved since. Resolves { saved } or { conflict: the newer place }; other failures throw.
async function savePlace(id, body) {
  const base = places.known.get(id);
  try { return { saved: await api(`/api/works/${id}/position${Number.isFinite(base) ? '?base=' + base : ''}`, { method: 'PUT', body }) }; }
  catch (e) { if (e.status === 409 && e.body && typeof e.body.updated === 'number') return { conflict: e.body }; throw e; }
}

// The reader module registers itself here when loaded.
const reader = { module: null, passageForAudio: null };

// ---------------------------------------------------------------- One place across devices
// The server time of the place this browser last had for each book: from opening it, a save, or a choice. A newer
// place on the server came from another device. The rules are the app's: taken when nothing moved here; when both
// moved, kept here if close (two minutes of audio, thirty sentences), otherwise the person is asked.
const places = {
  known: new Map(),
  apart(a, b) {
    const audioFar = a.audio > 0 && b.audio > 0 && Math.abs(a.audio - b.audio) > 120000;
    const textComparable = (a.passage > 0 && b.passage > 0) || !(a.audio > 0) || !(b.audio > 0);
    return audioFar || (textComparable && Math.abs((a.passage || 0) - (b.passage || 0)) > 30);
  },
  text(d, p) {
    if (!(p.passage > 0) && !(p.audio > 0)) return 'the beginning';
    if (p.audio > 0 && d.tracks?.length) return [chapterName(chapterAt(d, p.audio)), fmtClock(p.audio)].filter(Boolean).join(', ');
    return d.work.passages ? `${Math.round(100 * p.passage / d.work.passages)}% through` : `sentence ${p.passage}`;
  },
  ago(t) {
    const m = Math.round((Date.now() - t) / 60000), h = Math.round(m / 60), days = Math.round(m / 1440);
    return m < 1 ? 'just now' : m < 60 ? `${m} minute${m === 1 ? '' : 's'} ago` : m < 1440 ? `${h} hour${h === 1 ? '' : 's'} ago` : `${days} day${days === 1 ? '' : 's'} ago`;
  },
  // Resolves 'there', 'here', or 'later' (dismissed: nothing changes, and the question comes again next time).
  ask(d, remote, here) {
    // One question per book at a time: a second asker (a timer save, the reader and the player) gets the same answer.
    const open = this.pending.get(d.work.id); if (open) return open;
    const p = this.askNow(d, remote, here).finally(() => this.pending.delete(d.work.id));
    this.pending.set(d.work.id, p);
    return p;
  },
  pending: new Map(),
  askNow(d, remote, here) {
    return new Promise(resolve => {
      const dlg = el('dialog', { class: 'ask' });
      const done = v => { dlg.close(); dlg.remove(); resolve(v); };
      dlg.append(el('h2', {}, `Two places in ${d.work.title}`),
        el('p', {}, `${remote.by || 'Another device'} left it at ${places.text(d, remote)}, ${places.ago(remote.updated)}.`),
        el('p', {}, `This browser is at ${places.text(d, here)}.`),
        el('div', { class: 'acts' }, el('button', { class: 'ghost', onclick: () => done('here') }, 'Stay here'), el('button', { class: 'primary', onclick: () => done('there') }, 'Go there')));
      dlg.addEventListener('cancel', e => { e.preventDefault(); done('later'); });
      document.body.append(dlg); dlg.showModal();
    });
  },
};

// ---------------------------------------------------------------- Views
const view = $('#view');
let pageTeardown = null;
function show(...nodes) { view.replaceChildren(...nodes.flat(Infinity).filter(n => n !== null && n !== undefined && n !== false)); window.scrollTo(0, 0); }
function setTab(tab) { tab = { lists: 'journal', highlights: 'journal', stats: 'journal' }[tab] || tab; for (const a of document.querySelectorAll('#nav a')) a.classList.toggle('on', a.dataset.tab === tab); }
const coverOf = (w, cls = 'cover') => w.cover ? el('img', { class: cls, src: coverUrl(w.id), alt: '', loading: 'lazy' }) : el('div', { class: cls + ' empty' }, w.title);

function card(w, line) {
  return el('a', { class: 'card', href: `#/book/${w.id}` }, coverOf(w),
    el('div', { class: 't' }, w.title),
    el('div', { class: 's' }, [w.author, w.series?.[0] ? `${w.series[0].name}${w.series[0].sequence ? ' #' + +w.series[0].sequence : ''}` : null].filter(Boolean).join('  ·  ')),
    typeof line === 'number' ? el('div', { class: 'bar' + (line >= 100 ? ' done' : '') }, el('i', { style: `width:${line}%` })) : null,
    typeof line === 'string' ? el('div', { class: 's' }, line) : null);
}
// A sync that ended badly says so in its status; the percent it holds is the share of the audio measured, not progress.
const syncFailed = w => /^(Failed|Ebook could not be read)/.test(w.status || '');
const syncLine = w => w.sync === 'ready' ? 'Synced' : w.sync === 'preparing' ? (syncFailed(w) ? w.status : w.syncedPercent > 0 ? `Syncing · ${w.syncedPercent}%` : w.status || 'Waiting to sync') : w.hasAudio ? 'Audiobook only' : 'Ebook only';
const progressOf = (w, pos) => pos ? (w.passages > 0 && pos.passage > 0 ? Math.round(pos.passage * 100 / w.passages) : w.duration > 0 ? Math.round(pos.audio * 100 / w.duration) : 0) : 0;
const listRow = (href, cover, title, sub) => el('a', { class: 'row', href }, cover ? el('img', { class: 'cover', src: cover, alt: '', loading: 'lazy' }) : el('div', { class: 'cover' }), el('div', { class: 'grow' }, el('div', { class: 't' }, title), el('div', { class: 's' }, sub)), el('span', { class: 'muted' }, '›'));
const icon = name => ({
  book: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 5h7a3 3 0 0 1 3 3v12a2 2 0 0 0-2-2H4z"/><path d="M20 5h-7a3 3 0 0 0-3 3v12a2 2 0 0 1 2-2h8z"/></svg>',
  phones: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 13a8 8 0 0 1 16 0"/><rect x="3" y="13" width="4" height="7" rx="1.5"/><rect x="17" y="13" width="4" height="7" rx="1.5"/></svg>',
  prev: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M6 5h2v14H6zM19 5v14l-10-7z"/></svg>',
  next: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M16 5h2v14h-2zM5 5v14l10-7z"/></svg>',
})[name];
const svg = name => el('span', { html: icon(name), style: 'display:inline-flex' });

// The name this browser signs in under, as the device list shows it.
const webDevice = () => 'Web · ' + (navigator.userAgentData?.brands?.at(-1)?.brand || navigator.platform || 'browser');
const signedIn = a => { store.token = a.token; store.user = a.user; localStorage.setItem('unison.cookie', '1'); cache.shelf = null; cache.lists = null; cache.stats = null; location.hash = '#/shelf'; route(); };
// A server nobody has set up yet answers `needed`; once it says no, it never says yes again while this page is open (a reset happens only at start-up), so the answer is kept.
let setupDone = false;
async function setupStatus() {
  if (setupDone) return null;
  try { const s = await api('/api/setup'); if (!s.needed) setupDone = true; return s.needed ? s : null; } catch { return null; }
}

// The first run: the built-in admin has no password, so nobody can sign in. Say what the scan found and ask for one.
async function setupView(s) {
  $('#top').hidden = true;
  const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const found = el('div', { class: 'found' });
  const drawFound = l => {
    const warn = [];
    if (l.ebookFolderEmpty) warn.push('The ebook folder is empty. Check that it is the folder you meant to mount.');
    else if (l.scanned && !l.ebooks) warn.push(l.calibre ? 'The Calibre library has no EPUBs in it yet.' : 'No EPUBs were found in the ebook folder.');
    if (l.audiobookFolderEmpty) warn.push('The audiobook folder is empty. Check that it is the folder you meant to mount.');
    else if (l.scanned && !l.audiobooks) warn.push('No audiobooks were found. They are looked for in Author/Title/ folders with the audio files inside.');
    found.replaceChildren(el('span', { class: 'caption' }, l.scanned ? 'Found in your libraries' : 'Reading your libraries'),
      el('p', {}, `${count(l.ebooks, 'ebook', 'ebooks')} and ${count(l.audiobooks, 'audiobook', 'audiobooks')}${l.paired ? `, ${l.paired} of them as a pair` : ''}${l.scanned ? '.' : ' so far.'}`),
      ...(!l.calibre && !l.ebookFolderEmpty ? [el('p', { class: 'muted' }, 'The ebook folder has no metadata.db, so the EPUBs in it are read directly.')] : []),
      ...warn.map(w => el('p', { class: 'warn' }, w)),
      ...(l.scanned && warn.length ? [el('p', { class: 'muted' }, 'You can go on and fix the folders afterwards: the libraries are read again every two minutes.')] : []));
  };
  if (!s.reset) drawFound(s.library);
  // The first scan of a large library takes a while; the counts are kept up to date until it ends or the page is left.
  const poll = setInterval(async () => {
    if (!form.isConnected) return clearInterval(poll);
    try { const now = await api('/api/setup'); if (now.needed) { drawFound(now.library); if (now.library.scanned) clearInterval(poll); } else clearInterval(poll); } catch {}
  }, 3000);
  if (s.reset || s.library.scanned) clearInterval(poll);
  const key = s.key ? el('input', { id: 'setupKey', autocomplete: 'off', spellcheck: 'false' }) : null;
  const pass = el('input', { id: 'pass', type: 'password', autocomplete: 'new-password' });
  const again = el('input', { id: 'pass2', type: 'password', autocomplete: 'new-password' });
  const err = el('div', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'signin setup', onsubmit: async e => {
    e.preventDefault(); err.textContent = '';
    if (key && !key.value.trim()) { err.textContent = s.reset ? 'The setup key is needed to choose a new admin password.' : 'The setup key is needed from this address.'; return key.focus(); }
    if (pass.value.length < s.minPassword) { err.textContent = `A password is at least ${s.minPassword} characters.`; return pass.focus(); }
    if (pass.value !== again.value) { err.textContent = 'The two passwords are not the same.'; return again.focus(); }
    try {
      const a = await api('/api/setup', { method: 'POST', body: { password: pass.value, key: key ? key.value.trim() : '', web: true, device: webDevice() } });
      clearInterval(poll); setupDone = true; signedIn(a);
    } catch (x) {
      // Someone else finished the setup meanwhile (another tab, say): this is then a sign-in like any other.
      if (!await setupStatus()) { clearInterval(poll); return signIn('This server is already set up. Sign in as admin with the password that was chosen.'); }
      err.textContent = x.message;
    }
  } },
    el('img', { src: logoSrc(), alt: 'Unison' }),
    el('h1', {}, s.reset ? 'Choose a new admin password' : 'Set up this server'),
    el('p', { class: 'muted' }, s.reset ? 'The admin password was reset on the server. Choose a new one for admin. The other accounts are as they were.' : 'Nobody can sign in yet. Choose a password for the first account, admin. You add the other people afterwards, on the Admin page.'),
    s.reset ? null : found,
    key ? el('label', { class: 'field' }, 'Setup key', key, el('span', { class: 'hint' }, s.reset ? 'The contents of the file named token in the server’s data folder.' : 'You are not on the server’s own network, so it also asks for its setup key: the contents of the file named token in its data folder.')) : null,
    el('label', { class: 'field' }, 'Password for admin', pass, el('span', { class: 'hint' }, `${s.minPassword} characters or more.`)),
    el('label', { class: 'field' }, 'The same password again', again), err,
    el('button', { class: 'primary', type: 'submit' }, 'Set the password and sign in'),
    s.reset ? el('button', { type: 'button', class: 'link', onclick: () => { clearInterval(poll); signIn('', true); } }, 'Sign in with another account') : null);
  show(form);
  // Focused here rather than by autofocus, which scrolls a tall card past its own heading.
  (key || pass).focus({ preventScroll: true });
}

async function signIn(error, skipSetup) {
  const setup = skipSetup ? null : await setupStatus();
  if (setup) return setupView(setup);
  $('#top').hidden = true;
  const name = el('input', { id: 'name', autocomplete: 'username', autofocus: '' });
  const pass = el('input', { id: 'pass', type: 'password', autocomplete: 'current-password' });
  const err = el('div', { class: 'err' }, error || '');
  const form = el('form', { class: 'signin', onsubmit: async e => {
    e.preventDefault(); err.textContent = '';
    try {
      // web: true asks for the sign-in cookie that lets <img>, <audio> and the beacons authenticate without a header.
      const a = await api('/api/login', { method: 'POST', body: { name: name.value.trim(), password: pass.value, web: true, device: webDevice() } });
      signedIn(a);
    } catch (x) { err.textContent = x.message; }
  } },
    el('img', { src: logoSrc(), alt: 'Unison' }),
    el('p', { class: 'muted', style: 'text-align:center;margin:0' }, 'Your ebooks and audiobooks, in unison.'),
    el('label', { class: 'field' }, 'Name', name), el('label', { class: 'field' }, 'Password', pass), err,
    el('button', { class: 'primary', type: 'submit' }, 'Sign in'));
  show(form);
}

// This week, from the sessions the app and this client record.
async function weekPanel() {
  try {
    const r = cache.stats || (cache.stats = await api('/api/me/stats'));
    const days = r.days.slice(-7); const max = Math.max(1, ...days.map(d => d.listenMs + d.readMs));
    return el('aside', { class: 'panel' },
      el('span', { class: 'caption' }, 'This week'),
      el('div', { class: 'nums' }, el('div', {}, el('b', {}, fmtLong(r.week.listenMs)), el('span', {}, 'listening')), el('div', {}, el('b', {}, fmtLong(r.week.readMs)), el('span', {}, 'reading')), el('div', {}, el('b', {}, `${r.streak} ${r.streak === 1 ? 'day' : 'days'}`), el('span', {}, 'streak'))),
      el('div', { class: 'days' }, days.map(d => el('i', { class: d.listenMs + d.readMs ? '' : 'empty', style: `height:${Math.max(6, (d.listenMs + d.readMs) / max * 100)}%`, title: `${d.date}: ${fmtLong(d.listenMs)} listening · ${fmtLong(d.readMs)} reading` }))),
      el('div', { class: 'foot' }, el('span', {}, `${r.finished} finished · ${r.daysActive} ${r.daysActive === 1 ? 'day' : 'days'} with books`), el('a', { href: '#/stats' }, 'Stats')));
  } catch { return null; }
}

async function shelfView() {
  setTab('shelf');
  await sessions.flush(false);
  const [all, mine, progress, lists] = await Promise.all([works(), shelf(true), api('/api/me/series').catch(() => []), collections(true).catch(() => [])]);
  const byId = new Map(all.map(w => [w.id, w]));
  const want = (lists.find(c => c.kind === 'want')?.works || []).map(id => byId.get(id)).filter(Boolean);
  // The next book of each series with a finished book in it (never one already started), shelved or not; one per book.
  const nextUp = progress.filter(p => p.finished > 0 && p.next && byId.has(p.next)).filter((p, i, arr) => arr.findIndex(q => q.next === p.next) === i);
  const nextIds = new Set(nextUp.map(p => p.next));
  const reading = mine.filter(e => e.state !== 'finished' && !nextIds.has(e.work)).map(e => ({ e, w: byId.get(e.work) })).filter(x => x.w).sort((a, b) => (b.e.position?.updated || b.e.added) - (a.e.position?.updated || a.e.added));
  const finished = mine.filter(e => e.state === 'finished').map(e => ({ e, w: byId.get(e.work) })).filter(x => x.w).sort((a, b) => (b.e.finishedAt || 0) - (a.e.finishedAt || 0));
  const onShelf = new Set(mine.map(e => e.work));
  const ready = all.filter(w => w.sync === 'ready' && !onShelf.has(w.id));
  const preparing = all.filter(w => w.sync === 'preparing').sort((a, b) => syncFailed(a) - syncFailed(b) || b.syncedPercent - a.syncedPercent);
  const missing = all.filter(w => w.hasEbook !== w.hasAudio);
  const nodes = [];
  // Continue your story is a book already begun; a shelved book not yet opened waits in On my shelf.
  const begun = x => x.e.position && (x.e.position.passage > 0 || x.e.position.audio > 0);
  const current = reading.find(begun) || reading[0];
  const top = el('div', { class: 'two' });
  if (current) {
    const { w, e } = current; const pct = progressOf(w, e.position);
    const d = w.hasAudio ? await detail(w.id) : null;
    if (d) await loadBookChapters(d);
    const ch = d && e.position?.audio ? chapterAt(d, e.position.audio) : null;
    top.append(el('div', { class: 'hero' }, el('a', { href: `#/book/${w.id}` }, coverOf(w)),
      el('div', {},
        el('span', { class: 'caption' }, 'Continue your story'),
        el('h1', { style: 'margin-top:8px' }, w.title),
        el('p', { class: 'meta', style: 'font-size:16px;margin-top:4px' }, [w.author, w.series?.[0]?.name].filter(Boolean).join(' · ')),
        w.description ? el('p', { class: 'quote' }, w.description.replace(/<[^>]+>/g, '')) : null,
        el('div', { class: 'progress' },
          el('div', { class: 'l' }, el('span', {}, [ch ? chapterName(ch) : null, w.sync === 'ready' ? 'In sync' : null].filter(Boolean).join(' · ') || syncLine(w)), el('span', {}, `${pct}%${w.duration && e.position?.audio ? ' · ' + fmtLong(Math.max(0, w.duration - e.position.audio)) + ' left' : ''}`)),
          el('div', { class: 'bar' }, el('i', { style: `width:${pct}%` }))),
        el('div', { class: 'actions' },
          w.hasEbook ? el('a', { class: 'primary', href: `#/read/${w.id}` }, svg('book'), e.position?.passage > 0 ? 'Continue reading' : 'Start reading') : null,
          w.hasAudio ? el('a', { class: 'outline', href: `#/listen/${w.id}/play` }, svg('phones'), 'Listen') : null))));
  } else top.append(el('div', {}, el('h1', {}, 'Your library'), el('p', { class: 'muted', style: 'margin:6px 0 0' }, 'A good place to lose yourself. Add a book to your shelf to begin.')));
  const panel = await weekPanel(); if (panel) top.append(panel);
  nodes.push(top);
  if (reading.length > 1 || (reading.length === 1 && !current)) { nodes.push(el('div', { class: 'section' }, el('h2', {}, 'On my shelf'), el('span', { class: 'muted' }, `${reading.length} in progress`))); nodes.push(el('div', { class: 'grid' }, reading.map(({ w, e }) => card(w, progressOf(w, e.position))))); }
  else if (!reading.length) nodes.push(el('div', { class: 'section' }, el('h2', {}, 'On my shelf'), el('span', { class: 'muted' }, 'Browse eBooks or Audiobooks and add what you want to read next.')));
  if (nextUp.length) { nodes.push(el('div', { class: 'section' }, el('h2', {}, 'Next in your series'), el('span', { class: 'muted' }, nextUp.length))); nodes.push(el('div', { class: 'grid' }, nextUp.slice(0, 7).map(p => card(byId.get(p.next), (onShelf.has(p.next) ? 'On my shelf · ' : '') + `${p.finished} of ${p.total} finished`)))); }
  if (want.length) { nodes.push(el('div', { class: 'section' }, el('h2', {}, 'Want to read'), el('a', { class: 'muted', href: '#/lists/want' }, `${plural(want.length, 'book')} · All ›`))); nodes.push(el('div', { class: 'grid' }, want.slice(0, 7).map(w => card(w, syncLine(w))))); }
  nodes.push(el('div', { class: 'section' }, el('h2', {}, 'Ready to read & listen'), el('a', { class: 'muted', href: '#/ebooks' }, `${ready.length} books · All eBooks ›`)));
  nodes.push(el('div', { class: 'grid' }, ready.slice(0, 14).map(w => card(w))));
  const pair = el('div', { class: 'pair', style: 'margin-top:44px' });
  pair.append(el('div', {}, el('div', { class: 'section', style: 'margin-top:0' }, el('h2', {}, 'Getting ready'), el('span', { class: 'muted' }, preparing.length)),
    preparing.length ? el('div', { class: 'list' }, preparing.slice(0, 5).map(w => el('a', { class: 'row', href: `#/book/${w.id}` }, coverOf(w), el('div', { class: 'grow' }, el('div', { class: 't' }, w.title), el('div', { class: 's' }, el('span', { class: 'dot warn' }), syncLine(w)), w.syncedPercent > 0 && !syncFailed(w) ? el('div', { class: 'bar warn', style: 'margin-top:6px;max-width:220px' }, el('i', { style: `width:${w.syncedPercent}%` })) : null))),
      preparing.length > 5 ? el('a', { class: 'row', href: '#/ebooks', style: 'justify-content:center;color:var(--accent);font-weight:500' }, `All ${preparing.length} in eBooks ›`) : null) : el('p', { class: 'muted' }, 'Nothing syncing right now.')));
  pair.append(el('div', {}, el('div', { class: 'section', style: 'margin-top:0' }, el('h2', {}, 'Missing a piece'), el('span', { class: 'muted' }, missing.length)),
    el('div', { class: 'list' }, missing.filter(w => onShelf.has(w.id)).concat(missing.filter(w => !onShelf.has(w.id))).slice(0, 5).map(w => el('a', { class: 'row', href: `#/book/${w.id}` }, coverOf(w), el('div', { class: 'grow' }, el('div', { class: 't' }, w.title), el('div', { class: 's' }, w.hasAudio ? 'Audiobook only · pair it with an ebook' : 'Ebook only · an audiobook would pair')), el('span', { class: 'muted' }, '›'))),
      missing.length > 5 ? el('a', { class: 'row', href: '#/ebooks', style: 'justify-content:center;color:var(--accent);font-weight:500' }, `All ${missing.length} in eBooks ›`) : null)));
  nodes.push(pair);
  if (finished.length) { nodes.push(el('div', { class: 'section' }, el('h2', {}, 'Finished'), el('span', { class: 'muted' }, finished.length))); nodes.push(el('div', { class: 'grid' }, finished.map(({ w, e }) => card(w, 'Finished ' + fmtDate(e.finishedAt || e.added))))); }
  show(nodes);
}

// ---------------------------------------------------------------- Sections: eBooks and Audiobooks
const SECTIONS = { ebooks: { label: 'eBooks', fmt: 'ebook', other: 'audiobooks', has: w => w.hasEbook }, audiobooks: { label: 'Audiobooks', fmt: 'audio', other: 'ebooks', has: w => w.hasAudio } };
const SUBS = {
  ebooks: [['', 'All'], ['authors', 'Authors'], ['series', 'Series'], ['genres', 'Genres']],
  audiobooks: [['', 'All'], ['authors', 'Authors'], ['narrators', 'Narrators'], ['series', 'Series'], ['genres', 'Genres']],
  journal: [['lists', 'Lists'], ['highlights', 'Highlights'], ['stats', 'Stats']],
};
const enc = encodeURIComponent;
// The section last used, so a link from a book page or an old address lands where the person was.
let lastSection = (() => { try { const s = localStorage.getItem('unison.section'); return SECTIONS[s] ? s : 'ebooks'; } catch { return 'ebooks'; } })();
const rememberSection = sec => { lastSection = sec; try { localStorage.setItem('unison.section', sec); } catch {} };
const sectionFor = w => SECTIONS[lastSection].has(w) ? lastSection : w.hasEbook ? 'ebooks' : 'audiobooks';
const secHref = (sec, kind, name) => `#/${sec}/${kind}/${enc(name)}`;
// The row of a section page: text tabs, the current one underlined.
function subnav(section, current) {
  const href = k => section === 'journal' ? `#/${k}` : `#/${section}${k ? '/' + k : ''}`;
  return el('nav', { class: 'subnav', 'aria-label': (SECTIONS[section]?.label || 'Journal') + ' sections' }, SUBS[section].map(([k, label]) => el('a', { href: href(k), 'aria-current': k === current ? 'page' : null }, label)));
}
// The heading of a section page: its name, for the libraries how many books it holds, and the row to pick from.
async function sectionHead(section, current) {
  if (section === 'journal') return [el('h1', {}, 'Journal'), subnav('journal', current)];
  const n = (await works()).filter(SECTIONS[section].has).length;
  return [el('h1', {}, SECTIONS[section].label), el('div', { class: 'muted' }, plural(n, 'book')), subnav(section, current)];
}
// A cover grid that shows a page of it and the rest on request. `st.n` is kept by the caller so Back finds the same length.
function pagedGrid(list, lineOf, st) {
  const box = el('div');
  const render = () => box.replaceChildren(el('div', { class: 'grid' }, list.slice(0, st.n).map(w => card(w, lineOf(w)))),
    list.length > st.n ? el('div', { style: 'margin-top:22px' }, el('button', { class: 'ghost', onclick: () => { st.n += 120; render(); } }, `Show ${Math.min(120, list.length - st.n)} more`)) : document.createDocumentFragment());
  render();
  return box;
}
// A line under a detail page's count: the same author, series or genre in the other format.
async function alsoLine(sec, kind, name, path) {
  const o = SECTIONS[sec].other;
  try {
    const r = await api(`${path}?format=${SECTIONS[o].fmt}`);
    return r.length ? el('div', { style: 'margin-top:4px' }, el('a', { class: 'also', href: secHref(o, kind, name) }, `Also ${r.length} as ${o === 'audiobooks' ? 'audiobooks' : 'ebooks'} ›`)) : null;
  } catch { return null; }
}
const FILTERS = {
  ebooks: [['all', 'All'], ['other', 'With audiobook'], ['ready', 'Synced'], ['preparing', 'Syncing'], ['only', 'Ebook only']],
  audiobooks: [['all', 'All'], ['other', 'With ebook'], ['ready', 'Synced'], ['preparing', 'Syncing'], ['only', 'Audiobook only']],
};
const filterOf = { ebooks: 'all', audiobooks: 'all' }; const gridState = { ebooks: { n: 60 }, audiobooks: { n: 60 } };
const matchFilter = (sec, k, w) => k === 'all' || (k === 'other' ? (sec === 'ebooks' ? w.hasAudio : w.hasEbook) : k === 'only' ? (sec === 'ebooks' ? !w.hasAudio : !w.hasEbook) : w.sync === k);
async function booksView(sec) {
  setTab(sec);
  const [head, all] = await Promise.all([sectionHead(sec, ''), works()]);
  const list = all.filter(SECTIONS[sec].has); const k = filterOf[sec];
  const shown = list.filter(w => matchFilter(sec, k, w));
  show(head,
    el('div', { class: 'chips' }, FILTERS[sec].map(([key, label]) => el('button', { class: 'chip' + (k === key ? ' on' : ''), 'aria-pressed': String(k === key), onclick: () => { filterOf[sec] = key; gridState[sec].n = 60; booksView(sec); } }, label))),
    k !== 'all' ? el('div', { class: 'muted' }, plural(shown.length, 'book')) : null,
    pagedGrid(shown, syncLine, gridState[sec]));
}
let searchFilter = 'all'; const searchShown = { n: 60 };
async function searchView(query) {
  setTab(null);
  const list = rows(await api(`/api/works?q=${enc(query)}`));
  const filters = [['all', 'All'], ['ready', 'Synced'], ['preparing', 'Syncing'], ['ebook', 'Ebook only'], ['audio', 'Audiobook only']];
  const match = w => searchFilter === 'all' || (searchFilter === 'ebook' ? w.hasEbook && !w.hasAudio : searchFilter === 'audio' ? w.hasAudio && !w.hasEbook : w.sync === searchFilter);
  const shown = list.filter(match);
  show(el('h1', {}, `Results for “${query}”`),
    el('div', { class: 'chips' }, filters.map(([k, label]) => el('button', { class: 'chip' + (searchFilter === k ? ' on' : ''), 'aria-pressed': String(searchFilter === k), onclick: () => { searchFilter = k; searchShown.n = 60; searchView(query); } }, label))),
    el('div', { class: 'muted' }, plural(shown.length, 'book')),
    pagedGrid(shown, syncLine, searchShown),
    !list.length ? el('p', {}, 'Nothing in the library matches.  ', el('a', { href: `#/wanted/ask/${enc(query)}`, style: 'color:var(--accent);font-weight:500' }, 'Ask for it ›')) : null);
}

// Series, authors and narrators as the section sees them: lists and counts within its format.
const facets = new Map();
// Kept until the works are read again: whatever invalidates the works (a pairing, a rescan, an edit) starts these over too.
const facet = (path, fmt) => { if (!cache.works) facets.clear(); const key = `${path}|${fmt}`; return facets.has(key) ? Promise.resolve(facets.get(key)) : api(`${path}?format=${fmt}`).then(r => (facets.set(key, r), r)); };
async function seriesView(sec) {
  setTab(sec);
  const [head, list] = await Promise.all([sectionHead(sec, 'series'), facet('/api/series', SECTIONS[sec].fmt)]);
  show(head, el('div', { class: 'muted', style: 'margin-top:14px' }, `${list.length} series`),
    el('div', { class: 'list' }, list.map(s => listRow(secHref(sec, 'series', s.name), s.cover ? coverUrl(s.cover) : null, s.name,
      [s.authors.join(', ') || 'Various authors', plural(s.works, 'book'), s.ready ? `${s.ready} synced` : null, s.duration ? fmtHours(s.duration) + ' of audio' : null].filter(Boolean).join('  ·  ')))));
}
async function seriesPage(sec, name) {
  setTab(sec);
  const fmt = SECTIONS[sec].fmt;
  const [list, mine, progress, also] = await Promise.all([api(`/api/series/${enc(name)}?format=${fmt}`).then(rows), shelf(), api('/api/me/series').catch(() => []), alsoLine(sec, 'series', name, `/api/series/${enc(name)}`)]);
  const p = progress.find(x => x.name.toLowerCase() === name.toLowerCase());
  const finished = new Set(mine.filter(e => e.state === 'finished').map(e => e.work));
  const entry = id => mine.find(e => e.work === id);
  const numbered = w => { const seq = w.series?.find(s => s.name.toLowerCase() === name.toLowerCase())?.sequence; return Object.assign({}, w, { title: (seq ? `#${+seq}  ` : '') + w.title }); };
  const lineOf = w => finished.has(w.id) ? 'Finished' : entry(w.id) ? `On my shelf · ${progressOf(w, entry(w.id).position)}%` : syncLine(w);
  // After the last finished book: the one being read (Reading now) or, once that is finished, the next (Next up).
  const pinId = p?.next || p?.current; const pinned = pinId ? list.find(w => w.id === pinId) : null;
  show(el('a', { class: 'muted', href: `#/${sec}/series` }, '‹ Series'), el('h1', { style: 'margin-top:10px' }, name),
    el('div', { class: 'muted' }, [list.map(w => w.author).filter((a, i, arr) => a && arr.indexOf(a) === i).join(', '), p ? `${list.filter(w => finished.has(w.id)).length} of ${list.length} finished` : null].filter(Boolean).join('  ·  ')),
    also,
    pinned ? [el('div', { class: 'section' }, el('h2', {}, p.next ? 'Next up' : 'Reading now')), el('div', { class: 'grid' }, card(numbered(pinned), lineOf(pinned)))] : null,
    pinned ? el('div', { class: 'section' }, el('h2', {}, 'All books'), el('span', { class: 'muted' }, list.length)) : null,
    el('div', { class: 'grid' }, list.map(w => card(numbered(w), lineOf(w)))));
}
// Authors and narrators share one shape: a list of people with counts, and a person's books grouped by series.
async function peopleView(sec, kind) {
  setTab(sec);
  const [head, list] = await Promise.all([sectionHead(sec, kind), facet(`/api/${kind}`, SECTIONS[sec].fmt)]);
  const last = n => n.trim().split(/\s+/).pop().toLowerCase();
  const sorted = [...list].sort((a, b) => last(a.name).localeCompare(last(b.name)));
  const one = kind === 'authors' ? 'author' : 'narrator';
  show(head, el('div', { class: 'muted', style: 'margin-top:14px' }, `${sorted.length} ${kind}`),
    el('div', { class: 'list' }, sorted.map(p => listRow(secHref(sec, one, p.name), null, p.name, `${plural(p.works, 'book')}${p.ready ? '  ·  ' + p.ready + ' synced' : ''}`))));
}
async function personPage(sec, kind, name) {
  setTab(sec);
  const [list, also] = await Promise.all([api(`/api/${kind}/${enc(name)}?format=${SECTIONS[sec].fmt}`).then(rows), kind === 'authors' ? alsoLine(sec, 'author', name, `/api/authors/${enc(name)}`) : null]);
  const groups = new Map();
  for (const w of list) { const k = w.series?.[0]?.name || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(w); }
  const nodes = [el('a', { class: 'muted', href: `#/${sec}/${kind}` }, kind === 'authors' ? '‹ Authors' : '‹ Narrators'), el('h1', { style: 'margin-top:10px' }, name), el('div', { class: 'muted' }, `${plural(list.length, 'book')}${kind === 'narrators' ? ' narrated' : ''}`), also];
  for (const [k, ws] of [...groups.entries()].sort((a, b) => (a[0] === '') - (b[0] === '') || a[0].localeCompare(b[0]))) {
    nodes.push(el('div', { class: 'section' }, el('h2', {}, k ? el('a', { href: secHref(sec, 'series', k) }, k) : 'Standalone'), el('span', { class: 'muted' }, ws.length)));
    nodes.push(el('div', { class: 'grid' }, ws.sort((a, b) => (a.series?.[0]?.sequence || 0) - (b.series?.[0]?.sequence || 0)).map(w => card(w, syncLine(w)))));
  }
  show(nodes);
}
// Genres: the main ones as a list of rows; the rest on request, alphabetically.
async function genresView(sec) {
  setTab(sec);
  const [head, list] = await Promise.all([sectionHead(sec, 'genres'), api(`/api/genres?format=${SECTIONS[sec].fmt}`)]);
  const main = list.filter(g => g.main), rest = list.filter(g => !g.main).sort((a, b) => a.name.localeCompare(b.name));
  const rowsOf = gs => el('div', { class: 'glist' }, gs.map(g => el('a', { href: secHref(sec, 'genre', g.name) }, el('span', {}, g.name), el('span', { class: 'muted' }, g.works))));
  const more = el('div', { hidden: '' }, rowsOf(rest));
  const toggle = rest.length ? el('button', { class: 'ghost', style: 'margin-top:18px', 'aria-expanded': 'false', onclick: e => { const open = more.hidden; more.hidden = !open; e.currentTarget.setAttribute('aria-expanded', String(open)); e.currentTarget.textContent = open ? 'Show main genres only' : `Show all ${list.length}`; } }, `Show all ${list.length}`) : null;
  show(head, list.length ? [el('div', { class: 'muted', style: 'margin-top:14px' }, rest.length ? `${main.length} main genres` : plural(list.length, 'genre')), rowsOf(main), toggle, rest.length ? el('div', { style: 'margin-top:18px' }, more) : null]
    : el('p', { class: 'muted', style: 'margin-top:14px' }, 'No genres are recorded for these books yet.'));
}
async function genrePage(sec, name) {
  setTab(sec);
  const [list, also] = await Promise.all([api(`/api/genres/${enc(name)}?format=${SECTIONS[sec].fmt}`).then(rows), alsoLine(sec, 'genre', name, `/api/genres/${enc(name)}`)]);
  show(el('a', { class: 'muted', href: `#/${sec}/genres` }, '‹ Genres'), el('h1', { style: 'margin-top:10px' }, name), el('div', { class: 'muted' }, plural(list.length, 'book')), also,
    pagedGrid(list, syncLine, { n: 60 }));
}
// Addresses from before the sections: the section last used, or the other one when it has nothing under that name.
async function legacyDetail(kind, name) {
  const first = lastSection, second = SECTIONS[first].other;
  let target = first;
  try { if (!(await api(`/api/${kind === 'series' ? 'series' : 'authors'}/${enc(name)}?format=${SECTIONS[first].fmt}`)).length) target = second; } catch { target = second; }
  location.replace(secHref(target, kind, name));
}
async function sectionRoute(sec, [a, b]) {
  rememberSection(sec);
  if ((a === 'narrators' || a === 'narrator') && sec !== 'audiobooks') return location.replace(a === 'narrators' ? '#/audiobooks/narrators' : secHref('audiobooks', 'narrator', b || ''));
  switch (a) {
    case 'authors': return peopleView(sec, 'authors');
    case 'narrators': return peopleView(sec, 'narrators');
    case 'series': return b ? seriesPage(sec, b) : seriesView(sec);
    case 'genres': return genresView(sec);
    case 'author': return b ? personPage(sec, 'authors', b) : peopleView(sec, 'authors');
    case 'narrator': return b ? personPage(sec, 'narrators', b) : peopleView(sec, 'narrators');
    case 'genre': return b ? genrePage(sec, b) : genresView(sec);
    default: return booksView(sec);
  }
}

async function bookPage(id) {
  const [d, mine, lists] = await Promise.all([detail(id, true), shelf(), collections()]);
  const w = d.work; const entry = mine.find(e => e.work === id);
  const wanted = !!lists.find(c => c.kind === 'want')?.works.includes(id); const inLists = lists.filter(c => c.kind === 'list' && c.works.includes(id));
  const want = async on => { try { await api(`/api/me/collections/want/works/${id}`, { method: on ? 'PUT' : 'DELETE' }); cache.lists = null; toast(on ? 'On Want to read' : 'Off Want to read'); bookPage(id); } catch (x) { toast(x.message); } };
  await loadBookChapters(d);
  const marks = await api(`/api/me/marks/${id}`).catch(() => []);
  const pos = d.position; const pct = progressOf(w, pos);
  const shelve = async (state, finishedAt) => { await api(`/api/me/shelf/${id}`, { method: 'PUT', body: { state, finishedAt } }); cache.shelf = null; cache.lists = null; toast(state === 'finished' ? 'Marked as finished' : 'On your shelf'); bookPage(id); };
  const unshelve = async () => { await api(`/api/me/shelf/${id}`, { method: 'DELETE' }); cache.shelf = null; toast('Off your shelf'); bookPage(id); };
  const meta = [];
  if (w.narrators?.length) meta.push(el('div', { class: 'meta' }, 'Read by ', ...w.narrators.flatMap((n, i) => [i ? ', ' : '', el('a', { href: secHref('audiobooks', 'narrator', n) }, n)])));
  if (w.series?.length) meta.push(el('div', { class: 'meta' }, 'Series: ', ...w.series.flatMap((s, i) => [i ? ', ' : '', el('a', { href: secHref(sectionFor(w), 'series', s.name) }, s.name + (s.sequence ? ` #${+s.sequence}` : ''))])));
  meta.push(el('div', { class: 'meta' }, [w.hasAudio ? fmtClock(w.duration) + ' of audio' : null, w.passages ? w.passages.toLocaleString() + ' sentences' : null, w.publishedYear || null, w.rating ? '★ ' + w.rating.toFixed(1) : null].filter(Boolean).join('  ·  ')));
  const failed = syncFailed(w), syncing = d.shelf === 'preparing' && !failed;
  meta.push(el('div', { class: 'meta' }, w.audioOnly ? el('span', { class: 'good' }, el('span', { class: 'dot' }), 'Audio only · ready to listen') : d.shelf === 'ready' ? el('span', { class: 'good' }, el('span', { class: 'dot' }), `Synced · ${d.sentencesAligned.toLocaleString()} sentences matched`) : d.shelf === 'preparing' ? el('span', {}, el('span', { class: 'dot warn' }), syncLine(w), !failed && w.syncedPercent > 0 ? ` · ${d.units.filter(u => u.done).length} of ${d.units.length} chapters measured` : '') : el('span', {}, el('span', { class: 'dot off' }), w.hasAudio && w.hasEbook ? 'Not synced yet' : w.hasAudio ? 'Audiobook only · pair it with an ebook below' : 'Ebook only · an audiobook would pair')));
  if (w.genres?.length) meta.push(el('div', { class: 'meta' }, ...w.genres.flatMap((g, i) => [i ? ' · ' : '', el('a', { href: secHref(sectionFor(w), 'genre', g) }, g)])));
  if (wanted || inLists.length) meta.push(el('div', { class: 'meta' }, 'In ', ...[...(wanted ? [lists.find(c => c.kind === 'want')] : []), ...inLists].flatMap((c, i) => [i ? ', ' : '', el('a', { href: listHref(c) }, c.name)])));
  if (store.user?.role === 'admin' && w.edited?.length) meta.push(el('div', { class: 'meta' }, `Details edited in Unison: ${w.edited.map(f => ({ publishedYear: 'year', description: 'about' })[f] || f).join(', ')}`));
  const bm = marks.filter(m => m.kind === 'bookmark').length, hl = marks.filter(m => m.kind === 'highlight').length;
  if (bm || hl) meta.push(el('div', { class: 'meta' }, [bm ? `${bm} ${bm === 1 ? 'bookmark' : 'bookmarks'}` : null, hl ? `${hl} ${hl === 1 ? 'highlight' : 'highlights'}` : null].filter(Boolean).join(' · '), ' · in the reader'));
  const chapters = d.tracks.length ? chaptersOf(d) : [];
  const here = pos?.audio ? chapterAt(d, pos.audio) : null;
  // Sync and pairing: starting a sync is open to the household; Sync again and Pause syncing are for admins.
  const syncBox = (w.hasAudio && w.hasEbook) ? el('div', { class: 'box' }, el('span', { class: 'caption' }, 'Sync on the server'),
    el('div', { class: 'muted', style: 'font-size:14px' }, d.shelf === 'ready' ? 'Every chapter is measured. Sync again only if the ebook or audiobook file changed.' : failed ? (w.status.startsWith('Failed: only') ? "The server found too little of this book's text in the audiobook, so the text cannot follow the narration. The audiobook may be a different book or edition, or the book is not in English. You can still read it and listen to it." : 'The server could not finish measuring this book.') : syncing ? `${d.units.filter(u => u.done).length} of ${d.units.length} chapters measured so far. The server works through the queue on its own.` : 'Both files are here; the server has not measured this book yet.'),
    el('div', { class: 'actions', style: 'margin-top:12px' },
      d.shelf === 'ready' || w.status.startsWith('Failed: only') ? (store.user?.role === 'admin' ? el('button', { class: 'ghost', onclick: async e => { if (!confirm('Measure the whole book again? Reading positions are kept.')) return; e.target.disabled = true; await api(`/api/works/${id}/sync?restart=true`, { method: 'POST' }).catch(x => toast(x.message)); cache.works = null; toast('Syncing again'); bookPage(id); } }, 'Sync again') : null) : el('button', { class: 'primary', style: 'padding:9px 16px', onclick: async e => { e.target.disabled = true; await api(`/api/works/${id}/sync`, { method: 'POST' }).catch(x => toast(x.message)); cache.works = null; toast('Syncing'); bookPage(id); } }, 'Sync now'),
      syncing && store.user?.role === 'admin' ? el('button', { class: 'ghost', onclick: async e => { e.target.disabled = true; await api(`/api/works/${id}/sync`, { method: 'DELETE' }).catch(x => toast(x.message)); toast('Pausing after the current chapter'); setTimeout(() => bookPage(id), 1500); } }, 'Pause syncing') : null)) : null;
  let pairBox = null;
  if (w.hasAudio && !w.hasEbook && w.audioOnly) {
    pairBox = el('div', { class: 'box' }, el('span', { class: 'caption' }, 'Audio only'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'Marked audio-only: there is no ebook to pair with, and it is ready to listen to as it is.'),
      store.user?.role === 'admin' ? el('button', { class: 'ghost', style: 'margin-top:10px', onclick: async () => { await api(`/api/works/${id}/audio-only`, { method: 'DELETE' }).catch(x => toast(x.message)); cache.details.delete(id); cache.works = null; bookPage(id); } }, 'Wait for an ebook instead') : null);
  } else if (w.hasAudio && !w.hasEbook && store.user?.role === 'admin') {
    const q = el('input', { type: 'search', placeholder: 'Search ebooks by title or author', 'aria-label': 'Search ebooks' });
    const results = el('div', { class: 'pairList' });
    const search = async () => {
      const all = await works(); const t = q.value.trim().toLowerCase();
      const hits = all.filter(x => x.hasEbook && !x.hasAudio && (!t || `${x.title} ${x.author}`.toLowerCase().includes(t))).slice(0, 12);
      results.replaceChildren(...(hits.length ? hits.map(x => el('div', { class: 'row', role: 'button', tabindex: '0', onclick: async () => { if (!confirm(`Pair this audiobook with “${x.title}”? The server will rescan and start syncing.`)) return; try { await api(`/api/works/${id}/pair`, { method: 'PUT', body: { ebookWork: x.id } }); cache.works = null; toast('Paired · the server is rescanning'); setTimeout(() => { location.hash = '#/audiobooks'; }, 1200); } catch (e) { toast(e.message); } } }, coverOf(x), el('div', { class: 'grow' }, el('div', { class: 't' }, x.title), el('div', { class: 's' }, x.author)), el('span', { class: 'muted' }, 'Pair'))) : [el('p', { class: 'muted', style: 'margin:10px 0 0' }, t ? 'No ebook-only book matches.' : 'Type to search the ebooks that have no audiobook yet.')]));
    };
    q.addEventListener('input', search);
    pairBox = el('div', { class: 'box' }, el('span', { class: 'caption' }, 'Pair with an ebook'), el('p', { class: 'muted', style: 'margin:0 0 10px;font-size:14px' }, 'The scanner could not find this audiobook’s ebook by title and author. Pick it here and the server will pair them and sync.'), el('div', { class: 'inline' }, q), results);
    search();
  }
  show(
    el('div', { class: 'hero' }, coverOf(w),
      el('div', {},
        el('h1', {}, w.title), w.subtitle ? el('div', { class: 'meta' }, w.subtitle) : null,
        el('div', { class: 'meta', style: 'font-size:16px' }, ...(w.authors?.length ? w.authors : [w.author]).flatMap((a, i) => [i ? ', ' : '', el('a', { href: secHref(sectionFor(w), 'author', a) }, a)])),
        ...meta,
        pos && (pos.passage > 0 || pos.audio > 0) ? el('div', { class: 'progress' }, el('div', { class: 'l' }, el('span', {}, [here ? chapterName(here) : null, pos.audio ? fmtClock(pos.audio) : null].filter(Boolean).join(' · ') || 'Where you are'), el('span', {}, `${pct}% through your story`)), el('div', { class: 'bar' }, el('i', { style: `width:${pct}%` }))) : null,
        el('div', { class: 'actions' },
          w.hasEbook ? el('a', { class: 'primary', href: `#/read/${id}` }, svg('book'), pos?.passage > 0 ? 'Continue reading' : 'Read') : null,
          w.hasAudio ? el('a', { class: entry || !w.hasEbook ? 'primary' : 'outline', href: `#/listen/${id}/play` }, svg('phones'), pos?.audio > 0 ? 'Continue listening' : 'Listen') : null,
          entry && entry.state !== 'finished' ? el('button', { class: 'ghost', onclick: unshelve }, 'Remove from my shelf') : entry ? el('button', { class: 'ghost', onclick: () => shelve('reading') }, 'Mark as unfinished') : el('button', { class: 'ghost', onclick: () => shelve('reading') }, 'Add to my shelf'),
          entry && entry.state !== 'finished' ? el('button', { class: 'ghost', onclick: () => shelve('finished', Date.now()) }, 'Mark as finished') : null,
          !entry ? el('button', { class: 'ghost' + (wanted ? ' on' : ''), 'aria-pressed': String(wanted), onclick: () => want(!wanted) }, wanted ? '✓ Want to read' : 'Want to read') : null,
          el('button', { class: 'ghost', onclick: () => collectionsBox(w, lists, () => bookPage(id)) }, inLists.length ? `Collections · ${inLists.length}` : 'Collections'),
          store.user?.role === 'admin' ? el('a', { class: 'ghost', href: `#/book/${id}/edit` }, 'Edit details') : null))),
    w.description ? el('div', { class: 'desc' }, el('div', { class: 'caption' }, 'About'), el('p', { style: 'margin-top:8px' }, w.description.replace(/<[^>]+>/g, ''))) : null,
    chapters.length > 1 ? el('div', { class: 'desc', style: 'max-width:640px;font-family:var(--sans);font-size:15px' }, el('div', { class: 'caption' }, `${chapters.length} chapters`),
      el('div', { class: 'list' }, chapters.map((c, i) => el('div', { class: 'row' + (here && here.index === i ? ' on' : ''), role: 'button', tabindex: '0', onclick: () => { location.hash = `#/listen/${id}`; player.open(id, { play: true, at: c.start }); } }, el('span', { class: 'n' }, i + 1), el('div', { class: 'grow' }, chapterName(c)), el('span', { class: 's' }, fmtClock(c.start)))))) : null,
    syncBox, pairBox);
}

// Listening: the cover, one big button, and the chapters beside it.
async function listenView(id, play) {
  setTab(null);
  const d = await detail(id, true); const w = d.work;
  if (!d.tracks.length) return show(el('p', { class: 'err' }, 'This book has no audio.'));
  // Listen from the shelf or a book page lands here and starts playing; the address then drops the /play so a reload stays quiet.
  if (play) { history.replaceState(null, '', `#/listen/${id}`); await player.open(id, { play: true }); }
  else if (player.work?.id !== id) await player.open(id, { play: false });
  const chapters = player.chapters();
  const h1 = el('h1', {}, w.title); const sub = el('p', { class: 'sub' }, '');
  const tA = el('span'), tB = el('span');
  const fill = el('i'), knob = el('b');
  const rail = el('div', { class: 'rail', onclick: e => { const r = rail.getBoundingClientRect(); const ch = player.chapterAt(player.globalMs()); if (!ch) return; player.seekTo(ch.start + (e.clientX - r.left) / r.width * (ch.end - ch.start)); } }, fill, knob);
  const playBtn = el('button', { class: 'round play big', 'aria-label': 'Play or pause', onclick: () => player.toggle() });
  const sleepBtn = el('button', { class: 'ghost', onclick: () => { player.cycleSleep(); sleepBtn.textContent = player.sleepLabel(); } }, player.sleepLabel());
  const speedBtn = el('button', { class: 'ghost', onclick: () => { player.cycleSpeed(); speedBtn.textContent = fmtSpeed(player.speed); } }, fmtSpeed(player.speed));
  const pill = el('span', { class: 'pill' }, el('span', { class: 'dot' }), d.shelf === 'ready' ? 'In sync with your page' : 'Audiobook');
  let shown = Math.min(chapters.length, 12);
  const list = el('div', { class: 'chapters' });
  const renderChapters = () => {
    const ch = player.chapterAt(player.globalMs());
    const from = ch && ch.index >= shown ? Math.max(0, ch.index - 4) : 0;
    list.replaceChildren(el('div', { class: 'head' }, el('h2', {}, 'Chapters'), el('span', { class: 'muted', style: 'font-size:13px' }, `${chapters.length} · ${fmtLong(player.total())}`)),
      ...chapters.slice(from, from + shown).map(c => el('div', { class: 'ch' + (ch && ch.index === c.index ? ' on' : ''), role: 'button', tabindex: '0', onclick: () => player.seekTo(c.start) }, el('span', { class: 'n' }, c.index + 1), el('span', { class: 'grow' }, chapterName(c)), el('span', { class: 's' }, fmtClock(c.end - c.start)))),
      shown < chapters.length ? el('button', { class: 'ghost more', onclick: () => { shown = chapters.length; renderChapters(); } }, `All ${chapters.length} chapters`) : document.createDocumentFragment());
  };
  let lastIdx = -2;
  const update = ms => {
    if (ms === null) return;
    const ch = player.chapterAt(ms); const playing = player.isPlaying();
    playBtn.classList.toggle('playing', playing);
    if (ch) {
      const f = Math.min(1, Math.max(0, (ms - ch.start) / Math.max(1, ch.end - ch.start)));
      sub.textContent = `${chapterName(ch)} · ${w.author}${w.narrators?.length ? ' · read by ' + w.narrators.join(', ') : ''}`;
      tA.textContent = fmtClock(ms - ch.start); tB.textContent = `−${fmtClock(ch.end - ms)} in chapter · ${fmtClock(ms)} of ${fmtClock(player.total())} · ${fmtLong(Math.max(0, player.total() - ms) / (player.speed || 1))} left${player.speed && player.speed !== 1 ? ' at ' + fmtSpeed(player.speed) : ''}`;
      fill.style.width = `${f * 100}%`; knob.style.left = `${f * 100}%`;
      if (ch.index !== lastIdx) { lastIdx = ch.index; renderChapters(); }
    }
  };
  pageTeardown = player.onTime(update); update(player.globalMs());
  show(el('a', { class: 'muted', href: `#/book/${id}` }, '‹ ' + w.title),
    el('div', { class: 'listening' },
      el('div', { class: 'stage' }, coverOf(w),
        el('div', {}, h1, sub),
        el('div', { class: 'seek' }, rail, el('div', { class: 'times' }, tA, tB)),
        el('div', { class: 'controls' },
          el('button', { class: 'round plain', 'aria-label': 'Previous chapter', onclick: () => { const ch = player.chapterAt(player.globalMs()); if (!ch) return; player.seekTo(player.globalMs() - ch.start > 3000 ? ch.start : (chapters[ch.index - 1]?.start ?? 0)); } }, svg('prev')),
          el('button', { class: 'round soft big', 'aria-label': 'Back 30 seconds', onclick: () => player.seekTo(player.globalMs() - 30000) }, '−30'),
          playBtn,
          el('button', { class: 'round soft big', 'aria-label': 'Forward 30 seconds', onclick: () => player.seekTo(player.globalMs() + 30000) }, '+30'),
          el('button', { class: 'round plain', 'aria-label': 'Next chapter', onclick: () => { const ch = player.chapterAt(player.globalMs()); const n = chapters[(ch?.index ?? -1) + 1]; if (n) player.seekTo(n.start); } }, svg('next'))),
        el('div', { class: 'chips' }, speedBtn, sleepBtn, w.hasEbook ? el('a', { class: 'outline', style: 'padding:8px 16px', href: `#/read/${id}` }, svg('book'), 'Read from here') : null),
        pill),
      list));
}

// ---------------------------------------------------------------- Highlights
// One book's marks as Markdown: highlights as quotes with their notes, then bookmarks. `chapterOf` names a sentence's chapter when known.
function marksMarkdown(title, author, total, marks, chapterOf) {
  const where = m => [chapterOf?.(m.passage), total ? Math.round(m.passage * 100 / total) + '%' : null].filter(Boolean).join(' · ');
  const sorted = [...marks].sort((a, b) => a.passage - b.passage);
  const hl = sorted.filter(m => m.kind === 'highlight'), bm = sorted.filter(m => m.kind === 'bookmark');
  const out = [`# ${title}`, author ? `*${author}*` : null, ''];
  if (hl.length) { out.push('## Highlights', ''); for (const m of hl) { out.push('> ' + (m.text || '').replace(/\s+/g, ' ').trim(), '>', `> — ${where(m)}`, ''); if (m.note) out.push('Note: ' + m.note.replace(/\s*\n\s*/g, ' '), ''); } }
  if (bm.length) { out.push('## Bookmarks', ''); for (const m of bm) out.push(`- ${where(m)}: “${(m.text || '').replace(/\s+/g, ' ').trim().slice(0, 140)}”${m.note ? ' (' + m.note.replace(/\s*\n\s*/g, ' ') + ')' : ''}`); out.push(''); }
  return out.filter(l => l !== null).join('\n');
}
function download(name, text) {
  const a = el('a', { href: URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' })), download: name.replace(/[\\/:*?"<>|]+/g, ' ').trim() });
  document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}
async function highlightsView() {
  setTab('journal');
  const books = await api('/api/me/marks');
  const count = k => books.reduce((n, b) => n + b.marks.filter(m => m.kind === k).length, 0);
  const md = b => marksMarkdown(b.title, b.author, b.passages, b.marks);
  const nodes = await sectionHead('journal', 'highlights');
  if (!books.length) { show(nodes, el('p', { class: 'muted', style: 'margin-top:14px' }, 'Nothing marked yet. In a book, select text to highlight it, or bookmark a page with the ribbon.')); return; }
  nodes.push(el('div', { class: 'muted', style: 'margin-top:14px;display:flex;gap:14px;align-items:center;flex-wrap:wrap' }, `${count('highlight')} ${count('highlight') === 1 ? 'highlight' : 'highlights'} · ${count('bookmark')} ${count('bookmark') === 1 ? 'bookmark' : 'bookmarks'} · ${books.length} ${books.length === 1 ? 'book' : 'books'}`,
    el('button', { class: 'ghost', onclick: () => download('Unison highlights.md', books.map(md).join('\n---\n\n')) }, 'Download all as Markdown')));
  for (const b of books) {
    nodes.push(el('div', { class: 'section' }, el('h2', {}, el('a', { href: `#/book/${b.work}` }, b.title)), el('span', { class: 'muted' }, b.author), el('span', { style: 'flex:1' }),
      el('button', { class: 'ghost', onclick: () => download(`${b.title} - highlights.md`, md(b)) }, 'Markdown')));
    nodes.push(el('div', { class: 'list marks' }, b.marks.map(m => el('a', { class: 'row', href: `#/read/${b.work}/${m.passage}` },
      el('div', { class: 'grow' },
        el('div', { class: 'quote' + (m.kind === 'highlight' ? ' hl' : '') }, m.kind === 'bookmark' ? 'Bookmark · ' : '', m.text || '…'),
        m.note ? el('div', { class: 'note' }, m.note) : null,
        el('div', { class: 's' }, [b.passages ? Math.round(m.passage * 100 / b.passages) + '%' : null, m.created ? new Date(m.created).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : null].filter(Boolean).join(' · ')))))));
  }
  show(nodes);
}

// ---------------------------------------------------------------- Year in review
const HEAT = ['rgba(127,127,127,.16)', 'rgba(0,183,188,.30)', 'rgba(0,183,188,.52)', 'rgba(0,183,188,.76)', 'rgb(0,183,188)'];
const heatLevel = ms => ms <= 0 ? 0 : ms < 15 * 60000 ? 1 : ms < 45 * 60000 ? 2 : ms < 90 * 60000 ? 3 : 4;
// Where each day of a year sits in a grid of weeks (columns) that start on Monday (rows).
function yearCells(year, byDate) {
  const cells = []; const first = new Date(year, 0, 1); const offset = (first.getDay() + 6) % 7;
  for (let d = new Date(year, 0, 1), i = 0; d.getFullYear() === year; d.setDate(d.getDate() + 1), i++) {
    const key = `${year}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    const ms = byDate.get(key) || 0; cells.push({ key, ms, col: Math.floor((i + offset) / 7), row: (i + offset) % 7 });
  }
  return cells;
}
const heatmap = (year, byDate) => el('div', { class: 'heat', role: 'img', 'aria-label': `Days with books in ${year}` }, yearCells(year, byDate).map(c => el('i', { style: `grid-column:${c.col + 1};grid-row:${c.row + 1};background:${HEAT[heatLevel(c.ms)]}`, title: c.ms ? `${c.key}: ${fmtHours(c.ms)}` : c.key })));
const hourName = h => h < 0 ? '' : `${(h + 11) % 12 + 1} ${h < 12 ? 'am' : 'pm'}`;
// The poster: one image of the year, drawn on a canvas so it can be saved or copied and shared.
async function drawPoster(canvas, r, name) {
  const W = 1080, H = 1350, x = canvas.getContext('2d'); canvas.width = W; canvas.height = H;
  const serif = 'Literata, Georgia, serif', sans = 'system-ui, -apple-system, "Segoe UI", sans-serif';
  try { await Promise.all([document.fonts.load(`500 120px Literata`), document.fonts.load(`400 30px Literata`)]); } catch {}
  const ink = '#06293D', blue = '#064D73', teal = '#00B7BC', muted = '#5E6B70';
  x.fillStyle = '#F8F5ED'; x.fillRect(0, 0, W, H);
  x.fillStyle = blue; x.fillRect(0, 0, W, 14);
  x.textBaseline = 'alphabetic';
  x.fillStyle = muted; x.font = `600 26px ${sans}`; x.letterSpacing = '6px'; x.fillText('UNISON  ·  YEAR IN REVIEW', 80, 110); x.letterSpacing = '0px';
  x.fillStyle = ink; x.font = `500 190px ${serif}`; x.fillText(String(r.year), 72, 300);
  x.fillStyle = muted; x.font = `400 34px ${serif}`; x.fillText(`${name}’s year with books`, 80, 356);
  const total = r.listenMs + r.readMs;
  const stat = (px, py, value, label) => { x.fillStyle = blue; x.font = `500 84px ${serif}`; x.fillText(value, px, py); x.fillStyle = muted; x.font = `400 28px ${sans}`; x.fillText(label, px, py + 44); };
  stat(80, 500, fmtHours(total), 'with books'); stat(420, 500, String(r.finished.length), r.finished.length === 1 ? 'book finished' : 'books finished'); stat(760, 500, String(r.daysActive), r.daysActive === 1 ? 'day with a book' : 'days with books');
  x.fillStyle = ink; x.font = `400 30px ${sans}`;
  x.fillText([`${fmtHours(r.listenMs)} listening`, `${fmtHours(r.readMs)} reading`, r.longestStreak ? `longest streak ${r.longestStreak} ${r.longestStreak === 1 ? 'day' : 'days'}` : null].filter(Boolean).join('   ·   '), 80, 620);
  // The books the year was spent with: up to three covers.
  x.fillStyle = muted; x.font = `600 24px ${sans}`; x.letterSpacing = '5px'; x.fillText('MOST TIME WITH', 80, 706); x.letterSpacing = '0px';
  const top = r.books.slice(0, 3); const cw = 190, ch = 285, gap = (W - 160 - 3 * cw) / 2;
  const clip = (text, width) => { let t = text; while (t.length > 3 && x.measureText(t).width > width) t = t.slice(0, -2).trimEnd() + '…'; return t; };
  await Promise.all(top.map(async (b, i) => {
    const px = 80 + i * (cw + gap), py = 736;
    x.fillStyle = '#E4DFD2'; x.fillRect(px, py, cw, ch);
    try { const img = new Image(); img.src = coverUrl(b.work); await img.decode(); const s = Math.max(cw / img.width, ch / img.height); const sw = cw / s, sh = ch / s; x.drawImage(img, (img.width - sw) / 2, (img.height - sh) / 2, sw, sh, px, py, cw, ch); } catch {}
    x.fillStyle = ink; x.font = `500 28px ${serif}`; x.fillText(clip(b.title, Math.min(cw + gap - 30, W - 60 - px)), px, py + ch + 42);
    x.fillStyle = muted; x.font = `400 24px ${sans}`; x.fillText(clip(fmtHours(b.listenMs + b.readMs), cw + gap - 30), px, py + ch + 76);
  }));
  if (!top.length) { x.fillStyle = muted; x.font = `400 30px ${serif}`; x.fillText('Nothing listened to or read yet this year.', 80, 800); }
  // Every day of the year.
  const cells = yearCells(r.year, new Map(r.days.map(d => [d.date, d.listenMs + d.readMs]))); const size = 15, step = 17.3, gx = 80, gy = 1165;
  for (const c of cells) { x.fillStyle = HEAT[heatLevel(c.ms)]; x.beginPath(); x.roundRect(gx + c.col * step, gy + c.row * step, size, size, 3); x.fill(); }
  x.fillStyle = muted; x.font = `400 24px ${sans}`;
  const foot = [r.topAuthor ? `Most read author: ${r.topAuthor}` : null, r.topHour >= 0 ? `Favourite hour: ${hourName(r.topHour)}` : null].filter(Boolean).join('   ·   ');
  x.fillText(clip(foot, W - 160), 80, 1318);
  x.fillStyle = teal; x.fillRect(0, H - 8, W, 8);
}
async function yearView(year) {
  setTab('journal');
  await sessions.flush(false);
  const r = await api('/api/me/year' + (/^\d{4}$/.test(year || '') ? '/' + year : ''));
  const name = store.user?.name || (await api('/api/me')).user.name;
  const stat = (label, value) => el('div', { class: 'stat' }, el('b', {}, value), el('span', { class: 'muted' }, label));
  const canvas = el('canvas', { class: 'poster', 'aria-label': `Your ${r.year} in review, as an image` });
  const file = `Unison ${r.year} - ${name}.png`;
  const blob = () => new Promise(done => canvas.toBlob(done, 'image/png'));
  const save = el('button', { class: 'primary', onclick: async () => { const a = el('a', { href: URL.createObjectURL(await blob()), download: file }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 5000); } }, 'Download image');
  const copy = el('button', { class: 'ghost', onclick: async () => { try { await navigator.clipboard.write([new ClipboardItem({ 'image/png': await blob() })]); toast('Image copied'); } catch { toast('This browser cannot copy images; use Download'); } } }, 'Copy image');
  const byDate = new Map(r.history.map(d => [d.date, d.listenMs + d.readMs]));
  const maxMonth = Math.max(1, ...r.months.map(m => m.listenMs + m.readMs));
  const monthName = m => new Date(2000, m - 1, 1).toLocaleDateString(undefined, { month: 'short' });
  show(el('a', { class: 'muted', href: '#/stats' }, '‹ Stats'),
    el('h1', { style: 'margin-top:10px' }, `Your ${r.year}`),
    el('div', { class: 'chips', style: 'margin-top:10px' }, r.years.map(y => el('a', { class: y === r.year ? 'primary' : 'ghost', href: `#/stats/year/${y}`, style: 'padding:6px 14px' }, String(y)))),
    el('div', { class: 'yearbox' },
      el('div', { class: 'col' }, canvas, el('div', { class: 'chips' }, save, copy)),
      el('div', { class: 'col grow' },
        el('div', { class: 'stats', style: 'margin-top:0' }, stat('With books', fmtHours(r.listenMs + r.readMs)), stat('Listening', fmtHours(r.listenMs)), stat('Reading', fmtHours(r.readMs)), stat('Finished', r.finished.length), stat('Days with books', r.daysActive), stat('Longest streak', `${r.longestStreak} ${r.longestStreak === 1 ? 'day' : 'days'}`),
          r.busiestDay ? stat('Biggest day', `${new Date(r.busiestDay.date + 'T12:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} · ${fmtHours(r.busiestDay.listenMs + r.busiestDay.readMs)}`) : null,
          r.topHour >= 0 ? stat('Favourite hour', hourName(r.topHour)) : null),
        el('div', { class: 'section', style: 'margin-top:26px' }, el('h2', {}, 'Month by month')),
        el('div', { class: 'days tall months' }, r.months.map(m => el('i', { class: m.listenMs + m.readMs ? '' : 'empty', style: `height:${Math.max(2, (m.listenMs + m.readMs) / maxMonth * 100)}%`, title: `${monthName(m.month)}: ${fmtHours(m.listenMs)} listening · ${fmtHours(m.readMs)} reading` }))),
        el('div', { class: 'monthnames' }, r.months.map(m => el('span', {}, monthName(m.month)))))),
    r.finished.length ? [el('div', { class: 'section' }, el('h2', {}, `Finished in ${r.year}`), el('span', { class: 'muted' }, r.finished.length)),
      el('div', { class: 'list' }, r.finished.map(b => listRow(`#/book/${b.work}`, coverUrl(b.work), b.title, `${b.author}  ·  ${new Date(b.finishedAt).toLocaleDateString(undefined, { month: 'long', day: 'numeric' })}`)))] : [],
    r.books.length ? [el('div', { class: 'section' }, el('h2', {}, 'Most time with')),
      el('div', { class: 'list' }, r.books.map(b => listRow(`#/book/${b.work}`, coverUrl(b.work), b.title, `${b.author}  ·  ${fmtHours(b.listenMs)} listening · ${fmtHours(b.readMs)} reading`)))] : [],
    el('div', { class: 'section' }, el('h2', {}, 'Every day, every year'), el('span', { class: 'muted' }, 'a square per day; darker is longer')),
    r.years.slice().sort((a, b) => b - a).map(y => el('div', { class: 'heatyear' }, el('a', { class: 'muted', href: `#/stats/year/${y}` }, String(y)), heatmap(y, byDate))));
  await drawPoster(canvas, r, name);
}

async function statsView() {
  setTab('journal');
  await sessions.flush(false);
  const r = cache.stats = await api('/api/me/stats');
  const stat = (label, value) => el('div', { class: 'stat' }, el('b', {}, value), el('span', { class: 'muted' }, label));
  const tot = t => `${fmtHours(t.listenMs)} listening · ${fmtHours(t.readMs)} reading`;
  const days = r.days.slice(-28); const max = Math.max(1, ...days.map(d => d.listenMs + d.readMs));
  show(await sectionHead('journal', 'stats'), el('div', { style: 'margin-top:14px' }, el('a', { class: 'outline', href: '#/stats/year', style: 'padding:7px 16px' }, `Your ${new Date().getFullYear()} in review ›`)),
    el('div', { class: 'stats' }, stat('Today', tot(r.today)), stat('This week', tot(r.week)), stat('This month', tot(r.month)), stat('This year', tot(r.year)), stat('Streak', `${r.streak} days`), stat('Longest streak', `${r.longestStreak} days`), stat('Days with books', r.daysActive), stat('Finished', r.finished)),
    el('div', { class: 'section' }, el('h2', {}, 'Last four weeks'), el('span', { class: 'muted' }, 'a bar per day')),
    el('div', { class: 'days tall' }, days.map(d => el('i', { class: d.listenMs + d.readMs ? '' : 'empty', style: `height:${Math.max(2, (d.listenMs + d.readMs) / max * 100)}%`, title: `${d.date}: ${tot(d)}` }))),
    el('div', { class: 'section' }, el('h2', {}, 'Most time with')),
    el('div', { class: 'list' }, r.books.slice(0, 10).map(b => listRow(`#/book/${b.work}`, null, b.title, `${b.author}  ·  ${tot(b)}`))));
}

// Account: who I am, my password, my signed-in devices, the site's look; the household for admins.
async function accountView() {
  setTab(null);
  const me = await api('/api/me'); store.user = me.user;
  const isAdmin = me.user.role === 'admin';
  const p0 = el('input', { type: 'password', autocomplete: 'current-password' }), p1 = el('input', { type: 'password', autocomplete: 'new-password' }), p2 = el('input', { type: 'password', autocomplete: 'new-password' });
  const pwMsg = el('div', { class: 'muted', style: 'margin-top:10px;font-size:13px' });
  const pwForm = el('form', { onsubmit: async e => { e.preventDefault(); if (!p0.value) return pwMsg.textContent = 'Your current password is needed too.'; if (p1.value.length < 8) return pwMsg.textContent = 'Eight characters at least.'; if (p1.value !== p2.value) return pwMsg.textContent = 'The two new entries do not match.'; try { await api('/api/me/password', { method: 'POST', body: { current: p0.value, password: p1.value } }); p0.value = p1.value = p2.value = ''; pwMsg.textContent = 'Password changed. Your other devices were signed out.'; } catch (x) { pwMsg.textContent = x.message; } } },
    el('label', { class: 'field' }, 'Current password', p0), el('label', { class: 'field' }, 'New password', p1), el('label', { class: 'field' }, 'Again', p2), pwMsg, el('button', { class: 'primary', type: 'submit', style: 'margin-top:14px' }, 'Change password'));
  const mine = store.token.slice(0, 8);
  const devices = el('div', { class: 'list devices' }, me.devices.sort((a, b) => b.lastSeen - a.lastSeen).map(t => el('div', { class: 'row' }, el('div', { class: 'grow' }, el('div', { class: 't' }, t.device || 'A device', t.token.startsWith(mine) ? el('span', { class: 'good', style: 'font-size:12px;margin-left:8px' }, 'this browser') : null), el('div', { class: 's' }, `Last seen ${fmtWhen(t.lastSeen)} · signed in ${fmtDate(t.created)}`)),
    el('button', { class: 'ghost danger', onclick: async e => { if (!confirm(`Sign out ${t.device || 'this device'}?`)) return; await api('/api/me/tokens/' + t.token.slice(0, 8), { method: 'DELETE' }); if (t.token.startsWith(mine)) { store.token = ''; store.user = null; player.close(); route(); } else { toast('Signed out'); accountView(); } } }, t.token.startsWith(mine) ? 'Sign out here' : 'Sign out'))));
  const seg = el('div', { class: 'seg' }, THEMES.map(t => el('button', { class: theme.get() === t ? 'on' : '', onclick: e => { theme.set(t); for (const b of seg.children) b.classList.toggle('on', b === e.currentTarget); } }, { system: 'System', light: 'Light', dark: 'Dark' }[t])));
  const cols = [
    el('div', { class: 'panel' }, el('h2', {}, 'Password'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, `Signed in as ${me.user.name} (${me.user.role}).`), pwForm),
    el('div', { class: 'panel' }, el('h2', {}, 'Signed-in devices'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'Each device keeps its own key. Signing one out only affects that device.'), devices),
    el('div', { class: 'panel' }, el('h2', {}, 'Look'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'The library pages follow the system, or stay light or dark. The reader has its own papers.'), seg),
  ];
  if (isAdmin) cols.push(el('div', { class: 'panel' }, el('h2', {}, 'Household'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'People, roles, passwords, devices and the server live on the Admin page.'), el('a', { class: 'primary', href: '#/admin', style: 'align-self:flex-start' }, 'Open Admin')));
  show(el('h1', {}, 'Account'), el('div', { class: 'cols' }, cols));
}

/* Admin: the household's accounts with what each person has been doing, and the server. */
async function adminView() {
  setTab(null);
  const me = store.user || (await api('/api/me')).user;
  if (me.role !== 'admin') { location.hash = '#/shelf'; return; }
  const [people, health, audit] = await Promise.all([api('/api/users/overview'), api('/api/health'), api('/api/users/audit').catch(() => [])]);
  const hours = ms => ms >= 3600000 ? (ms / 3600000).toFixed(ms >= 36000000 ? 0 : 1) + ' h' : ms > 0 ? Math.round(ms / 60000) + ' min' : '—';
  const act = (label, fn, cls = 'ghost') => el('button', { class: cls, onclick: fn }, label);
  const refresh = () => adminView();
  const rowsEl = people.map(o => {
    const u = o.user; const self = u.id === me.id;
    const role = el('select', { disabled: self || u.id === 1 ? '' : null, onchange: async e => { try { await api(`/api/users/${u.id}/role`, { method: 'PUT', body: { role: e.target.value } }); toast(`${u.name} is now ${e.target.value}`); } catch (x) { toast(x.message); e.target.value = u.role; } } },
      el('option', { value: 'member', selected: u.role === 'member' ? '' : null }, 'Member'), el('option', { value: 'admin', selected: u.role === 'admin' ? '' : null }, 'Admin'));
    return el('tr', {},
      el('td', {}, el('div', { class: 'name' }, u.name, self ? el('span', { class: 'good', style: 'font-size:12px;margin-left:8px' }, 'you') : null), el('div', { class: 'muted', style: 'font-size:12px' }, `since ${fmtDate(u.created)}`)),
      el('td', {}, role),
      el('td', {}, `${o.reading} reading`, el('div', { class: 'muted', style: 'font-size:12px' }, `${o.queued} queued · ${o.finished} finished`)),
      el('td', {}, hours(o.listenMs), el('div', { class: 'muted', style: 'font-size:12px' }, `listening · ${hours(o.readMs)} reading`)),
      el('td', {}, o.devices ? `${o.devices} ${o.devices === 1 ? 'device' : 'devices'}` : 'none', el('div', { class: 'muted', style: 'font-size:12px' }, o.lastActive ? `active ${fmtWhen(o.lastActive)}` : 'never signed in')),
      el('td', {}, el('div', { class: 'acts' },
        act('Set password', async () => { const p = await askBox(`New password for ${u.name}`, 'Eight characters or more.', 'Set password', '', true); if (!p) return; if (p.length < 8) return toast('Eight characters at least'); try { await api(`/api/users/${u.id}/password`, { method: 'POST', body: { password: p } }); toast(`Password set for ${u.name}`); } catch (x) { toast(x.message); } }),
        o.devices ? act(self ? 'Sign out other devices' : 'Sign out everywhere', async () => { if (!confirm(`Sign ${u.name} out of ${o.devices} ${o.devices === 1 ? 'device' : 'devices'}? They sign in again with their password.`)) return; try { const r = await api(`/api/users/${u.id}/tokens`, { method: 'DELETE' }); toast(r.message); if (self) { store.token = ''; store.user = null; route(); return; } refresh(); } catch (x) { toast(x.message); } }) : null,
        act('Reset progress', async () => { if (!confirm(`Reset ${u.name}? Their shelf, finished list, positions, bookmarks and statistics are wiped on the server; the account and its devices stay. This cannot be undone.`)) return; try { const r = await api(`/api/users/${u.id}/data`, { method: 'DELETE' }); toast(r.message); refresh(); } catch (x) { toast(x.message); } }, 'ghost danger'),
        !self && u.id !== 1 ? act('Remove', async () => { if (!confirm(`Remove ${u.name}? Their shelf, positions, marks and statistics go with them. This cannot be undone.`)) return; try { await api(`/api/users/${u.id}`, { method: 'DELETE' }); toast(`${u.name} removed`); refresh(); } catch (x) { toast(x.message); } }, 'ghost danger') : null)));
  });
  const nName = el('input', { placeholder: 'Name', autocomplete: 'off', required: '' }), nPass = el('input', { type: 'password', placeholder: 'Password (eight characters or more)', autocomplete: 'new-password', minlength: 8, required: '' }), nRole = el('select', {}, el('option', { value: 'member' }, 'Member'), el('option', { value: 'admin' }, 'Admin'));
  const addForm = el('form', { class: 'inline', onsubmit: async e => { e.preventDefault(); try { await api('/api/users', { method: 'POST', body: { name: nName.value.trim(), password: nPass.value, role: nRole.value } }); toast(`${nName.value.trim()} added`); refresh(); } catch (x) { toast(x.message); } } }, nName, nPass, nRole, el('button', { class: 'primary', type: 'submit', style: 'padding:10px 16px' }, 'Add person'));
  const syncing = health.syncing || [];
  const backups = await api('/api/users/backups').catch(() => []);
  const backupLine = el('div', { class: 'muted', style: 'font-size:13px' }, backups.length ? `Last database backup ${fmtWhen(backups[0].written)} (${backups[0].name}, ${Math.round(backups[0].size / 1048576)} MB); ${backups.length} kept in the server's data folder. Include that folder in the host's own backups.` : 'No database backup yet.');
  const serverPanel = el('div', { class: 'panel wide' }, el('h2', {}, 'Server'),
    el('div', { class: 'serverline' },
      el('div', {}, el('b', {}, health.works), 'books'), el('div', {}, el('b', {}, health.ready), 'read & listen'), el('div', {}, el('b', {}, health.preparing), 'syncing'), el('div', {}, el('b', {}, health.missing), 'one half only'), el('div', {}, el('b', {}, health.version), 'UnisonServer')),
    ...(health.libraryNotes || []).map(n => {
      const partial = (health.libraryEmpty || []).includes('partial') && n.includes('were not found at the last check');
      const lib = partial ? 'partial' : (health.libraryEmpty || []).find(l => n.startsWith(`The ${l} folder is empty`));
      return el('div', { class: 'inline', style: 'font-size:14px;color:var(--warn);align-items:center;gap:10px;flex-wrap:wrap' }, el('span', {}, n),
        lib ? el('button', { class: 'ghost', onclick: async ev => {
          const b = ev.currentTarget;
          if (!b.dataset.sure) { b.dataset.sure = '1'; b.textContent = partial ? 'Yes, hide the books that were not found' : `Yes, the ${lib} folder is empty on purpose`; return; }
          try { await api('/api/scan/accept-empty?library=' + lib, { method: 'POST' }); toast('Scanning the library'); setTimeout(refresh, 2500); } catch (x) { toast(x.message); }
        } }, partial ? 'They were removed on purpose' : 'It is empty on purpose') : null);
    }),
    syncing.length ? el('div', { class: 'muted', style: 'font-size:13px' }, 'Syncing now: ' + syncing.map(sx => `${sx.title} (${sx.unitsDone}/${sx.units})`).join(', ')) : el('div', { class: 'muted', style: 'font-size:13px' }, 'Nothing syncing right now.'),
    el('div', { style: 'display:flex;gap:10px;flex-wrap:wrap' },
      act('Rescan the library', async () => { try { await api('/api/scan', { method: 'POST' }); toast('Scanning the library; new books appear as the scan finds them'); } catch (x) { toast(x.message); } }, 'primary'),
      el('a', { class: 'ghost', href: '#/admin/pairing' }, `Pairing (${health.missing} to look at)`),
      act('Back up the database now', async () => { try { const b = await api('/api/users/backup', { method: 'POST' }); toast(`Backup written: ${b.name}`); refresh(); } catch (x) { toast(x.message); } }),
      el('span', { class: 'muted', style: 'font-size:13px;align-self:center' }, 'A rescan reads the Calibre and audiobook folders again; nothing there is changed.')),
    backupLine);
  show(el('h1', {}, 'Admin'),
    el('div', { class: 'cols' },
      el('div', { class: 'panel wide' }, el('h2', {}, 'People'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'Everyone has their own shelf, positions, marks and statistics. Admins can manage people and the server.'),
        el('div', { style: 'overflow-x:auto' }, el('table', { class: 'table' }, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Role'), el('th', {}, 'Shelf'), el('th', {}, 'Time with books'), el('th', {}, 'Devices'), el('th', {}))), el('tbody', {}, rowsEl))),
        el('div', { class: 'caption', style: 'margin-top:6px' }, 'Add a person'), addForm),
      serverPanel,
      el('div', { class: 'panel wide' }, el('h2', {}, 'Recent activity'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'Sign-ins, failures and lockouts, device sign-outs, account changes and rescans, with the address they came from.'),
        audit.length ? el('div', { class: 'list audit' }, audit.slice(0, 40).map(a => el('div', { class: 'row', style: 'padding:9px 0' }, el('div', { class: 'grow' }, el('div', { class: 't', style: 'font-weight:500' }, el('span', { class: 'dot ' + (a.action.includes('failed') || a.action.includes('throttled') ? 'warn' : a.action.includes('signed in') ? '' : 'off') }), `${a.user} · ${a.action}`), el('div', { class: 's' }, [fmtWhen(a.time), a.address, a.detail].filter(Boolean).join(' · ')))))) : el('p', { class: 'muted' }, 'Nothing recorded yet.'))));
}

/* Admin → Pairing: every audiobook still waiting for an ebook, with the ebooks by the same author
   offered first; ebooks waiting for audio; and the audiobooks marked audio-only. */
async function pairingView() {
  setTab(null);
  const me = store.user || (await api('/api/me')).user;
  if (me.role !== 'admin') { location.hash = '#/shelf'; return; }
  const o = await api('/api/pairing');
  [...o.audioOnly, ...o.unpairedAudio, ...o.ebookOnly].forEach(norm);
  const ebookById = new Map(o.ebookOnly.map(w => [w.id, w]));
  const refresh = () => pairingView();
  const pair = async (audio, ebook) => { if (!confirm(`Pair “${audio.title}” with the ebook “${ebook.title}”? The server rescans and starts syncing.`)) return; try { await api(`/api/works/${audio.id}/pair`, { method: 'PUT', body: { ebookWork: ebook.id } }); toast('Paired; the sync starts shortly'); cache.works = null; setTimeout(refresh, 1500); } catch (x) { toast(x.message); } };
  const audioOnly = async w => { try { await api(`/api/works/${w.id}/audio-only`, { method: 'PUT' }); toast(`${w.title} is audio-only`); cache.works = null; refresh(); } catch (x) { toast(x.message); } };
  const unAudioOnly = async w => { try { await api(`/api/works/${w.id}/audio-only`, { method: 'DELETE' }); toast(`${w.title} waits for an ebook again`); cache.works = null; refresh(); } catch (x) { toast(x.message); } };
  const rowFor = w => {
    const sug = (o.suggestions[w.id] || []).map(id => ebookById.get(id)).filter(Boolean);
    const q = el('input', { type: 'search', placeholder: 'Find an ebook by title or author', style: 'width:100%;max-width:360px' });
    const hits = el('div', { class: 'pairList' });
    const render = list => hits.replaceChildren(...list.slice(0, 8).map(e => el('button', { class: 'ghost', style: 'margin:4px 6px 0 0', onclick: () => pair(w, e) }, `${e.title}${e.series?.[0] ? ` (${e.series[0].name}${e.series[0].sequence ? ' #' + +e.series[0].sequence : ''})` : ''} · ${e.author}`)));
    render(sug);
    q.addEventListener('input', () => { const t = q.value.trim().toLowerCase(); render(t ? o.ebookOnly.filter(e => `${e.title} ${e.author}`.toLowerCase().includes(t)) : sug); });
    return el('div', { class: 'row', style: 'align-items:flex-start' },
      coverOf(w, 'cover', 48),
      el('div', { class: 'grow' }, el('div', { class: 't' }, el('a', { href: `#/book/${w.id}` }, w.title)), el('div', { class: 's' }, [w.author, w.narrators?.join(', '), w.duration ? fmtClock(w.duration) : null].filter(Boolean).join(' · ')),
        el('div', { style: 'margin-top:8px' }, sug.length ? el('div', { class: 'muted', style: 'font-size:12px' }, `${sug.length} ${sug.length === 1 ? 'ebook' : 'ebooks'} by this author without audio:`) : el('div', { class: 'muted', style: 'font-size:12px' }, 'No ebook by this author is waiting; search the ebooks:'), hits, el('div', { style: 'margin-top:8px' }, q))),
      el('div', { class: 'acts' }, el('button', { class: 'ghost', onclick: () => audioOnly(w) }, 'Audio only')));
  };
  show(el('a', { class: 'muted', href: '#/admin' }, '‹ Admin'), el('h1', { style: 'margin-top:10px' }, 'Pairing'),
    el('p', { class: 'muted', style: 'margin:6px 0 0;font-size:14px' }, 'An audiobook and an ebook become one book when they are paired; the server then measures every sentence so the two stay in step. The scanner pairs by title and author, and by series and sequence; what it could not pair is here.'),
    el('div', { class: 'cols' },
      el('div', { class: 'panel wide' }, el('h2', {}, `Audiobooks waiting for an ebook`), el('span', { class: 'muted' }, `${o.unpairedAudio.length}`),
        o.unpairedAudio.length ? el('div', { class: 'list' }, o.unpairedAudio.map(rowFor)) : el('p', { class: 'muted' }, 'Every audiobook has its ebook, or is marked audio-only.')),
      el('div', { class: 'panel wide' }, el('h2', {}, 'Audio-only'), el('p', { class: 'muted', style: 'margin:0;font-size:14px' }, 'Listenable as they are; they no longer count as half a book. Clear the mark if an ebook turns up.'),
        o.audioOnly.length ? el('div', { class: 'list' }, o.audioOnly.map(w => el('div', { class: 'row' }, el('div', { class: 'grow' }, el('div', { class: 't' }, el('a', { href: `#/book/${w.id}` }, w.title)), el('div', { class: 's' }, w.author)), el('button', { class: 'ghost', onclick: () => unAudioOnly(w) }, 'Wait for an ebook')))) : el('p', { class: 'muted' }, 'None.')),
      el('div', { class: 'panel wide' }, el('h2', {}, 'Ebooks without audio'), el('span', { class: 'muted' }, `${o.ebookOnly.length} · readable as they are; an audiobook added to the server pairs by title`),
        el('div', { class: 'grid' }, o.ebookOnly.slice(0, 60).map(w => card(w, w.series?.[0]?.name || ''))), o.ebookOnly.length > 60 ? el('p', { class: 'muted' }, `and ${o.ebookOnly.length - 60} more; the eBooks section lists them under Ebook only.`) : null)));
}

async function openReader(id, at) {
  if (!reader.module) reader.module = await import('/reader.js?v=19');
  const d = await detail(id, true);
  await loadBookChapters(d);
  // A link to a sentence (#/read/<book>/<sentence>, from the highlights page) opens there, then becomes the plain address so a reload does not jump back.
  const openAt = /^\d+$/.test(at || '') ? Number(at) : null;
  if (openAt !== null) history.replaceState(null, '', `#/read/${id}`);
  await reader.module.open({ el, api, detail: d, token: store.token, openAt, marksMarkdown, download, player, chapterName, fmtClock, sessions, beacon, places, savePlace, onPassageForAudio: fn => { reader.passageForAudio = fn; }, close: () => { reader.passageForAudio = null; history.length > 1 ? history.back() : (location.hash = `#/book/${id}`); } });
}

// ---------------------------------------------------------------- Lists: Want to read, collections, the wanted list
const plural = (n, one, many = one + 's') => `${n} ${n === 1 ? one : many}`;
async function collections(force) { if (force || !cache.lists) cache.lists = await api('/api/me/collections'); return cache.lists; }
// A question in the page's own dialog. `input`: a text field with that value; resolves its text, true, or null when dismissed.
// `secret`: the field is a password field (hidden as typed, never offered to a password manager as a sign-in).
function askBox(title, text, okLabel, input, secret) {
  return new Promise(resolve => {
    const dlg = el('dialog', { class: 'ask' });
    const field = input !== undefined ? (secret ? el('input', { type: 'password', autocomplete: 'new-password', 'aria-label': title }) : el('input', { type: 'text', value: input, maxlength: '60', 'aria-label': title })) : null;
    const done = v => { dlg.close(); dlg.remove(); resolve(v); };
    dlg.append(el('form', { onsubmit: e => { e.preventDefault(); done(field ? field.value : true); } }, el('h2', {}, title), text ? el('p', {}, text) : null, field ? el('label', { class: 'field', style: 'margin-top:0' }, field) : null,
      el('div', { class: 'acts' }, el('button', { class: 'ghost', type: 'button', onclick: () => done(null) }, 'Cancel'), el('button', { class: 'primary', type: 'submit', style: 'padding:9px 18px' }, okLabel))));
    dlg.addEventListener('cancel', e => { e.preventDefault(); done(null); });
    document.body.append(dlg); dlg.showModal(); if (field) field.select();
  });
}
const listHref = c => `#/lists/${c.kind === 'want' ? 'want' : c.id}`;
async function listsView() {
  setTab('journal');
  const [all, lists] = await Promise.all([works(), collections(true)]);
  const byId = new Map(all.map(w => [w.id, w]));
  const name = el('input', { type: 'text', maxlength: '60', placeholder: 'A new collection', 'aria-label': 'Name of a new collection' });
  const form = el('form', { class: 'inline', style: 'margin-top:18px;max-width:520px', onsubmit: async e => { e.preventDefault(); if (!name.value.trim()) return name.focus(); try { const c = await api('/api/me/collections', { method: 'POST', body: { name: name.value } }); cache.lists = null; location.hash = `#/lists/${c.id}`; } catch (x) { toast(x.message); } } },
    name, el('button', { class: 'primary', type: 'submit', style: 'padding:9px 18px' }, 'Make collection'));
  show(await sectionHead('journal', 'lists'), el('div', { style: 'margin-top:14px' }, el('a', { class: 'outline', href: '#/wanted', style: 'padding:7px 16px' }, 'The wanted list ›')),
    form,
    el('div', { class: 'list', style: 'margin-top:22px' }, lists.map(c => {
      const first = c.works.map(id => byId.get(id)).find(w => w?.cover);
      return listRow(listHref(c), first ? coverUrl(first.id) : null, c.name, c.works.length ? plural(c.works.length, 'book') : c.kind === 'want' ? 'Nothing yet' : 'Empty');
    })));
}
async function listPage(key) {
  setTab('journal');
  const [all, lists, mine] = await Promise.all([works(), collections(true), shelf()]);
  const c = lists.find(x => key === 'want' ? x.kind === 'want' : String(x.id) === key);
  const back = el('a', { class: 'muted', href: '#/lists' }, '‹ Your lists');
  if (!c) { show(back, el('p', { class: 'err' }, 'That collection is gone.')); return; }
  const byId = new Map(all.map(w => [w.id, w])); const books = c.works.map(id => byId.get(id)).filter(Boolean);
  const k = c.kind === 'want' ? 'want' : c.id;
  const remove = async w => { try { await api(`/api/me/collections/${k}/works/${w.id}`, { method: 'DELETE' }); cache.lists = null; toast(`Off ${c.name}`); listPage(key); } catch (x) { toast(x.message); } };
  const rename = async () => { const n = await askBox('Rename this collection', '', 'Rename', c.name); if (n === null || !n.trim() || n.trim() === c.name) return; try { await api(`/api/me/collections/${k}`, { method: 'PUT', body: { name: n } }); cache.lists = null; listPage(key); } catch (x) { toast(x.message); } };
  const drop = async () => { if (!await askBox(`Remove “${c.name}”?`, 'The collection goes; its books stay in the library.', 'Remove')) return; try { await api(`/api/me/collections/${k}`, { method: 'DELETE' }); cache.lists = null; location.hash = '#/lists'; } catch (x) { toast(x.message); } };
  const line = w => { const e = mine.find(x => x.work === w.id); return e ? (e.state === 'finished' ? 'Finished' : `On my shelf · ${progressOf(w, e.position)}%`) : syncLine(w); };
  show(back, el('h1', { style: 'margin-top:10px' }, c.name),
    el('div', { class: 'muted', style: 'display:flex;gap:14px;align-items:center;flex-wrap:wrap' }, plural(books.length, 'book'),
      c.kind === 'list' ? [el('button', { class: 'ghost', onclick: rename }, 'Rename'), el('button', { class: 'ghost danger', onclick: drop }, 'Remove collection')] : null),
    books.length ? el('div', { class: 'grid' }, books.map(w => el('div', { class: 'cardx' }, card(w, line(w)), el('button', { class: 'ghost small', title: `Take ${w.title} off ${c.name}`, onclick: () => remove(w) }, 'Remove'))))
      : el('p', { class: 'muted', style: 'margin-top:22px' }, c.kind === 'want' ? 'Nothing yet. On a book’s page, choose Want to read.' : 'Nothing yet. On a book’s page, choose Collections.'));
}
// Which collections a book is in: tick boxes that save as they are ticked, and a field for a new one.
function collectionsBox(w, lists, onClose) {
  const dlg = el('dialog', { class: 'ask' });
  const rowsEl = el('div', { class: 'ticks' });
  const draw = () => rowsEl.replaceChildren(...lists.filter(c => c.kind === 'list').map(c => el('label', {}, el('input', { type: 'checkbox', checked: c.works.includes(w.id) ? '' : null, onchange: async e => {
    const on = e.target.checked;
    try { await api(`/api/me/collections/${c.id}/works/${w.id}`, { method: on ? 'PUT' : 'DELETE' }); c.works = on ? [w.id, ...c.works] : c.works.filter(x => x !== w.id); cache.lists = null; } catch (x) { e.target.checked = !on; toast(x.message); }
  } }), c.name)), ...(lists.some(c => c.kind === 'list') ? [] : [el('p', { class: 'muted' }, 'No collections yet. Name one below.')]));
  const name = el('input', { type: 'text', maxlength: '60', placeholder: 'A new collection', 'aria-label': 'Name of a new collection' });
  const add = async e => { e.preventDefault(); if (!name.value.trim()) return name.focus(); try { const c = await api('/api/me/collections', { method: 'POST', body: { name: name.value } }); await api(`/api/me/collections/${c.id}/works/${w.id}`, { method: 'PUT' }); c.works = [w.id]; lists.push(c); cache.lists = null; name.value = ''; draw(); } catch (x) { toast(x.message); } };
  const done = () => { dlg.close(); dlg.remove(); onClose(); };
  draw();
  dlg.append(el('h2', {}, 'Collections'), el('p', { class: 'muted' }, w.title), rowsEl, el('form', { class: 'inline', style: 'margin-top:14px', onsubmit: add }, name, el('button', { class: 'ghost', type: 'submit' }, 'Add')),
    el('div', { class: 'acts' }, el('button', { class: 'primary', type: 'button', style: 'padding:9px 18px', onclick: done }, 'Done')));
  dlg.addEventListener('cancel', e => { e.preventDefault(); done(); });
  document.body.append(dlg); dlg.showModal();
}

const KINDS = { both: 'Ebook and audiobook', ebook: 'Ebook', audiobook: 'Audiobook' };
// Looking a book up before asking for it: what Audible and Open Library say (by way of the server), and for each
// answer what the server already has of it. The last lookup is kept so a book's page and Back need no second search.
const lookup = { words: '', found: [], errors: [] };
// A cover the lookup offered comes through the server, which wants the sign-in in a header: fetched, then shown.
const lookupCover = url => {
  const img = el('img', { class: 'cover', alt: '' });
  if (url) fetch(`/api/lookup/image?url=${encodeURIComponent(url)}`, { headers: { Authorization: 'Bearer ' + store.token, 'X-Device': deviceId() } })
    .then(r => r.ok ? r.blob() : null).then(b => { if (b) img.src = URL.createObjectURL(b); }).catch(() => {});
  return img;
};
const haveLine = f => f.work ? `On your server · ${f.hasEbook && f.hasAudio ? 'ebook and audiobook' : f.hasEbook ? 'ebook only' : 'audiobook only'}` : f.wanted ? 'Not on your server · already on the wanted list' : 'Not on your server';
async function lookupFor(words) {
  if (words !== lookup.words || !lookup.found.length) { const r = await api(`/api/lookup?q=${encodeURIComponent(words)}`); lookup.words = words; lookup.found = r.found; lookup.errors = r.errors; }
  return lookup;
}
// One book the lookup found: everything the catalogue says about it, what the server has, and the way to ask for it.
async function foundPage(index) {
  setTab('journal');
  const f = lookup.found[index]; if (!f) { location.hash = '#/wanted'; return; }
  const b = f.book; const whole = f.work && f.hasEbook && f.hasAudio;
  const kind = el('select', { 'aria-label': 'Which form to ask for' }, Object.entries(KINDS).map(([k, v]) => el('option', { value: k }, v)));
  // What to ask for: both by default; when the server has one form already, the other.
  kind.value = f.work && f.hasEbook && !f.hasAudio ? 'audiobook' : f.work && f.hasAudio && !f.hasEbook ? 'ebook' : 'both';
  const ask = el('button', { class: 'primary', disabled: f.wanted ? '' : null, onclick: async () => {
    ask.disabled = true;
    try { const made = await api('/api/requests', { method: 'POST', body: { title: b.title, author: b.authors[0] || '', kind: kind.value, note: '' } }); toast(made.status === 'arrived' ? 'The library already has it' : `${made.title} is on the wanted list`); lookup.words = ''; lookup.found = []; location.hash = '#/wanted'; }
    catch (x) { ask.disabled = false; toast(x.message); }
  } }, f.wanted ? 'Already wanted' : 'Request');
  show(el('a', { class: 'muted', href: `#/wanted/ask/${encodeURIComponent(lookup.words)}` }, '‹ Results'),
    el('div', { class: 'hero', style: 'margin-top:14px' }, lookupCover(b.cover),
      el('div', {},
        el('h1', {}, b.title), b.subtitle ? el('div', { class: 'meta' }, b.subtitle) : null,
        b.authors.length ? el('div', { class: 'meta', style: 'font-size:16px' }, b.authors.join(', ')) : null,
        b.narrators.length ? el('div', { class: 'meta' }, 'Read by ' + b.narrators.join(', ')) : null,
        b.series.length ? el('div', { class: 'meta' }, 'Series: ' + b.series.map(s => s.name + (s.sequence ? ` #${+s.sequence}` : '')).join(', ')) : null,
        el('div', { class: 'meta' }, [b.publishedYear, b.publisher, 'from ' + b.source].filter(Boolean).join('  ·  ')),
        el('div', { class: 'meta' }, el('span', { class: f.work ? 'good' : 'muted' }, f.work ? el('span', { class: 'dot' }) : null, haveLine(f))),
        b.genres.length ? el('div', { class: 'meta' }, b.genres.join(' · ')) : null,
        el('div', { class: 'actions' },
          f.work ? el('a', { class: whole ? 'primary' : 'outline', href: `#/book/${f.work}` }, 'Open it in your library') : null,
          whole ? null : [kind, ask]))),
    b.description ? el('div', { class: 'desc' }, el('div', { class: 'caption' }, 'About'), b.description.split(/\n\n+/).map(p => el('p', { style: 'margin-top:8px' }, p))) : null);
}

// Told once, the next time a page is opened: a book this person asked for is in the library now. The first look only
// notes what had arrived before, so an old list is not announced all over again.
async function announceArrivals() {
  if (announceArrivals.at && Date.now() - announceArrivals.at < 60000) return;
  announceArrivals.at = Date.now();
  try {
    const me = store.user || (await api('/api/me')).user; const key = `unison.arrivals.${me.name}`;
    const mine = (await api('/api/requests')).requests.filter(q => q.mine && q.status === 'arrived');
    const seen = JSON.parse(localStorage.getItem(key) || 'null');
    localStorage.setItem(key, JSON.stringify(mine.map(q => q.id)));
    const fresh = seen ? mine.filter(q => !seen.includes(q.id)) : [];
    if (fresh.length === 1) notice(`${fresh[0].title} is in the library now`, fresh[0].work ? `#/book/${fresh[0].work}` : '#/wanted', 'Open');
    else if (fresh.length) notice(`${fresh.length} books you asked for are in the library now`, '#/wanted', 'See');
  } catch {}
}

// The wanted list: books the library does not have yet. Anyone asks; an admin settles; the scanner notices arrivals.
async function wantedView(prefill) {
  setTab('journal');
  const me = store.user || (await api('/api/me')).user; const isAdmin = me.role === 'admin';
  const [r, all] = await Promise.all([api('/api/requests'), works()]);
  const byId = new Map(all.map(w => [w.id, w]));
  const title = el('input', { type: 'text', maxlength: '200', value: prefill || '', placeholder: 'Title', 'aria-label': 'Title' }), author = el('input', { type: 'text', maxlength: '120', placeholder: 'Author', 'aria-label': 'Author' });
  const kind = el('select', { 'aria-label': 'Which form' }, Object.entries(KINDS).map(([k, v]) => el('option', { value: k }, v)));
  const note = el('input', { type: 'text', maxlength: '500', placeholder: 'A note (optional)', 'aria-label': 'A note' });
  const submit = async e => {
    e.preventDefault(); if (!title.value.trim()) return title.focus();
    try { const made = await api('/api/requests', { method: 'POST', body: { title: title.value, author: author.value, kind: kind.value, note: note.value } }); toast(made.status === 'arrived' ? 'The library already has it' : r.requests.some(x => x.id === made.id) ? 'Already on the list' : 'Asked for'); if (prefill) history.replaceState(null, '', '#/wanted'); wantedView(); } catch (x) { toast(x.message); }
  };
  const act = (label, fn, cls = 'ghost') => el('button', { class: cls, onclick: async () => { try { await fn(); wantedView(); } catch (x) { toast(x.message); } } }, label);
  const settle = (q, status) => api(`/api/requests/${q.id}`, { method: 'PUT', body: { status } });
  const row = q => {
    const w = q.work ? byId.get(q.work) : null;
    return el('div', { class: 'row' }, w ? el('a', { href: `#/book/${w.id}` }, coverOf(w)) : el('div', { class: 'cover' }),
      el('div', { class: 'grow' }, el('div', { class: 't' }, w ? el('a', { href: `#/book/${w.id}` }, q.title) : q.title, q.author ? el('span', { class: 'muted', style: 'font-weight:400' }, '  ·  ' + q.author) : null),
        el('div', { class: 's' }, [KINDS[q.kind], q.requester ? `asked by ${q.requester}` : null, q.source === 'discord' ? 'on Discord' : null, fmtWhen(q.created)].filter(Boolean).join(' · ')),
        q.note ? el('div', { class: 's' }, q.note) : null),
      el('div', { class: 'acts' },
        isAdmin && q.status === 'wanted' ? [act('Arrived', () => settle(q, 'arrived')), act('Decline', () => settle(q, 'declined'))] : null,
        isAdmin && q.status !== 'wanted' ? act('Want again', () => settle(q, 'wanted')) : null,
        q.mine || isAdmin ? act('Remove', () => api(`/api/requests/${q.id}`, { method: 'DELETE' }), 'ghost danger') : null));
  };
  const group = (label, status) => { const list = r.requests.filter(q => q.status === status); return list.length || status === 'wanted' ? [el('div', { class: 'section' }, el('h2', {}, label), el('span', { class: 'muted' }, list.length)), list.length ? el('div', { class: 'list wanted' }, list.map(row)) : el('p', { class: 'muted' }, 'Nothing is waiting.')] : null; };
  const words = el('input', { type: 'search', maxlength: '200', value: prefill || '', placeholder: 'Title, or title and author', 'aria-label': 'Look up a book', style: 'min-width:320px' });
  const results = el('div');
  const byTitle = el('details', { style: 'margin-top:14px' }, el('summary', { class: 'muted', style: 'cursor:pointer' }, 'Not there? Ask by title'),
    el('form', { class: 'inline ask', style: 'margin-top:12px', onsubmit: submit }, title, author, kind, note, el('button', { class: 'primary', type: 'submit', style: 'padding:9px 18px' }, 'Ask for it')));
  // Words in the address are looked up; each answer says what the server has and opens the book's page.
  if (prefill) {
    results.replaceChildren(el('p', { class: 'muted' }, 'Looking it up…'));
    lookupFor(prefill).then(l => {
      if (!results.isConnected) return;
      results.replaceChildren(
        l.found.length ? el('div', { class: 'section' }, el('h2', {}, 'Found'), el('span', { class: 'muted' }, l.found.length)) : el('p', { class: 'muted' }, l.errors.length ? `The lookup could not be reached (${l.errors.join('; ')}). You can still ask by title below.` : `Nothing found for “${prefill}”. Try fewer words, or ask by title below.`),
        l.found.length ? el('div', { class: 'list' }, l.found.map((f, i) => el('a', { class: 'row', href: `#/wanted/found/${i}` }, lookupCover(f.book.cover),
          el('div', { class: 'grow' }, el('div', { class: 't' }, f.book.title),
            el('div', { class: 's' }, [f.book.authors.join(', ') || 'Unknown author', f.book.series[0] ? f.book.series[0].name + (f.book.series[0].sequence ? ` #${+f.book.series[0].sequence}` : '') : null].filter(Boolean).join('  ·  ')),
            el('div', { class: 's' }, el('span', { class: f.work ? 'good' : '' }, haveLine(f)), `  ·  ${[f.book.publishedYear, 'from ' + f.book.source].filter(Boolean).join(' · ')}`))))) : document.createDocumentFragment());
      if (!l.found.length) byTitle.open = true;
    }).catch(x => { if (results.isConnected) { results.replaceChildren(el('p', { class: 'err' }, x.message)); byTitle.open = true; } });
  }
  show(el('a', { class: 'muted', href: '#/lists' }, '‹ Your lists'), el('h1', { style: 'margin-top:10px' }, 'The wanted list'),
    el('p', { class: 'muted', style: 'margin:6px 0 0' }, 'Books the library does not have yet. Look a book up, then ask for the one you mean.'),
    el('form', { class: 'inline ask', style: 'margin-top:18px', onsubmit: e => { e.preventDefault(); const t = words.value.trim(); if (t.length < 2) return words.focus(); if (location.hash === `#/wanted/ask/${encodeURIComponent(t)}`) wantedView(t); else location.hash = `#/wanted/ask/${encodeURIComponent(t)}`; } }, words, el('button', { class: 'primary', type: 'submit', style: 'padding:9px 18px' }, 'Look it up')),
    results, byTitle,
    group('Still wanted', 'wanted'), group('Arrived', 'arrived'), group('Declined', 'declined'));
}

// The metadata overlay (admins): corrections kept in Unison's catalogue, over what Calibre and the audiobook folders say.
async function metaView(id) {
  setTab(null);
  const me = store.user || (await api('/api/me')).user;
  if (me.role !== 'admin') { location.hash = `#/book/${id}`; return; }
  const v = await api(`/api/works/${id}/meta`); const w = norm(v.current), src = v.source;
  const seriesText = l => l.map(s => s.name + (s.sequence !== null && s.sequence !== undefined ? ` #${+s.sequence}` : '')).join('\n');
  const parseSeries = t => lines(t).map(x => { const m = x.match(/^(.*?)\s*#\s*(\d+(?:\.\d+)?)$/); return m ? { name: m[1].trim(), sequence: +m[2] } : { name: x, sequence: null }; });
  const lines = t => t.split('\n').map(x => x.trim()).filter(Boolean);
  const text = (value, more = {}) => el('input', Object.assign({ type: 'text', value: value || '' }, more));
  const area = (value, rows) => { const t = el('textarea', { rows }); t.value = value || ''; return t; };
  const F = { title: text(w.title), subtitle: text(w.subtitle), authors: area(w.authors.join('\n'), 2), narrators: area(w.narrators.join('\n'), 2), series: area(seriesText(w.series), 2),
    publishedYear: text(w.publishedYear, { maxlength: '4', inputmode: 'numeric' }), publisher: text(w.publisher), language: text(w.language), genres: text(w.genres.join(', ')), description: area(w.description, 8) };
  const said = { title: src.title, subtitle: src.subtitle, authors: src.authors.join(', '), narrators: src.narrators.join(', '), series: seriesText(src.series).replace(/\n/g, ', '), publishedYear: src.publishedYear, publisher: src.publisher, language: src.language, genres: src.genres.join(', '), description: src.description.length > 140 ? src.description.slice(0, 140) + '…' : src.description };
  const forget = () => { cache.works = null; facets.clear(); cache.details.delete(id); };
  const reset = async key => { try { await api(`/api/works/${id}/meta`, { method: 'PUT', body: { reset: [key] } }); forget(); toast('Back to what the library says'); metaView(id); } catch (x) { toast(x.message); } };
  const field = (label, key, hint) => el('label', { class: 'field' }, label, hint ? el('span', { class: 'hint' }, ' · ' + hint) : null, F[key],
    w.edited.includes(key) ? el('span', { class: 'was' }, `The library says: ${said[key] || 'nothing'}  `, el('button', { type: 'button', class: 'link', onclick: () => reset(key) }, 'Put that back')) : null);
  let cover = null;
  const coverBox = el('div', { class: 'coverBox' }, coverOf(w));
  const image = async url => URL.createObjectURL(await (await api(`/api/works/${id}/meta/image?url=${encodeURIComponent(url)}`)).blob());
  const save = async e => {
    e.preventDefault();
    const body = { title: F.title.value, subtitle: F.subtitle.value, authors: lines(F.authors.value), narrators: lines(F.narrators.value), series: parseSeries(F.series.value), publishedYear: F.publishedYear.value, publisher: F.publisher.value, language: F.language.value,
      genres: F.genres.value.split(',').map(x => x.trim()).filter(Boolean), description: F.description.value };
    if (cover) body.cover = cover;
    try { await api(`/api/works/${id}/meta`, { method: 'PUT', body }); forget(); toast('Saved'); location.hash = `#/book/${id}`; } catch (x) { toast(x.message); }
  };
  // Looking the book up outside the house: Audible and Open Library, by the title and author typed here.
  const lt = text(w.title, { 'aria-label': 'Title to look up', placeholder: 'Title' }), la = text(w.author, { 'aria-label': 'Author to look up', placeholder: 'Author' });
  const results = el('div', { class: 'list' });
  const fill = c => {
    const put = (key, value) => { if (value) F[key].value = value; };
    put('title', c.title); put('subtitle', c.subtitle); put('authors', c.authors.join('\n')); put('narrators', c.narrators.join('\n')); put('series', seriesText(c.series)); put('publishedYear', c.publishedYear); put('publisher', c.publisher); put('language', c.language); put('genres', c.genres.join(', ')); put('description', c.description);
    toast('Filled in. Check it, then save.'); window.scrollTo(0, 0);
  };
  const useCover = async c => { try { const url = await image(c.cover); cover = c.cover; coverBox.replaceChildren(el('img', { class: 'cover', src: url, alt: '' }), el('span', { class: 'was' }, 'New cover, kept when you save')); toast('Cover chosen. Save to keep it.'); window.scrollTo(0, 0); } catch (x) { toast(x.message); } };
  const candidate = c => {
    const thumb = el('div', { class: 'cover' });
    if (c.cover) image(c.cover).then(url => thumb.replaceWith(el('img', { class: 'cover', src: url, alt: '' }))).catch(() => {});
    return el('div', { class: 'row' }, thumb,
      el('div', { class: 'grow' }, el('div', { class: 't' }, c.title, c.subtitle ? el('span', { class: 'muted', style: 'font-weight:400' }, '  ·  ' + c.subtitle) : null),
        el('div', { class: 's' }, [c.source, c.authors.join(', '), c.narrators.length ? 'read by ' + c.narrators.join(', ') : null, seriesText(c.series).replace(/\n/g, ', ') || null, c.publishedYear || null].filter(Boolean).join(' · '))),
      el('div', { class: 'acts' }, el('button', { class: 'ghost', type: 'button', onclick: () => fill(c) }, 'Use details'), c.cover ? el('button', { class: 'ghost', type: 'button', onclick: () => useCover(c) }, 'Use cover') : null));
  };
  const look = async e => {
    e.preventDefault(); results.replaceChildren(el('p', { class: 'muted' }, 'Looking…'));
    try { const r = await api(`/api/works/${id}/meta/lookup?title=${encodeURIComponent(lt.value)}&author=${encodeURIComponent(la.value)}`); results.replaceChildren(...(r.candidates.length ? r.candidates.map(candidate) : [el('p', { class: 'muted' }, 'Nothing found.')]), ...r.errors.map(x => el('p', { class: 'err' }, x))); }
    catch (x) { results.replaceChildren(el('p', { class: 'err' }, x.message)); }
  };
  show(el('a', { class: 'muted', href: `#/book/${id}` }, `‹ ${w.title}`), el('h1', { style: 'margin-top:10px' }, 'Edit details'),
    el('p', { class: 'muted', style: 'margin:6px 0 0;max-width:640px' }, 'Corrections are kept in Unison. Calibre and the audiobook folders are not changed.'),
    el('div', { class: 'metaEdit' },
      el('form', { onsubmit: save },
        field('Title', 'title'), field('Subtitle', 'subtitle'), field('Authors', 'authors', 'one per line'), field('Narrators', 'narrators', 'one per line'), field('Series', 'series', 'one per line, like Harbour #2'),
        el('div', { class: 'three' }, field('Year', 'publishedYear'), field('Language', 'language'), field('Publisher', 'publisher')), field('Genres', 'genres', 'separated by commas'), field('About', 'description'),
        el('div', { class: 'actions' }, el('button', { class: 'primary', type: 'submit' }, 'Save'), el('a', { class: 'ghost', href: `#/book/${id}` }, 'Cancel'))),
      el('div', {}, coverBox, w.edited.includes('cover') ? el('span', { class: 'was' }, 'A cover chosen here.  ', el('button', { type: 'button', class: 'link', onclick: () => reset('cover') }, 'Put the library’s back')) : null,
        el('div', { class: 'box' }, el('span', { class: 'caption' }, 'Look it up'), el('p', { class: 'muted', style: 'margin:0 0 10px;font-size:14px' }, 'Audible and Open Library are asked by title and author. Nothing is saved until you choose.'),
          el('form', { class: 'inline', onsubmit: look }, lt, la, el('button', { class: 'ghost', type: 'submit' }, 'Look up')), results))));
}

// ---------------------------------------------------------------- The name button: a link to Account, or for an admin a small menu
function drawWho() {
  const u = store.user; const name = u?.name || '…'; const admin = u?.role === 'admin';
  for (const b of [$('#who'), $('#whoBtn')]) { $('span', b).textContent = name; $('i', b).textContent = name[0].toUpperCase(); }
  $('#who').hidden = admin; $('#whoBtn').hidden = !admin;
  if (!admin) closeWhoMenu();
}
function closeWhoMenu(focusButton) { const m = $('#whoMenu'); if (m.hidden) return; m.hidden = true; $('#whoBtn').setAttribute('aria-expanded', 'false'); if (focusButton) $('#whoBtn').focus(); }
function openWhoMenu() { $('#whoMenu').hidden = false; $('#whoBtn').setAttribute('aria-expanded', 'true'); $('#whoMenu a').focus(); }
$('#whoBtn').onclick = () => $('#whoMenu').hidden ? openWhoMenu() : closeWhoMenu(true);
$('#whoMenu').addEventListener('click', () => closeWhoMenu());
$('#whoMenu').addEventListener('keydown', e => {
  const items = [...document.querySelectorAll('#whoMenu a')]; const i = items.indexOf(document.activeElement);
  if (e.key === 'ArrowDown') { e.preventDefault(); items[(i + 1) % items.length].focus(); }
  else if (e.key === 'ArrowUp') { e.preventDefault(); items[(i + items.length - 1) % items.length].focus(); }
  else if (e.key === 'Tab') closeWhoMenu();
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#whoMenu').hidden) closeWhoMenu(true); });
document.addEventListener('click', e => { if (!$('#whoBox').contains(e.target)) closeWhoMenu(); });

// ---------------------------------------------------------------- Routing
async function route() {
  if (!store.token) return signIn();
  // Browsers signed in before the cookie existed get one now, so covers and audio keep loading.
  if (!localStorage.getItem('unison.cookie')) { try { await api('/api/me/cookie', { method: 'POST' }); localStorage.setItem('unison.cookie', '1'); } catch {} }
  $('#top').hidden = false;
  drawWho();
  if (!store.user) api('/api/me').then(m => { store.user = m.user; drawWho(); }).catch(() => {});
  closeWhoMenu();
  const parts = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  document.body.classList.toggle('reading', parts[0] === 'read');
  if (parts[0] !== 'read') announceArrivals();
  if (parts[0] !== 'read' && reader.module) reader.module.closeIfOpen();
  if (pageTeardown) { pageTeardown(); pageTeardown = null; }
  try {
    switch (parts[0]) {
      case 'ebooks': case 'audiobooks': return await sectionRoute(parts[0], parts.slice(1));
      case 'search': return await searchView(parts[1] || '');
      case 'journal': return location.replace('#/lists');
      // Addresses from before the sections.
      case 'books': return location.replace('#/ebooks');
      case 'authors': return location.replace('#/ebooks/authors');
      case 'narrators': return location.replace('#/audiobooks/narrators');
      case 'narrator': return location.replace(secHref('audiobooks', 'narrator', parts[1] || ''));
      case 'series': return parts[1] ? await legacyDetail('series', parts[1]) : location.replace('#/ebooks/series');
      case 'author': return parts[1] ? await legacyDetail('author', parts[1]) : location.replace('#/ebooks/authors');
      case 'book': return parts[2] === 'edit' ? await metaView(parts[1]) : await bookPage(parts[1]);
      case 'lists': return parts[1] ? await listPage(parts[1]) : await listsView();
      case 'wanted': return parts[1] === 'found' ? await foundPage(+parts[2]) : await wantedView(parts[1] === 'ask' ? parts[2] : '');
      case 'listen': return await listenView(parts[1], parts[2] === 'play');
      case 'read': return await openReader(parts[1], parts[2]);
      case 'highlights': return await highlightsView();
      case 'stats': return parts[1] === 'year' ? await yearView(parts[2]) : await statsView();
      case 'account': return await accountView();
      case 'admin': return parts[1] === 'pairing' ? await pairingView() : await adminView();
      default: return await shelfView();
    }
  } catch (x) { show(el('p', { class: 'err' }, x.message)); }
}
window.addEventListener('hashchange', route);
$('#searchForm').onsubmit = e => { e.preventDefault(); const q = $('#search').value.trim(); location.hash = q ? `#/search/${encodeURIComponent(q)}` : `#/${lastSection}`; };
route();
})();
