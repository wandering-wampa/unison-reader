"""Exercises phase 9's routes against the local server (Start-LocalServer.ps1): collections and Want to read,
the wanted list, the metadata overlay.
python server/local/check_phase9.py [base] ; prints PASS or the first thing that was wrong."""
import json, os, sys, time, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8099'
ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'build', 'local-server')
creds = dict(l.split() for l in open(os.path.join(ROOT, 'credentials.txt')).read().splitlines() if l.strip())
checks = [0]

def call(method, path, body=None, token=None, base=BASE, device='web-check'):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header('Content-Type', 'application/json'); req.add_header('X-Device', device)
    if token: req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read(); return e.code, (json.loads(raw) if raw else None)

def ok(cond, what):
    checks[0] += 1
    if not cond: sys.exit('FAIL: ' + what)

def login(name):
    s, a = call('POST', '/api/login', {'name': name, 'password': creds[name], 'device': 'check'})
    ok(s == 200, 'sign in as ' + name); return a['token']

admin, reader = login('admin'), login('reader')

# A stand-in for the Discord webhook the wanted list posts to (Start-LocalServer.ps1 points the server at it).
import threading, http.server
hook = {'posts': [], 'failed': set()}
class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        # A message with FAILONCE in it is refused the first time, to see it tried again.
        if 'FAILONCE' in body['content'] and body['content'] not in hook['failed']:
            hook['failed'].add(body['content']); self.send_response(500); self.end_headers(); return
        hook['posts'].append(body); self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
threading.Thread(target=http.server.HTTPServer(('127.0.0.1', 8098), Hook).serve_forever, daemon=True).start()
def posted(n, seconds=20):
    deadline = time.time() + seconds
    while time.time() < deadline and len(hook['posts']) < n: time.sleep(.3)
    return [p['content'] for p in hook['posts']]
works = {w['work']['title']: w['work'] for w in call('GET', '/api/works', token=admin)[1]}
ok(len(works) >= 7, 'seven fixture works')
long_way, short_way, salt, quiet, typo = (works[t]['id'] for t in ('The Long Way Down', 'The Short Way Up', 'Salt and Iron', 'A Quiet Year', 'Teh Mispelled Titel'))

# ---- collections and Want to read
for t in (admin, reader):  # leave nothing from an earlier run
    for c in call('GET', '/api/me/collections', token=t)[1]:
        if c['kind'] == 'list': call('DELETE', f"/api/me/collections/{c['id']}", token=t)
        else:
            for w in c['works']: call('DELETE', f"/api/me/collections/want/works/{w}", token=t)
    for e in call('GET', '/api/me/shelf', token=t)[1]: call('DELETE', f"/api/me/shelf/{e['work']}", token=t)
