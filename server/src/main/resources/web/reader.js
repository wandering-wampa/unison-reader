/* The web reader — a quiet paper page. foliate-js renders the EPUB; the server's sentence list is
   found in each section by its text, so the server's alignment can light the sentence being narrated,
   a double-click on a sentence can start the audio there, and bookmarks and highlights are kept by
   sentence (shared with the app through the server). The bottom row is the app's: Listen here or Pause,
   the time and a parity phrase ("In sync", "Audio a chapter ahead"), Follow only while the narration
   is elsewhere. Position (sentence + audio) goes to the server as in the app; reading time is counted. */
import '/foliate/view.js';

let current = null;

const normalized = s => s.replace(/\s+/g, ' ').trim();
const SIZES = { min: 14, max: 40 };
const THEMES = {
  // Opaque colours: the bands of neighbouring lines overlap, and a translucent colour doubles up where they do.
  paper: { bg: '#F8F5ED', fg: '#2B2D25', hl: '#B3E3E0', mk: '#F3E39A', fd: '#F5CFB4', label: 'Paper' },
  white: { bg: '#FFFFFF', fg: '#1A1A1A', hl: '#B8EBED', mk: '#F7E9A3', fd: '#F8D3BB', label: 'White' },
  sepia: { bg: '#F2E6CF', fg: '#3B2F1E', hl: '#BEC4BA', mk: '#E6D28A', fd: '#E9BB95', label: 'Sepia' },
  night: { bg: '#1C1F1D', fg: '#E1DDD2', hl: '#125657', mk: '#5A4F1E', fd: '#6A4028', label: 'Night' },
};
// Search compares folded text: lower case, plain quotes and dashes, no accents; one character for one, so offsets carry over.
const fold = s => { let out = ''; for (const c of s) out += c.length > 1 ? c : '‘’ʼ'.includes(c) ? "'" : '“”'.includes(c) ? '"' : '–—'.includes(c) ? '-' : /\s/.test(c) ? ' ' : (c.normalize('NFD')[0] || c).toLowerCase().charAt(0) || c; return out; };
const prefs = {
  get size() { return Number(localStorage.getItem('unison.read.size') || 20); }, set size(v) { localStorage.setItem('unison.read.size', v); },
  get theme() { return localStorage.getItem('unison.read.theme') || 'paper'; }, set theme(v) { localStorage.setItem('unison.read.theme', v); },
  get font() { return localStorage.getItem('unison.read.font') || 'literata'; }, set font(v) { localStorage.setItem('unison.read.font', v); },
  get flow() { return localStorage.getItem('unison.read.flow') || 'paginated'; }, set flow(v) { localStorage.setItem('unison.read.flow', v); },
  // Two columns on a wide desktop window unless chosen otherwise; one column below that.
  get columns() { return localStorage.getItem('unison.read.columns') || (innerWidth >= 1100 ? '2' : '1'); }, set columns(v) { localStorage.setItem('unison.read.columns', v); },
};
const ICONS = {
  back: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>',
  search: '<svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/></svg>',
  toc: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/></svg>',
  bookmark: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M6 3h12v18l-6-4-6 4z"/></svg>',
  bookmarked: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M6 3h12v18l-6-4-6 4z"/></svg>',
  marks: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 3h9v17l-4.5-3L4 20z"/><path d="M16 6h5M16 10h5M16 14h5"/></svg>',
  phones: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 13a8 8 0 0 1 16 0"/><rect x="3" y="13" width="4" height="7" rx="1.5"/><rect x="17" y="13" width="4" height="7" rx="1.5"/></svg>',
  pause: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>',
  player: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 13a8 8 0 0 1 16 0"/><rect x="3" y="13" width="4" height="7" rx="1.5"/><rect x="17" y="13" width="4" height="7" rx="1.5"/></svg>',
};

export function closeIfOpen() { if (current) { current.teardown(); current = null; } }
export function beacon() { current?.beacon?.(); }