s, cs = call('GET', '/api/me/collections', token=reader)
ok(s == 200 and len(cs) == 1 and cs[0]['kind'] == 'want' and cs[0]['name'] == 'Want to read' and cs[0]['works'] == [], 'Want to read is there from the start, empty')
ok(call('GET', '/api/me/collections')[0] == 401, 'collections need a sign-in')
s, c = call('POST', '/api/me/collections', {'name': '  Beach   reads '}, reader)
ok(s == 200 and c['name'] == 'Beach reads' and c['kind'] == 'list', 'a collection is made, its name tidied')
cid = c['id']
ok(call('POST', '/api/me/collections', {'name': 'beach reads'}, reader)[0] == 400, 'the same name again is refused, whatever the case')
ok(call('POST', '/api/me/collections', {'name': 'want to read'}, reader)[0] == 400, 'a second Want to read is refused')
ok(call('POST', '/api/me/collections', {'name': ' '}, reader)[0] == 400, 'an empty name is refused')
ok(call('POST', '/api/me/collections', {'name': 'x' * 61}, reader)[0] == 400, 'a name of 61 characters is refused')
ok(call('PUT', f'/api/me/collections/{cid}/works/{salt}', token=reader)[0] == 200, 'a book goes in')
ok(call('PUT', f'/api/me/collections/{cid}/works/{salt}', token=reader)[0] == 200, 'adding it twice is harmless')
time.sleep(0.01); call('PUT', f'/api/me/collections/{cid}/works/{quiet}', token=reader)
ok(call('PUT', f'/api/me/collections/{cid}/works/nonsense', token=reader)[0] == 400, 'an unknown book is refused')
cs = call('GET', '/api/me/collections', token=reader)[1]
ok([c['name'] for c in cs] == ['Want to read', 'Beach reads'] and cs[1]['works'] == [quiet, salt], 'Want to read first; books newest first, once each')
ok(call('GET', '/api/me/collections', token=admin)[1][1:] == [], "another person does not see it")
ok(call('PUT', f'/api/me/collections/{cid}/works/{long_way}', token=admin)[0] == 400, "nor can they add to it")
ok(call('DELETE', f'/api/me/collections/{cid}', token=admin)[0] == 400, 'nor remove it')
s, c = call('PUT', f'/api/me/collections/{cid}', {'name': 'Holiday'}, reader)
ok(s == 200 and c['name'] == 'Holiday' and c['works'] == [quiet, salt], 'renamed, books kept')
ok(call('PUT', f'/api/me/collections/{cid}', {'name': 'HOLIDAY'}, reader)[0] == 200, 'renaming to itself in another case is fine')
ok(call('PUT', '/api/me/collections/want', {'name': 'Someday'}, reader)[0] == 400, 'Want to read keeps its name')
ok(call('DELETE', '/api/me/collections/want', token=reader)[0] == 400, 'and cannot be removed')
call('DELETE', f'/api/me/collections/{cid}/works/{salt}', token=reader)
ok(call('GET', '/api/me/collections', token=reader)[1][1]['works'] == [quiet], 'a book comes out')
# Want to read, and the shelf taking a book off it
call('PUT', f'/api/me/collections/want/works/{long_way}', token=reader); call('PUT', f'/api/me/collections/want/works/{short_way}', token=reader)
ok(set(call('GET', '/api/me/collections', token=reader)[1][0]['works']) == {long_way, short_way}, 'two books wanted')
ok(call('PUT', f'/api/me/shelf/{long_way}', {'state': 'reading'}, reader)[0] == 200, 'one is put on the shelf')
ok(call('GET', '/api/me/collections', token=reader)[1][0]['works'] == [short_way], 'and has left Want to read; the other stays')
call('PUT', f'/api/me/collections/{cid}/works/{long_way}', token=reader); call('PUT', f'/api/me/shelf/{long_way}', {'state': 'finished'}, reader)
ok(long_way in call('GET', '/api/me/collections', token=reader)[1][1]['works'], 'a collection keeps a shelved book')
ok(call('DELETE', f'/api/me/collections/{cid}', token=reader)[0] == 200 and len(call('GET', '/api/me/collections', token=reader)[1]) == 1, 'the collection is removed')
# Reset progress clears lists too
call('POST', '/api/me/collections', {'name': 'Gone soon'}, reader)
ok(call('DELETE', '/api/users/2/data', token=admin)[0] == 200, 'admin resets the reader')
cs = call('GET', '/api/me/collections', token=reader)[1]
ok(len(cs) == 1 and cs[0]['works'] == [], 'after a reset only an empty Want to read is left')

# ---- the wanted list
for r in call('GET', '/api/requests', token=admin)[1]['requests']: call('DELETE', f"/api/requests/{r['id']}", token=admin)
s, r1 = call('POST', '/api/requests', {'title': 'The Lighthouse Keeper', 'author': 'Nora Vale', 'note': 'the new one', 'kind': 'audiobook'}, reader)
ok(s == 200 and r1['status'] == 'wanted' and r1['requester'] == 'reader' and r1['mine'] and r1['source'] == 'web', 'a member asks for a book')
def wanted_file():
    with open(os.path.join(ROOT, 'data', 'wanted.json'), encoding='utf-8') as f: return json.load(f)
w = wanted_file()
ok(w['version'] == 1 and [(x['id'], x['title'], x['author'], x['kind'], x['requester'], x['note']) for x in w['requests']] == [(r1['id'], 'The Lighthouse Keeper', 'Nora Vale', 'audiobook', 'reader', 'the new one')], 'the wanted file holds the request')
p = posted(1)
ok(p == ['Request · type: audiobook · title: The Lighthouse Keeper by Nora Vale · from: reader'], 'a new request is posted to the webhook: %r' % p)
ok(hook['posts'][0]['allowed_mentions'] == {'parse': []} and hook['posts'][0]['username'] == 'Unison', 'and can ping nobody')
s, again = call('POST', '/api/requests', {'title': 'the lighthouse keeper', 'author': 'N. Vale'}, admin)
ok(s == 200 and call('GET', '/api/requests', token=admin)[1]['requests'].__len__() == 2, 'a different author spelling is its own request')
call('DELETE', f"/api/requests/{again['id']}", token=admin)
s, same = call('POST', '/api/requests', {'title': 'The Lighthouse Keeper!', 'author': 'Nora Vale'}, admin)
ok(s == 200 and same['id'] == r1['id'] and not same['mine'], 'the same book asked again answers with the first request')
ok(call('POST', '/api/requests', {'title': ' '}, reader)[0] == 400 and call('POST', '/api/requests', {'title': 'X', 'kind': 'vinyl'}, reader)[0] == 400, 'no title or an odd kind is refused')
s, here = call('POST', '/api/requests', {'title': 'salt and iron', 'author': 'Tomas Reed', 'kind': 'ebook'}, reader, device='phone-1')
ok(s == 200 and here['status'] == 'arrived' and here['work'] == salt and here['source'] == 'app', 'a book the library already has arrives at once (and a phone is the app)')
ok(here['id'] not in [x['id'] for x in wanted_file()['requests']], 'a request that arrived at once is not in the wanted file')
s, half = call('POST', '/api/requests', {'title': 'Glass Orchard', 'author': 'Reed', 'kind': 'both'}, reader)
ok(s == 200 and half['status'] == 'wanted', 'ebook here but both wanted: still wanted')
p = posted(3)
ok(p[1:] == ['Request · type: ebook · title: Glass Orchard by Reed · from: reader', 'Request · type: audiobook · title: Glass Orchard by Reed · from: reader'], 'both forms wanted is two posts, and the book that arrived at once (and the request taken back) was never posted: %r' % p)
# A post the webhook refuses is tried again with the next change, in order, and a title cannot break the line.
s, fail = call('POST', '/api/requests', {'title': 'FAILONCE  Book · @everyone\nsecond line', 'kind': 'ebook'}, admin)
time.sleep(5); ok(len(hook['posts']) == 3 and len(hook['failed']) == 1, 'a refused post is not counted as said: %r %r %r' % (s, [x['content'] for x in hook['posts']], hook['failed']))
s, after = call('POST', '/api/requests', {'title': 'After The Failure', 'author': 'Ann Other', 'kind': 'audiobook'}, admin)
p = posted(5)
ok(p[3:] == ['Request · type: ebook · title: FAILONCE Book - @everyone second line · from: admin', 'Request · type: audiobook · title: After The Failure by Ann Other · from: admin'], 'the refused post goes out with the next change, first: %r' % p)
for x in (fail, after): call('DELETE', f"/api/requests/{x['id']}", token=admin)
time.sleep(4); ok(len(hook['posts']) == 5, 'nothing is posted twice')
s, lst = call('GET', '/api/requests', token=reader)
ok([r['status'] for r in lst['requests']] == ['wanted', 'wanted', 'arrived'], 'wanted first, then what was settled')
ok(call('PUT', f"/api/requests/{r1['id']}", {'status': 'declined'}, reader)[0] == 400, 'only an admin settles a request')
s, d = call('PUT', f"/api/requests/{r1['id']}", {'status': 'declined'}, admin)
ok(s == 200 and d['status'] == 'declined' and d['resolved'] > 0, 'admin declines')
ok(r1['id'] not in [x['id'] for x in wanted_file()['requests']], 'a declined request leaves the wanted file')
s, d = call('PUT', f"/api/requests/{r1['id']}", {'status': 'wanted'}, admin)
ok(r1['id'] in [x['id'] for x in wanted_file()['requests']], 'and returns to it when wanted again')
ok(d['status'] == 'wanted' and d['resolved'] == 0, 'and wants it again')
s, d = call('PUT', f"/api/requests/{half['id']}", {'status': 'arrived', 'work': works['Glass Orchard']['id']}, admin)
ok(s == 200 and d['work'] == works['Glass Orchard']['id'], 'admin marks one arrived with its book')
ok(call('PUT', f"/api/requests/{half['id']}", {'status': 'arrived', 'work': 'nope'}, admin)[0] == 400, 'an unknown book is refused')
s, a_req = call('POST', '/api/requests', {'title': 'Admin Wants This'}, admin)
ok(call('DELETE', f"/api/requests/{a_req['id']}", token=reader)[0] == 400, "a member cannot remove somebody else's request")
ok(call('DELETE', f"/api/requests/{r1['id']}", token=reader)[0] == 200, 'but can take back their own')
ok(call('DELETE', f"/api/requests/{a_req['id']}", token=admin)[0] == 200, 'and an admin removes any')