export async function open(ctx) {
  closeIfOpen();
  const { el, api, detail, player, onPassageForAudio, sessions, places } = ctx;
  places?.known.set(detail.work.id, detail.position?.updated || 0);
  const work = detail.work;
  const fmt = ms => { const s = Math.round(ms / 1000); return `${Math.floor(s / 3600)}:${String(Math.floor(s % 3600 / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`; };
  const fmtDate = t => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  const ic = (name, label, onclick) => el('button', { class: 'ic', 'aria-label': label, title: label, html: ICONS[name] || name, onclick });

  // ---- Chrome: a slim top row, the page, and one row at the bottom.
  const root = el('div', { class: 'reader' });
  const title = el('div', { class: 't' }, work.title);
  const tocBtn = ic('toc', 'Contents');
  const bmBtn = ic('bookmark', 'Bookmark this page');
  const marksBtn = ic('marks', 'Bookmarks & highlights');
  const settingsBtn = ic('A', 'Appearance');
  const searchBtn = ic('search', 'Search in this book ( / )');
  const bar = el('div', { class: 'rbar' }, ic('back', 'Back to the library', () => ctx.close()), title, searchBtn, bmBtn, marksBtn, tocBtn, settingsBtn);
  const view = document.createElement('foliate-view');
  const chapterLabel = el('div', { class: 'rchapter' }, '');
  const body = el('div', { class: 'rbody' }, view, chapterLabel, el('div', { class: 'rnav prev', onclick: () => turn(-1) }), el('div', { class: 'rnav next', onclick: () => turn(1) }));
  const listen = work.hasAudio ? el('button', { class: 'main', title: 'Narration (space)' }) : null;
  const setListen = playing => { if (!listen) return; listen.classList.toggle('on', playing); listen.replaceChildren(el('span', { html: playing ? ICONS.pause : ICONS.phones, style: 'display:inline-flex' }), playing ? 'Pause' : 'Listen here'); };
  setListen(false);
  const parity = el('span', { class: 'parity', hidden: '' }, el('span', { class: 'dot' }), el('span', {}, ''));
  const follow = el('button', { class: 'mini follow', hidden: '' }, 'Follow');
  const where = el('span', { class: 'where' }, 'Opening…');
  const bar2 = el('div', { class: 'bar' }, el('i', { style: 'width:0' }));
  const pct = el('span', { class: 'pct' }, '');
  const speed = work.hasAudio ? el('button', { class: 'mini', hidden: '', onclick: e => { player.cycleSpeed(); e.target.textContent = speedText(); } }, '') : null;
  const back30 = work.hasAudio ? el('button', { class: 'mini', hidden: '', onclick: () => player.seekTo(player.globalMs() - 30000) }, '−30') : null;
  const fwd30 = work.hasAudio ? el('button', { class: 'mini', hidden: '', onclick: () => player.seekTo(player.globalMs() + 30000) }, '+30') : null;
  const toPlayer = work.hasAudio ? el('a', { class: 'ic', href: `#/listen/${work.id}`, 'aria-label': 'Open the player', title: 'Open the player', html: ICONS.player }) : null;
  const pace = el('span', { class: 'pace', title: 'At your reading pace, or at the narration speed while listening' }, '');
  const foot = el('div', { class: 'rfoot' }, listen || '', parity, follow, where, pace, el('span', { class: 'grow' }), speed || '', back30 || '', fwd30 || '', bar2, pct, toPlayer || '');
  root.append(bar, body, foot);
  document.querySelector('#view').replaceChildren(root);
  document.body.classList.add('reading');
  function speedText() { return (Number.isInteger(player.speed) ? player.speed + '.0' : String(player.speed)) + '×'; }

  const state = { passages: [], toc: [], anchors: [], byPassage: new Map(), maps: new WeakMap(), marks: [], passage: detail.position?.passage || 0, lastPushed: '', highlighted: -1, unsub: null, timer: null, destroyed: false, following: false, navigating: 0, busy: false, chapterLabel: '', visible: null, wasPlaying: false, lastFirst: -1, found: -1, query: '', folded: null };
  const narrating = () => player.work?.id === work.id;
  const audioNow = () => narrating() ? player.globalMs() : (detail.position?.audio || 0);
  current = {
    teardown() { state.destroyed = true; document.removeEventListener('visibilitychange', checkElsewhere); window.removeEventListener('online', retryPush); state.unsub?.(); clearInterval(state.timer); push(false); if (sessions.open?.kind === 'read') sessions.end(); onPassageForAudio(null); document.body.classList.remove('reading'); try { view.close?.(); } catch {} },
    beacon() { if (`${state.passage}:${audioNow()}` !== state.lastPushed) ctx.beacon(`/api/works/${work.id}/position`, { passage: state.passage, audio: audioNow() }, narrating() && player.isPlaying() ? undefined : places?.known.get(work.id)); },
  };
  // Reading time counts while the page is open and the narrator is quiet.
  if (!(narrating() && player.isPlaying())) sessions.start(work.id, 'read');

  // ---- Style the pages: size, type and paper as CSS on each section document.
  function applyStyle() {
    const t = THEMES[prefs.theme] || THEMES.paper;
    root.style.setProperty('--rbg', t.bg); root.style.setProperty('--rfg', t.fg);
    const font = prefs.font === 'literata' ? `font-family: Literata, Georgia, serif !important;` : '';
    view.renderer?.setStyles?.(`
      @import url("https://fonts.googleapis.com/css2?family=Literata:ital,opsz,wght@0,7..72,400;0,7..72,500;0,7..72,600;0,7..72,700;1,7..72,400;1,7..72,500&display=swap");
      @namespace epub "http://www.idpf.org/2007/ops";
      html { font-size: ${prefs.size}px !important; color: ${t.fg} !important; background: ${t.bg} !important; }
      body { color: ${t.fg} !important; background: transparent !important; line-height: 1.55; ${font} }
      p, li, blockquote, div, h1, h2, h3, h4 { ${font} }
      p { line-height: inherit; }
      a { color: inherit; }
      img { max-width: 100%; height: auto; }
      ::highlight(narration) { background: ${t.hl}; }
      ::highlight(marks) { background: ${t.mk}; }
      ::highlight(found) { background: ${t.fd}; }
    `);
    view.renderer?.setAttribute('max-column-count', prefs.columns);
    view.renderer?.setAttribute('max-inline-size', prefs.columns === '2' ? '640px' : '720px');
    root.classList.toggle('scrolled', prefs.flow === 'scrolled');
    fitNav();
  }
  // The page-turn zones take the whole margin on each side of the text, never less than a thumb's width.
  function fitNav() {
    const r = view.renderer; if (!r) return;
    const cols = Number(r.getAttribute('max-column-count') || 1), max = parseFloat(r.getAttribute('max-inline-size') || 720) * cols;
    const side = Math.max(56, Math.round((body.clientWidth - Math.min(body.clientWidth, max)) / 2));
    for (const n of body.querySelectorAll('.rnav')) n.style.width = side + 'px';
  }
  window.addEventListener('resize', fitNav);
  const closeMenu = () => root.querySelector('.rmenu')?.remove();
  const menu = (kind, ...content) => { const old = root.querySelector('.rmenu'); if (old) { old.remove(); if (old.dataset.kind === kind) return null; } const m = el('div', { class: 'rmenu' }, ...content); m.dataset.kind = kind; root.append(m); return m; };
  settingsBtn.onclick = () => {
    const size = el('input', { type: 'range', min: SIZES.min, max: SIZES.max, value: prefs.size, oninput: e => { prefs.size = Number(e.target.value); applyStyle(); } });
    const themes = el('div', { class: 'themes' }, Object.entries(THEMES).map(([k, t]) => el('button', { title: t.label, 'aria-label': t.label, class: prefs.theme === k ? 'on' : '', style: `background:${t.bg}`, onclick: e => { prefs.theme = k; applyStyle(); for (const b of themes.children) b.classList.toggle('on', b === e.currentTarget); } })));
    const font = el('button', { class: 'ghost', onclick: e => { prefs.font = prefs.font === 'literata' ? 'publisher' : 'literata'; applyStyle(); e.target.textContent = prefs.font === 'literata' ? 'Literata' : 'Publisher’s'; } }, prefs.font === 'literata' ? 'Literata' : 'Publisher’s');
    const flow = el('button', { class: 'ghost', onclick: e => { prefs.flow = prefs.flow === 'scrolled' ? 'paginated' : 'scrolled'; view.renderer.setAttribute('flow', prefs.flow); applyStyle(); e.target.textContent = prefs.flow === 'scrolled' ? 'Scroll' : 'Pages'; } }, prefs.flow === 'scrolled' ? 'Scroll' : 'Pages');
    const cols = el('button', { class: 'ghost', onclick: e => { prefs.columns = prefs.columns === '2' ? '1' : '2'; applyStyle(); e.target.textContent = prefs.columns === '2' ? 'Two' : 'One'; } }, prefs.columns === '2' ? 'Two' : 'One');
    menu('settings', el('label', {}, 'Text size', size), el('label', {}, 'Paper', themes), el('label', {}, 'Type', font), el('label', {}, 'Flow', flow), el('label', {}, 'Columns', cols));
  };
  tocBtn.onclick = () => {
    const items = []; const walk = (list, depth) => { for (const t of list || []) { items.push(el('a', { href: '#', class: depth ? 'sub' : '', onclick: e => { e.preventDefault(); closeMenu(); navigate(() => view.goTo(t.href)); } }, t.label?.trim() || '…')); walk(t.subitems, depth + 1); } };
    walk(view.book?.toc, 0);
    if (!items.length) for (const t of state.toc) items.push(el('a', { href: '#', onclick: e => { e.preventDefault(); closeMenu(); navigate(() => goTo(t.passage)); } }, t.title));
    for (const a of items) if (state.chapterLabel && a.textContent === state.chapterLabel) a.classList.add('on');
    const m = menu('toc', el('div', { class: 'caption', style: 'margin-bottom:4px' }, 'Contents'), el('div', { class: 'toc' }, items));
    m?.querySelector('.toc a.on')?.scrollIntoView({ block: 'center' });
  };

  // ---- The book, its sentences, and my marks on it.
  const headers = { Authorization: 'Bearer ' + ctx.token };
  const [epub, stored, anchors, marks] = await Promise.all([
    fetch(`/api/works/${work.id}/ebook`, { headers }).then(r => r.blob()),
    fetch(`/api/works/${work.id}/passages`, { headers }).then(r => r.ok ? r.json() : { passages: [], toc: [] }),
    work.hasAudio ? fetch(`/api/works/${work.id}/alignment`, { headers }).then(r => r.ok ? r.json() : []) : Promise.resolve([]),
    api(`/api/me/marks/${work.id}`).catch(() => []),
  ]);
  if (state.destroyed) return;
  state.passages = stored.passages.map((p, id) => Object.assign(p, { id }));
  state.toc = stored.toc || [];
  state.anchors = anchors.sort((a, b) => a.start - b.start);
  for (const a of state.anchors) state.byPassage.set(a.passage, a);
  state.marks = marks;

  function mapDocument(doc, index) {
    if (state.maps.has(doc)) return state.maps.get(doc);
    const nodes = [], offsets = []; let text = '', space = false;
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, { acceptNode: n => n.parentElement.closest('script,style') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT });
    while (walker.nextNode()) { const node = walker.currentNode; for (let i = 0; i < node.length; i++) { const c = node.data[i]; if (/\s/.test(c)) { if (space) continue; text += ' '; space = true; } else { text += c; space = false; } nodes.push(node); offsets.push(i); } }
    const ranges = []; let cursor = 0;
    for (const p of state.passages) {
      if (p.section !== index) continue;
      const q = normalized(p.text); if (!q) continue;
      let start = text.indexOf(q, cursor); if (start < 0) start = text.indexOf(q); if (start < 0) continue;
      const end = start + q.length - 1; const range = doc.createRange();
      range.setStart(nodes[start], offsets[start]); range.setEnd(nodes[end], offsets[end] + 1);
      ranges.push({ id: p.id, range }); cursor = end + 1;
    }
    const map = { ranges, text, index }; state.maps.set(doc, map); return map;
  }
  const rangeOf = id => { const p = state.passages[id]; if (!p) return null; for (const { doc, index } of view.renderer.getContents()) if (index === p.section) return mapDocument(doc, index).ranges.find(r => r.id === id)?.range || null; return null; };
  function highlight(id) {
    for (const { doc } of view.renderer.getContents()) doc.defaultView.CSS?.highlights?.delete('narration');
    const r = rangeOf(id); if (!r) return false;
    const w = r.startContainer.ownerDocument.defaultView; if (w.CSS?.highlights) { const h = new w.Highlight(r); h.priority = 2; w.CSS.highlights.set('narration', h); } return true;
  }
  // My highlights, drawn under the narration's in every loaded section.
  function drawMarks() {
    for (const { doc, index } of view.renderer.getContents()) {
      const w = doc.defaultView; if (!w.CSS?.highlights) continue;
      const map = mapDocument(doc, index); const h = new w.Highlight(); h.priority = 1;
      for (const m of state.marks) if (m.kind === 'highlight') { const r = map.ranges.find(x => x.id === m.passage); if (r) h.add(r.range); }
      w.CSS.highlights.set('marks', h);
    }
    const v = state.visible; const has = v && state.marks.some(m => m.kind === 'bookmark' && m.passage >= v.first && m.passage <= v.last);
    bmBtn.innerHTML = has ? ICONS.bookmarked : ICONS.bookmark; bmBtn.classList.toggle('on', !!has); bmBtn.title = has ? 'Remove the bookmark on this page' : 'Bookmark this page';
  }
  // The sentence a search result opened, marked until the next page turn.
  function drawFound() {
    for (const { doc, index } of view.renderer.getContents()) {
      const w = doc.defaultView; if (!w.CSS?.highlights) continue;
      const r = state.found >= 0 ? mapDocument(doc, index).ranges.find(x => x.id === state.found) : null;
      if (r) { const h = new w.Highlight(r.range); h.priority = 3; w.CSS.highlights.set('found', h); } else w.CSS.highlights.delete('found');
    }
  }
  const visible = () => { const page = view.lastLocation?.range; if (!page) return null; const doc = page.startContainer.ownerDocument; const index = view.renderer.getContents().find(c => c.doc === doc)?.index; const ranges = mapDocument(doc, index).ranges; const first = ranges.find(r => r.range.compareBoundaryPoints(Range.START_TO_END, page) > 0); if (!first) return null; let last = first; for (const r of ranges) if (r.range.compareBoundaryPoints(Range.END_TO_START, page) < 0) last = r; return { first: first.id, last: last.id }; };
  const inView = id => { const v = visible(); return !!v && id >= v.first && id <= v.last; };
  // A section plus an anchor is a resolved target: it goes to the renderer, not through view.goTo's href resolver.
  async function goTo(id) { const p = state.passages[id]; if (!p) return; await view.renderer.goTo({ index: p.section, anchor: doc => mapDocument(doc, p.section).ranges.find(r => r.id === id)?.range || 0 }); }
  // Relocations the reader triggers never count as browsing away from the narration.
  async function navigate(fn) { state.navigating++; try { await fn(); } finally { setTimeout(() => { state.navigating = Math.max(0, state.navigating - 1); }, 60); } }
  function turn(dir) { dir > 0 ? view.next() : view.prev(); }

  // Which sentence a moment of audio belongs to, by binary search over the anchors.
  function passageForAudio(ms) {
    const a = state.anchors; if (!a.length) return state.passage;
    let lo = 0, hi = a.length - 1; while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (a[mid].start <= ms) lo = mid; else hi = mid - 1; }
    return a[lo].start <= ms ? a[lo].passage : a[0].passage;
  }
  onPassageForAudio(passageForAudio);
  const anchorFrom = id => { for (let i = id; i < Math.min(state.passages.length, id + 12); i++) { const a = state.byPassage.get(i); if (a) return a; } return null; };
  const chapterOf = id => { let n = 0; for (const t of state.toc) if (t.passage <= id) n++; return n; };
  const chapterTitleOf = id => state.toc.filter(t => t.passage <= id).at(-1)?.title || '';
  const sectionOf = id => state.passages[id]?.section;

  // An ordinary save names the server time of the place this tab last knew (`base`): when another device has saved
  // since, the server refuses it, the save is dropped and the same question as on returning to the tab is asked.
  async function push(force) {
    const key = `${state.passage}:${audioNow()}`; if (!force && key === state.lastPushed) return; const prev = state.lastPushed; state.lastPushed = key;
    const r = await ctx.savePlace(work.id, { passage: state.passage, audio: audioNow() }).catch(() => null);
    // A failed save is not a saved place: put the old value back (unless a later push set its own) so the timer, the next push, the beacon and the connection coming back try again.
    if (!r) { if (state.lastPushed === key) state.lastPushed = prev; return; }
    if (!r.conflict) { places?.known.set(work.id, r.saved.updated); return; }
    // Not asked while one question is open or the tab is hidden (coming back to it asks); the save is tried again later.
    if (!places || state.destroyed || state.checking || document.visibilityState !== 'visible') { state.lastPushed = ''; return; }
    if (state.dismissed === r.conflict.updated) return;
    state.checking = true;
    try { const c = await resolveElsewhere(r.conflict); if (c === 'kept') push(true); else if (c === 'later') state.dismissed = r.conflict.updated; } finally { state.checking = false; }
  }

  // The rules for a newer place from another device: the text never moves by itself, so a place far from this
  // one is offered (Go there / Stay here); a close one, or any while narrating here, is left as it is.
  // Resolves 'there', 'here', 'kept' or 'later' (dismissed).
  async function resolveElsewhere(remote) {
    const here = { passage: state.passage, audio: audioNow() };
    if (!places.apart(here, remote) || (narrating() && player.isPlaying())) { places.known.set(work.id, remote.updated); return 'kept'; }
    const choice = await places.ask(detail, remote, here);
    if (choice === 'there') {
      places.known.set(work.id, remote.updated);
      const p = remote.passage > 0 || !(remote.audio > 0) ? remote.passage : passageForAudio(remote.audio);
      // The audio goes with the text, or the next save would send the new sentence with the old listening time.
      // A paused player stays paused (seekTo only resumes one that was playing, and this branch is never reached while it plays).
      // A place without a listening time leaves the audio where it is.
      const at = remote.audio;
      if (at > 0) {
        if (narrating()) { await player.seekTo(at, true); player.pushed = `${work.id}:${player.globalMs()}`; }
        else detail.position = { ...(detail.position || {}), audio: at };
      }
      if (state.passages[p]) { state.passage = p; await navigate(() => goTo(p)); }
      state.lastPushed = `${state.passage}:${audioNow()}`;
    } else if (choice === 'here') { places.known.set(work.id, remote.updated); push(true); }
    return choice;
  }

  // Back to this tab after another device moved the book.
  async function checkElsewhere() {
    if (!places || state.destroyed || state.checking || document.visibilityState !== 'visible') return;
    state.checking = true;
    try {
      const remote = await api(`/api/works/${work.id}/position`).catch(() => null);
      if (!remote || remote.updated <= (places.known.get(work.id) || 0)) return;
      await resolveElsewhere(remote);
    } finally { state.checking = false; }
  }
  document.addEventListener('visibilitychange', checkElsewhere);
  // The connection came back: a save that failed while offline is tried again without the person doing anything.
  function retryPush() { if (!state.destroyed) push(false); }
  window.addEventListener('online', retryPush);
  state.timer = setInterval(() => push(false), 20000);

  // ---- Marks: bookmarks on a page's first sentence, highlights on sentences, notes on either.
  const markText = id => state.passages[id]?.text || '';
  async function setMark(m) {
    const i = state.marks.findIndex(x => x.kind === m.kind && x.passage === m.passage);
    if (i >= 0) state.marks[i] = Object.assign(state.marks[i], m); else state.marks.push(m);
    drawMarks();
    try { await api(`/api/me/marks/${work.id}`, { method: 'PUT', body: m }); } catch (e) { ctxToast(e.message); }
  }
  async function removeMark(kind, passage) {
    state.marks = state.marks.filter(x => !(x.kind === kind && x.passage === passage)); drawMarks();
    try { await api(`/api/me/marks/${work.id}/${kind}/${passage}`, { method: 'DELETE' }); } catch (e) { ctxToast(e.message); }
  }
  const ctxToast = text => { const t = document.querySelector('#toast'); t.onclick = null; t.style.cursor = ''; t.textContent = text; t.hidden = false; clearTimeout(ctxToast.timer); ctxToast.timer = setTimeout(() => { t.hidden = true; }, 2500); };
  bmBtn.onclick = async () => {
    const v = visible(); if (!v) return;
    const has = state.marks.find(m => m.kind === 'bookmark' && m.passage >= v.first && m.passage <= v.last);
    if (has) { await removeMark('bookmark', has.passage); ctxToast('Bookmark removed'); }
    else { await setMark({ passage: v.first, kind: 'bookmark', note: '', text: markText(v.first), created: Date.now() }); ctxToast('Page bookmarked'); }
  };
  const noteFor = async m => { const n = prompt(m.note ? 'Edit the note:' : 'Add a note:', m.note || ''); if (n === null) return; await setMark(Object.assign({}, m, { note: n.trim() })); if (root.querySelector('.rmenu[data-kind=marks]')) { closeMenu(); marksBtn.click(); } };
  marksBtn.onclick = () => {
    const sorted = [...state.marks].sort((a, b) => a.passage - b.passage);
    const narratedId = narrating() ? passageForAudio(player.globalMs()) : -1;
    const actions = el('div', { class: 'actions' },
      narratedId >= 0 ? el('button', { class: 'ghost', onclick: async () => { const has = state.marks.find(m => m.kind === 'highlight' && m.passage === narratedId); if (has) await removeMark('highlight', narratedId); else await setMark({ passage: narratedId, kind: 'highlight', note: '', text: markText(narratedId), created: Date.now() }); closeMenu(); marksBtn.click(); } }, state.marks.some(m => m.kind === 'highlight' && m.passage === narratedId) ? 'Remove highlight on the narrated sentence' : 'Highlight the narrated sentence') : null,
      sorted.length ? el('button', { class: 'ghost', onclick: () => ctx.download(`${work.title} - highlights.md`, ctx.marksMarkdown(work.title, work.author, state.passages.length, state.marks, chapterTitleOf)) }, 'Export as Markdown') : null,
      el('span', { class: 'muted', style: 'font-size:13px;align-self:center' }, 'Select text on the page to highlight it.'));
    const rows = sorted.map(m => el('div', { class: 'mk' },
      el('div', { class: 'grow', onclick: () => { closeMenu(); navigate(() => goTo(m.passage)); } },
        el('div', { class: 'q' + (m.kind === 'highlight' ? ' hl' : '') }, m.kind === 'bookmark' ? el('span', { html: ICONS.bookmark, style: 'display:inline-flex;vertical-align:-3px;margin-right:6px' }) : null, m.text || markText(m.passage) || '…'),
        el('div', { class: 's' }, [chapterTitleOf(m.passage), state.passages.length ? Math.round(m.passage * 100 / state.passages.length) + '%' : null, m.created ? fmtDate(m.created) : null].filter(Boolean).join(' · ')),
        m.note ? el('div', { class: 'note' }, m.note) : null),
      el('button', { class: 'x', title: m.note ? 'Edit note' : 'Add note', 'aria-label': m.note ? 'Edit note' : 'Add note', onclick: () => noteFor(m) }, '✎'),
      el('button', { class: 'x', title: 'Remove', 'aria-label': 'Remove', onclick: async () => { await removeMark(m.kind, m.passage); closeMenu(); marksBtn.click(); } }, '✕')));
    menu('marks', el('div', { class: 'caption', style: 'margin-bottom:4px' }, `Bookmarks & highlights · ${sorted.length}`), actions, rows.length ? el('div', {}, rows) : el('p', { class: 'muted', style: 'margin:6px 0 0;font-size:14px' }, 'None yet. Bookmark a page with the ribbon, or select text and choose Highlight.'));
  };
  // ---- Search: every sentence with the words, in reading order; a result opens its page with the sentence marked.
  searchBtn.onclick = () => {
    const input = el('input', { type: 'search', placeholder: 'Words, or "an exact phrase"', 'aria-label': 'Search in this book', value: state.query || '', autocomplete: 'off' });
    const summary = el('div', { class: 'muted', style: 'font-size:12px' }, '');
    const list = el('div', {});
    const m = menu('search', el('div', { class: 'caption' }, 'Search in this book'), input, summary, list);
    if (!m) return;
    m.classList.add('wide');
    state.folded ||= state.passages.map(p => fold(p.text));
    let shown = 50;
    const render = () => {
      state.query = input.value;
      // Words in any order within a sentence; in quotes, the exact phrase.
      const typed = fold(input.value.trim()).replace(/ +/g, ' ');
      const exact = typed.length > 2 && typed.startsWith('"') && typed.endsWith('"');
      const q = typed.replace(/^"+|"+$/g, '').trim();
      if (q.length < 2) { summary.textContent = ''; list.replaceChildren(); return; }
      const split = [...new Set(q.split(' ').filter(t => t.length >= 2))];
      const terms = exact || !q.includes(' ') || !split.length ? [q] : split;
      const hits = []; for (let i = 0; i < state.folded.length; i++) if (terms.every(t => state.folded[i].includes(t))) hits.push(i);
      const words = wordsBefore(), total = words[state.passages.length] || 1;
      summary.textContent = hits.length === 0 ? 'Nothing found' : hits.length === 1 ? '1 sentence' : `${hits.length.toLocaleString()} sentences`;
      const rows = hits.slice(0, shown).map(id => {
        const text = state.passages[id].text, f = state.folded[id], at = Math.min(...terms.map(t => f.indexOf(t)));
        // A long sentence shows the part around the first match.
        const from = text.length > 240 && at > 100 ? text.lastIndexOf(' ', at - 60) + 1 : 0;
        const to = text.length - from > 240 ? (i => i < 0 ? text.length : i)(text.indexOf(' ', from + 220)) : text.length;
        const parts = [from > 0 ? '…' : '']; let cursor = from;
        const spans = []; for (const t of terms) for (let i = f.indexOf(t); i >= 0 && i < to; i = f.indexOf(t, i + t.length)) if (i >= from) spans.push([i, Math.min(i + t.length, to)]);
        spans.sort((a, b) => a[0] - b[0]);
        for (const [a, b] of spans) { if (b <= cursor) continue; const s = Math.max(a, cursor); parts.push(text.slice(cursor, s), el('mark', {}, text.slice(s, b))); cursor = b; }
        parts.push(text.slice(cursor, to), to < text.length ? '…' : '');
        const go = async () => {
          closeMenu(); state.found = id; state.following = false;
          await navigate(() => goTo(id)); drawFound(); updateParity();
        };
        return el('div', { class: 'mk' }, el('div', { class: 'grow', role: 'button', tabindex: '0', onclick: go, onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); go(); } } }, el('div', { class: 's', style: 'margin:0 0 2px' }, [chapterTitleOf(id), Math.round(words[id] * 100 / total) + '%'].filter(Boolean).join(' · ')), el('div', { class: 'q found' }, parts)));
      });
      if (hits.length > shown) rows.push(el('button', { class: 'ghost', style: 'margin-top:6px', onclick: () => { shown += 100; render(); } }, 'Show more'));
      list.replaceChildren(...rows);
    };
    input.oninput = () => { shown = 50; render(); };
    render(); input.focus(); input.select();
  };

  // A toolbar over a text selection: highlight it, add a note, or narrate from it.
  let selBar = null;
  const hideSel = () => { selBar?.remove(); selBar = null; };
  function showSel(doc) {
    const sel = doc.getSelection(); if (!sel || sel.isCollapsed || !sel.toString().trim()) return hideSel();
    const range = sel.getRangeAt(0); const map = mapDocument(doc, view.renderer.getContents().find(c => c.doc === doc)?.index);
    const hits = map.ranges.filter(r => r.range.compareBoundaryPoints(Range.END_TO_START, range) < 0 && r.range.compareBoundaryPoints(Range.START_TO_END, range) > 0).map(r => r.id);
    if (!hits.length) return hideSel();
    const rect = range.getBoundingClientRect(); const frame = doc.defaultView.frameElement?.getBoundingClientRect() || { left: 0, top: 0 }; const rootRect = root.getBoundingClientRect();
    hideSel();
    const mark = async note => { for (const id of hits) await setMark({ passage: id, kind: 'highlight', note: note && id === hits[0] ? note : '', text: markText(id), created: Date.now() }); sel.removeAllRanges(); hideSel(); ctxToast(hits.length === 1 ? 'Sentence highlighted' : `${hits.length} sentences highlighted`); };
    const left = Math.min(rootRect.width - 120, Math.max(120, Math.round(frame.left + rect.left + rect.width / 2 - rootRect.left)));
    const top = Math.max(60, Math.round(frame.top + rect.top - rootRect.top - 8));
    selBar = el('div', { class: 'sel', style: `left:${left}px;top:${top}px` },
      el('button', { onclick: () => mark('') }, 'Highlight'),
      el('button', { onclick: () => { const n = prompt('Note for this highlight:'); if (n !== null) mark(n.trim()); } }, 'Note'),
      work.hasAudio ? el('button', { onclick: async () => { const a = anchorFrom(hits[0]); if (!a) return; sel.removeAllRanges(); hideSel(); state.passage = hits[0]; state.following = true; await player.open(work.id, { play: true, at: a.start }); } }, 'Listen from here') : null);
    root.append(selBar);
  }

  // ---- Parity: where the narration is, relative to the page.
  function updateParity() {
    if (!work.hasAudio || !state.anchors.length) return;
    const live = narrating(); const playing = live && player.isPlaying();
    for (const b of [speed, back30, fwd30]) if (b) b.hidden = !live;
    if (speed) speed.textContent = speedText();
    if (!live) { parity.hidden = true; follow.hidden = true; return; }
    const ms = player.globalMs(); const id = passageForAudio(ms); const v = visible();
    let text = '', off = false;
    if (v) {
      if (id >= v.first && id <= v.last) text = 'In sync';
      else {
        off = true;
        const dc = chapterOf(id) - chapterOf(v.first), ds = sectionOf(id) - sectionOf(v.first);
        const dir = id > v.last ? 'ahead' : 'behind';
        text = dc === 0 && ds === 0 ? `Audio ${dir} on this page's chapter` : Math.abs(dc) <= 1 ? `Audio a chapter ${dir}` : `Audio ${Math.abs(dc)} chapters ${dir}`;
      }
    }
    parity.hidden = false; parity.querySelector('.dot').classList.toggle('warn', off);
    parity.lastElementChild.textContent = `${fmt(ms)}${text ? ' · ' + text : ''}${!playing ? ' · paused' : ''}`;
    follow.hidden = !(off && playing);
  }
  // ---- Pace: time left in the chapter and the book, at the narration speed while listening, else at this reader's own pace
  // (words a minute, learned from forward page turns on this browser; 230 until there is something to go on).
  const wpm = () => Number(localStorage.getItem('unison.read.wpm')) || 230;
  const wordsBefore = () => state.words ||= (() => { const w = new Uint32Array(state.passages.length + 1); state.passages.forEach((p, i) => { w[i + 1] = w[i] + (p.text.trim().split(/\s+/).length || 0); }); return w; })();
  function learnPace(from, to) {
    const now = Date.now(), took = now - (state.turnedAt || 0); state.turnedAt = now;
    if (!(to > from) || took < 5000 || took > 600000) return;
    const words = wordsBefore()[to] - wordsBefore()[from]; if (words < 40) return;
    const sample = words / (took / 60000); if (sample < 80 || sample > 700) return;
    const had = Number(localStorage.getItem('unison.read.wpm')); localStorage.setItem('unison.read.wpm', String(Math.round((had || 230) * .85 + sample * .15)));
  }
  function updatePace() {
    const n = state.passages.length; if (!n) { pace.textContent = ''; return; }
    const listening = narrating() && player.isPlaying() && work.duration > 0 && state.anchors.length;
    const here = Math.min(n, Math.max(0, listening ? passageForAudio(player.globalMs()) : (state.visible?.first ?? state.passage)));
    const end = state.toc.map(t => t.passage).filter(p => p > here).sort((a, b) => a - b)[0] ?? n;
    let chapter, book;
    if (listening) {
      const ms = player.globalMs(), speed = player.speed || 1, endMs = end >= n ? work.duration : (anchorFrom(end)?.start ?? null);
      chapter = endMs === null ? null : Math.max(0, endMs - ms) / speed / 60000; book = Math.max(0, work.duration - ms) / speed / 60000;
    } else { const w = wordsBefore(); chapter = (w[end] - w[here]) / wpm(); book = (w[n] - w[here]) / wpm(); }
    const f = m => m < 1 ? 'under a minute' : m < 60 ? `${Math.round(m)} min` : `${Math.floor(m / 60)} h ${String(Math.round(m % 60)).padStart(2, '0')} min`;
    pace.textContent = [chapter !== null ? `${f(chapter)} left in chapter` : null, `${f(book)} in book`].filter(Boolean).join(' · ');
  }
  follow.onclick = async () => { state.following = true; const id = passageForAudio(player.globalMs()); await navigate(() => goTo(id)); highlight(id); updateParity(); };

  // ---- Follow the narration: light the sentence; bring it into view while following.
  state.unsub = player.onTime(async ms => {
    const playing = narrating() && player.isPlaying();
    if (playing !== state.wasPlaying) { state.wasPlaying = playing; if (!playing && !state.destroyed) sessions.start(work.id, 'read'); }
    if (playing !== state.pacedPlaying || playing) { state.pacedPlaying = playing; updatePace(); }
    if (ms === null || !narrating() || !state.anchors.length) { setListen(false); updateParity(); return; }
    const id = passageForAudio(ms);
    if (id !== state.highlighted) {
      state.highlighted = id; if (player.isPlaying()) state.passage = id;
      const lit = highlight(id);
      if (state.following && player.isPlaying() && (!lit || !inView(id)) && !state.busy) { state.busy = true; try { await navigate(() => goTo(id)); highlight(id); } finally { state.busy = false; } }
    }
    setListen(player.isPlaying()); updateParity();
  });

  // A big jump offers its way back for four seconds, as the app does.
  function offerUndo(was) {
    root.querySelector('.undo')?.remove();
    const u = el('div', { class: 'undo' }, `Was at ${fmt(was)}`, el('button', { onclick: async () => { u.remove(); state.following = true; await player.seekTo(was); const id = passageForAudio(was); await navigate(() => goTo(id)); highlight(id); } }, 'Resume'));
    root.append(u); setTimeout(() => u.remove(), 4000);
  }
  if (listen) listen.onclick = async () => {
    if (narrating() && player.isPlaying()) { player.toggle(); setListen(false); return; }
    // Top of the page, unless the audio stopped on this page or in the two sentences just before it.
    const was = audioNow(); const v = visible(); const paused = was ? passageForAudio(was) : -1;
    const onPage = v && paused >= v.first - 2 && paused <= v.last;
    const from = onPage ? null : (v ? anchorFrom(v.first) : anchorFrom(state.passage));
    state.following = true;
    await player.open(work.id, { play: true, at: from ? from.start : null });
    setListen(true);
    if (from && was && Math.abs(from.start - was) > 90000) offerUndo(was);
  };

  view.addEventListener('load', ({ detail: { doc, index } }) => {
    mapDocument(doc, index); drawMarks(); drawFound();
    // Double-click a sentence: narrate from there.
    doc.addEventListener('dblclick', async e => {
      if (!work.hasAudio) return;
      const pos = doc.caretPositionFromPoint ? doc.caretPositionFromPoint(e.clientX, e.clientY) : (() => { const r = doc.caretRangeFromPoint(e.clientX, e.clientY); return r && { offsetNode: r.startContainer, offset: r.startOffset }; })();
      if (!pos) return;
      const hit = mapDocument(doc, index).ranges.find(r => r.range.isPointInRange(pos.offsetNode, pos.offset)); if (!hit) return;
      const a = anchorFrom(hit.id); if (!a) return;
      state.passage = hit.id; highlight(hit.id); state.following = true;
      doc.getSelection()?.removeAllRanges(); hideSel();
      await player.open(work.id, { play: true, at: a.start });
    });
    doc.addEventListener('mouseup', () => setTimeout(() => showSel(doc), 10));
    doc.addEventListener('selectionchange', () => { const s = doc.getSelection(); if (!s || s.isCollapsed) hideSel(); });
    doc.addEventListener('keydown', keys);
  });
  view.addEventListener('relocate', ({ detail: d }) => {
    const v = visible(); state.visible = v; hideSel();
    const userMoved = state.navigating === 0 && d.reason !== 'anchor' && d.reason !== 'navigation';
    if (userMoved && state.found >= 0) { state.found = -1; drawFound(); }
    if (v && !(narrating() && player.isPlaying())) state.passage = v.first;
    if (userMoved && narrating() && player.isPlaying() && state.highlighted >= 0 && !(v && state.highlighted >= v.first && state.highlighted <= v.last)) state.following = false;
    if (userMoved && v && state.lastFirst >= 0 && !(narrating() && player.isPlaying())) learnPace(state.lastFirst, v.first);
    if (userMoved && v && sessions.open?.kind === 'read') { sessions.count('pages'); if (state.lastFirst >= 0 && v.first > state.lastFirst) sessions.count('sentences', v.first - state.lastFirst); }
    if (v) state.lastFirst = v.first;
    const chapter = d.tocItem?.label?.trim() || chapterTitleOf(v?.first ?? state.passage);
    state.chapterLabel = chapter; chapterLabel.textContent = chapter;
    const loc = d.location;
    where.textContent = loc?.total ? `Page ${loc.current + 1} of ${loc.total}` : '';
    const f = Math.round((d.fraction || 0) * 100); pct.textContent = `${f}%`; bar2.firstElementChild.style.width = `${f}%`;
    drawMarks(); updateParity(); updatePace();
  });

  await view.open(new File([epub], 'book.epub', { type: 'application/epub+zip' }));
  if (state.destroyed) return;
  view.renderer.setAttribute('flow', prefs.flow);
  view.renderer.setAttribute('gap', '7%'); view.renderer.setAttribute('margin', '34px');
  applyStyle();
  // Start where you were: the saved sentence, or the sentence of the saved audio position.
  // A link to one sentence (a highlight on the highlights page) opens there with the sentence marked, like a search result.
  const linked = Number.isInteger(ctx.openAt) && state.passages[ctx.openAt] ? ctx.openAt : -1;
  if (linked >= 0) state.found = linked;
  const startAt = linked >= 0 ? linked : state.passage > 0 ? state.passage : (detail.position?.audio ? passageForAudio(detail.position.audio) : 0);
  await navigate(async () => { if (startAt > 0 && state.passages[startAt]) { state.passage = startAt; await goTo(startAt); } else await view.goTo(0); });
  // The page opened at counts as sent once it settles: only a move made here is saved, so an idle tab never overwrites another device's progress.
  setTimeout(() => { if (state.lastPushed === '') state.lastPushed = `${state.passage}:${audioNow()}`; }, 1500);
  if (narrating()) { state.following = player.isPlaying() && linked < 0; state.wasPlaying = player.isPlaying(); const id = passageForAudio(player.globalMs()); if (!highlight(id) && state.following) { await navigate(() => goTo(id)); highlight(id); } setListen(player.isPlaying()); }
  updateParity();
  function shortcuts() {
    const rows = [['← →', 'Turn the page'], ['Space', 'Listen here, or pause'], ['/', 'Search in this book'], ['T', 'Contents'], ['B', 'Bookmark this page'], ['M', 'Bookmarks & highlights'], ['Esc', 'Close a panel'], ['?', 'This list']];
    menu('keys', el('div', { class: 'caption' }, 'Keyboard'), el('div', { class: 'keys' }, rows.map(([k, what]) => el('div', {}, el('kbd', {}, k), el('span', {}, what)))));
  }
  function keys(e) {
    if (e.key === 'Escape') { closeMenu(); hideSel(); return; }
    // Typing in a field (search, a slider) is not a shortcut.
    if (e.target.closest?.('input,textarea')) return;
    if (e.key === '/' || ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F'))) { e.preventDefault(); if (!root.querySelector('.rmenu[data-kind=search]')) searchBtn.click(); return; }
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key === 't' || e.key === 'T') return tocBtn.click();
    if (e.key === 'm' || e.key === 'M') return marksBtn.click();
    if (e.key === '?') return shortcuts();
    if (e.key === 'ArrowRight' || e.key === 'PageDown') turn(1); else if (e.key === 'ArrowLeft' || e.key === 'PageUp') turn(-1); else if (e.key === ' ' && listen && !e.target.closest?.('input,button')) { e.preventDefault(); listen.click(); } else if (e.key === 'Escape') { closeMenu(); hideSel(); } else if ((e.key === 'b' || e.key === 'B') && !e.ctrlKey && !e.metaKey && !e.target.closest?.('input')) bmBtn.click(); }
  document.addEventListener('keydown', keys);
  const oldTeardown = current.teardown; current.teardown = () => { document.removeEventListener('keydown', keys); window.removeEventListener('resize', fitNav); hideSel(); oldTeardown(); };
}