# ---- the metadata overlay
call('PUT', f'/api/works/{typo}/meta', {'reset': ['title', 'subtitle', 'authors', 'narrators', 'series', 'description', 'publishedYear', 'publisher', 'language', 'genres', 'cover']}, admin)
ok(call('GET', f'/api/works/{typo}/meta', token=reader)[0] == 400 and call('PUT', f'/api/works/{typo}/meta', {'title': 'x'}, reader)[0] == 400, 'the overlay is the admin\'s')
s, v = call('GET', f'/api/works/{typo}/meta', token=admin)
ok(s == 200 and v['current']['title'] == 'Teh Mispelled Titel' and v['current']['edited'] == [], 'nothing corrected yet')
s, v = call('PUT', f'/api/works/{typo}/meta', {'title': 'The Misspelled Title', 'authors': ['Ines Calder'], 'series': [{'name': 'Harbour', 'sequence': 1.5}], 'description': 'Fixed in Unison.', 'publishedYear': '2022', 'genres': ['Memoir', 'Essays'], 'narrators': ['A Voice']}, admin)
ok(s == 200 and v['current']['title'] == 'The Misspelled Title' and v['source']['title'] == 'Teh Mispelled Titel', 'the title is corrected; the source is kept')
ok(v['current']['author'] == 'Ines Calder' and v['current']['authors'] == ['Ines Calder'] and v['current']['narrator'] == 'A Voice', 'author and narrator follow')
ok(set(v['current']['edited']) == {'title', 'authors', 'series', 'description', 'publishedYear', 'genres', 'narrators'}, 'the corrected fields are named')
w = call('GET', f'/api/works/{typo}', token=reader)[1]['work']
ok(w['title'] == 'The Misspelled Title' and w['publishedYear'] == '2022' and w['id'] == typo, 'everyone sees the corrected book, same id')
ok([x['work']['id'] for x in call('GET', '/api/series/Harbour', token=reader)[1]] == [long_way, typo, short_way], 'it sits in its series by its number')
ok(any(p['name'] == 'Ines Calder' and p['works'] == 2 for p in call('GET', '/api/authors', token=reader)[1]) and not any(p['name'] == 'ines calder' for p in call('GET', '/api/authors', token=reader)[1]), 'the author list counts it under the corrected name')
ok([x['work']['id'] for x in call('GET', '/api/works?q=misspelled', token=reader)[1]] == [typo] and call('GET', '/api/works?q=mispelled', token=reader)[1] == [], 'search finds the corrected title only')
ok(any(n['name'] == 'A Voice' for n in call('GET', '/api/narrators', token=reader)[1]), 'the narrator is listed')
s, v = call('PUT', f'/api/works/{typo}/meta', {'title': 'Teh Mispelled Titel', 'publishedYear': ''}, admin)
ok('title' not in v['current']['edited'] and v['current']['title'] == 'Teh Mispelled Titel', 'a value equal to the source drops the correction')
ok('publishedYear' not in v['current']['edited'], 'so does an empty year over an empty year')
ok(call('PUT', f'/api/works/{typo}/meta', {'title': ' '}, admin)[0] == 400 and call('PUT', f'/api/works/{typo}/meta', {'publishedYear': '99'}, admin)[0] == 400, 'an empty title or a bad year is refused')
ok(call('PUT', f'/api/works/{typo}/meta', {'cover': 'https://example.com/a.jpg'}, admin)[0] == 400 and call('PUT', f'/api/works/{typo}/meta', {'cover': 'http://127.0.0.1:8099/api/health'}, admin)[0] == 400, 'a cover from anywhere else is refused')
call('POST', '/api/scan', token=admin); time.sleep(3)
v = call('GET', f'/api/works/{typo}/meta', token=admin)[1]
ok(v['current']['description'] == 'Fixed in Unison.' and v['current']['series'][0]['name'] == 'Harbour', 'corrections survive a rescan')
s, v = call('PUT', f'/api/works/{typo}/meta', {'reset': ['authors', 'series', 'description', 'genres', 'narrators']}, admin)
ok(v['current']['edited'] == [] and v['current']['author'] == 'ines calder' and v['current']['series'] == [], 'reset puts back what the library says')
if '--online' in sys.argv:
    s, found = call('GET', f'/api/works/{typo}/meta/lookup?title=Project%20Hail%20Mary&author=Andy%20Weir', token=admin)
    ok(s == 200 and any(c['source'] == 'Audible' and c['narrators'] and c['cover'] for c in found['candidates']), 'Audible answers a lookup: ' + str(found['errors']))
    ok(any(c['source'] == 'Open Library' for c in found['candidates']), 'Open Library answers a lookup: ' + str(found['errors']))
    aud = next(c for c in found['candidates'] if c['source'] == 'Audible')
    s, v = call('PUT', f'/api/works/{typo}/meta', {'cover': aud['cover']}, admin)
    ok(s == 200 and 'cover' in v['current']['edited'] and v['current']['cover'], 'a cover from the lookup is taken')
    ok(v['current']['coverVersion'] > 0 and call('GET', f'/api/works/{typo}', token=reader)[1]['work']['coverVersion'] == v['current']['coverVersion'], 'with a version a client can watch')
    req = urllib.request.Request(BASE + f'/api/works/{typo}/cover'); req.add_header('Authorization', 'Bearer ' + admin)
    with urllib.request.urlopen(req) as r: ok(r.status == 200 and len(r.read()) > 5000 and r.headers['Content-Type'].startswith('image/'), 'and served as the cover')
    s, v = call('PUT', f'/api/works/{typo}/meta', {'reset': ['cover']}, admin)
    ok('cover' not in v['current']['edited'] and not v['current']['cover'] and v['current']['coverVersion'] == 0, 'and reset')
    ok(call('GET', f'/api/works/{typo}/meta/lookup', token=reader)[0] == 400, 'lookups are the admin\'s')
# ---- unknown paths: /api answers 404 in JSON when signed in and 401 when not; the page and its files are served, other paths are 404
for m in ('GET', 'POST', 'PUT', 'DELETE'):
    for path in ('/api/no-such-route-xyz', '/api/link/start', '/api/me/link/123456'):
        body = {} if m in ('POST', 'PUT') else None
        s, v = call(m, path, body, admin)
        ok(s == 404 and isinstance(v, dict) and v.get('message'), f'{m} {path} signed in is a 404 with a JSON message (got {s})')
        ok(call(m, path, body)[0] == 401, f'{m} {path} signed out is a 401')
import re
def raw(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as r: return r.status, r.headers, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e: return e.code, e.headers, e.read().decode('utf-8', 'replace')
s, h, page = raw('/')
ok(s == 200 and h['Content-Type'].startswith('text/html') and h['Cache-Control'] == 'no-cache', '/ is the page, text/html, no-cache')
ok(raw('/index.html')[0] == 200, '/index.html is the page')
refs = re.findall(r'<script[^>]+src="(/[^"]+)"', page) + re.findall(r'<link[^>]+rel="stylesheet"[^>]+href="(/[^"]+)"', page)
ok(len(refs) >= 2, 'the page references a script and a stylesheet')
for ref in refs:
    kind = 'javascript' if ref.split('?')[0].endswith('.js') else 'css'
    s, h, _ = raw(ref)
    ok(s == 200 and kind in h['Content-Type'], f'{ref} is served as {kind} (got {s} {h["Content-Type"]})')
for path in ('/no-such-page', '/book/abc', '/missing.js'):
    ok(raw(path)[0] == 404, f'{path} is a 404, not the page')
print(f'PASS ({checks[0]} checks)')
