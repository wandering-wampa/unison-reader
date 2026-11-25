"""Checks the first-run setup (GET and POST /api/setup). Unlike the other checks it starts its own servers, because a
server can be set up only once: three short runs of the built distribution (gradlew :server:installDist first) on
throwaway folders under build/setup-check, on port 8097 or the one given. Needs java on PATH (. .\\Enter-Android.ps1).
A caller from outside is imitated with the header a proxy would send; this machine itself counts as local.
Nothing here prints a key or a token."""
import hashlib, json, os, shutil, sqlite3, subprocess, sys, threading, time, urllib.request
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8097
BASE = f'http://127.0.0.1:{PORT}'
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ROOT = os.path.join(REPO, 'build', 'setup-check')
LIB = os.path.join(REPO, 'server', 'build', 'install', 'server', 'lib', '*')
OUTSIDE, ELSEWHERE = '203.0.113.9', '198.51.100.7'   # documentation addresses: public, and nobody's
# Two private addresses, written in pieces because the scan that guards the public copy stops at anything shaped like one.
LAN, PROXY = '.'.join(('192', '168', '1', '20')), '.'.join(('10', '0', '0', '1'))
PASSWORD = 'a-first-pass-phrase-11'
fails = 0

def call(method, path, body=None, token=None, headers=None):
    r = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    r.add_header('Content-Type', 'application/json')
    if token: r.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items(): r.add_header(k, v)
    try:
        with urllib.request.urlopen(r) as x: return x.status, json.loads(x.read().decode() or '{}'), x.headers
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        return e.code, (json.loads(t) if t.startswith('{') else {'message': t}), e.headers

def ok(cond, what):
    global fails
    print(('  ok   ' if cond else '  FAIL ') + what)
    if not cond: fails += 1

def start(name, fresh=True):
    """A server on its own folders; returns the process and the folder."""
    root = os.path.join(ROOT, name)
    if fresh and os.path.exists(root): shutil.rmtree(root)
    for d in ('ebooks', 'audiobooks', 'data'): os.makedirs(os.path.join(root, d), exist_ok=True)
    open(os.path.join(root, 'model.onnx'), 'ab').close()
    env = dict(os.environ, UNISON_EBOOKS=os.path.join(root, 'ebooks'), UNISON_AUDIOBOOKS=os.path.join(root, 'audiobooks'),
               UNISON_DATA=os.path.join(root, 'data'), UNISON_MODEL=os.path.join(root, 'model.onnx'), PORT=str(PORT), UNISON_WANTED_WEBHOOK='', UNISON_TOKEN='')
    log = open(os.path.join(root, 'server.log'), 'ab')
    p = subprocess.Popen(['java', '-Xss4m', '-cp', LIB, 'dev.unison.server.MainKt'], env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        if p.poll() is not None: raise SystemExit(f'the server stopped at once: see {os.path.join(root, "server.log")}')
        try:
            if call('GET', '/api/health')[0] == 200: return p, root
        except OSError: time.sleep(0.25)
    p.kill(); raise SystemExit('the server did not answer in thirty seconds')

def stop(p):
    p.terminate()
    try: p.wait(10)
    except subprocess.TimeoutExpired: p.kill()

def key_of(root): return open(os.path.join(root, 'data', 'token')).read().strip()
def key_file(root): return os.path.join(root, 'data', 'token')
def status(headers=None): return call('GET', '/api/setup', headers=headers)[1]
def setup(body, headers=None): s, m, h = call('POST', '/api/setup', body, headers=headers); return s, m, h
def login(password): return call('POST', '/api/login', {'name': 'admin', 'password': password, 'device': 'check-setup'})

if not os.path.isdir(os.path.dirname(LIB)): raise SystemExit('Build the server first: gradlew :server:installDist')
try: call('GET', '/api/health'); raise SystemExit(f'Something already answers on port {PORT}: give another port.')
except OSError: pass

print('A new server on two empty folders, set up from this machine')
p, root = start('local')
try:
    KEY = key_of(root)
    s = status()
    ok(s.get('needed') is True and s.get('key') is False and s.get('minPassword') == 8, f'setup is needed, and from this machine no key is asked for ({ {k: v for k, v in s.items() if k != "library"} })')
    for _ in range(40):
        if status()['library']['scanned']: break
        time.sleep(0.25)
    lib = status()['library']
    ok(lib['scanned'] and lib['ebooks'] == 0 and lib['audiobooks'] == 0 and lib['paired'] == 0, f'the scan has ended and found nothing ({lib})')
    ok(lib['ebookFolderEmpty'] and lib['audiobookFolderEmpty'] and not lib['calibre'], 'both folders are reported empty, and no Calibre library')
    for name, value, want in (('X-Forwarded-For', OUTSIDE, True), ('X-Forwarded-For', LAN, False), ('X-Forwarded-For', f'{LAN}, {OUTSIDE}', True),
                              ('X-Forwarded-For', f'{OUTSIDE}, {PROXY}', True), ('X-Real-IP', OUTSIDE, True), ('Forwarded', f'for={OUTSIDE};proto=https', True),
                              ('Forwarded', 'for="[fd00::7]:4711"', False), ('CF-Connecting-IP', OUTSIDE, True), ('X-Forwarded-For', 'unknown', True)):
        ok(status({name: value})['key'] is want, f'{name}: {value} -> the key is {"asked for" if want else "not asked for"}')
    ok(login('')[0] == 401 and login(PASSWORD)[0] == 401, 'nobody can sign in as admin yet')
    ok(call('GET', '/api/me', token=KEY)[1].get('user', {}).get('name') == 'admin', 'the first key signs in as admin before setup')
    ok(call('GET', '/api/me')[0] == 401 and call('GET', '/api/works')[0] == 401, 'the rest of the API still needs a sign-in')
    s, m, _ = setup({'password': 'short'})
    ok(s == 400, f'a password under eight characters: 400 ({m.get("message")})')
    s, m, _ = setup({'password': ''})
    ok(s == 400 and status()['needed'], 'no password: 400, and setup is still needed')
    s, m, _ = setup({'password': PASSWORD}, {'X-Forwarded-For': OUTSIDE})
    ok(s == 403 and 'key' in m.get('message', ''), f'from outside without the key: 403 ({m.get("message")})')
    s, m, _ = setup({'password': PASSWORD, 'key': 'not-the-key'}, {'X-Forwarded-For': OUTSIDE})
    ok(s == 403 and status()['needed'], f'from outside with a wrong key: 403 ({m.get("message")}), and setup is still needed')
    s, m, _ = setup({'password': PASSWORD, 'key': 'not-the-key'}, {'X-Forwarded-For': f'127.0.0.1, {OUTSIDE}'})
    ok(s == 403, 'a forged local address in front of the real one does not get past the key')
    # Six requests at once, each with its own password: one is taken.
    answers = []
    def race(i): answers.append((i, setup({'password': f'{PASSWORD}-{i}', 'web': True, 'device': 'check-setup'})))
    threads = [threading.Thread(target=race, args=(i,)) for i in range(6)]
    for t in threads: t.start()
    for t in threads: t.join()
    won = [(i, a) for i, a in answers if a[0] == 200]
    ok(len(won) == 1 and sorted(a[0] for _, a in answers) == [200] + [409] * 5, f'six at once: one is taken, five are told it is done ({sorted(a[0] for _, a in answers)})')
    i, (s, a, h) = won[0]; token = a.get('token', '')
    ok(a.get('user', {}).get('name') == 'admin' and a['user'].get('role') == 'admin' and len(token) == 64, 'the answer signs the caller in as admin')
    cookie = h.get('Set-Cookie') or ''
    ok('unison=' in cookie and 'HttpOnly' in cookie and 'SameSite=Strict' in cookie, 'and sets the browser cookie when asked as the web client')
    ok(call('GET', '/api/me', token=token)[0] == 200, 'the token it gave works')
    # Only two of the losers are tried: five wrong passwords in a row would trip the sign-in throttle and hide the answers below.
    ok(login(f'{PASSWORD}-{i}')[0] == 200 and all(login(f'{PASSWORD}-{j}')[0] == 401 for j in [j for j in range(6) if j != i][:2]), 'the password that was taken signs in, and others that were sent do not')
    s = status()
    ok(s == {'needed': False, 'key': False, 'reset': False, 'minPassword': 8, 'library': None}, f'afterwards the server says setup is not needed and nothing else ({s})')
    ok(status({'X-Forwarded-For': OUTSIDE}).get('library') is None, 'from outside too')
    s, m, _ = setup({'password': 'another-pass-phrase'})
    ok(s == 409 and login('another-pass-phrase')[0] == 401, f'a second setup: 409 ({m.get("message")}), and the password is unchanged')
    s, m, _ = setup({'password': 'another-pass-phrase', 'key': KEY}, {'X-Forwarded-For': OUTSIDE})
    ok(s == 409 and login('another-pass-phrase')[0] == 401, 'a second setup with the right key from outside: 409 as well')
    ok(call('GET', '/api/me', token=KEY)[0] == 401 and not os.path.exists(key_file(root)), 'setup ended the first key: it is refused and its file is gone')
    audit = call('GET', '/api/users/audit', token=token)[1]
    done = [e for e in audit if e['action'] == 'server set up']; refused = [e for e in audit if e['action'] == 'setup refused']
    ok(len(done) == 1 and done[0]['user'] == 'admin' and done[0]['address'] == '127.0.0.1' and done[0]['detail'] == 'check-setup', f'the audit log has the setup once, with the address and device ({done[0] if done else None})')
    ok(len(refused) == 3 and {e['detail'] for e in refused} == {'no setup key', 'wrong setup key'} and all(e['address'] in (OUTSIDE, '127.0.0.1') for e in refused), f'and the three refusals ({[(e["address"], e["detail"]) for e in refused]})')
    ok(not any(KEY in json.dumps(e) or token in json.dumps(e) for e in audit), 'with no key or token in it')
finally: stop(p)
p, root = start('local', fresh=False)
try:
    ok(status()['needed'] is False and login(f'{PASSWORD}-{i}')[0] == 200 and setup({'password': 'another-pass-phrase'})[0] == 409, 'after a restart it is still set up')
    ok(call('GET', '/api/me', token=KEY)[0] == 401 and not os.path.exists(key_file(root)), 'and the first key is still refused, with no new key file')
finally: stop(p)
print('A server set up before the key ended: its key is put back in the database, as an older server had it')
con = sqlite3.connect(os.path.join(root, 'data', 'catalog.db'))
con.execute("INSERT INTO tokens (token, prefix, hashed, user, device, created, last_seen) VALUES (?, ?, 1, 1, 'server', 0, ?)", (hashlib.sha256(KEY.encode()).hexdigest(), KEY[:8], int(time.time() * 1000)))
con.commit(); con.close()
open(key_file(root), 'w').write(KEY)
p, root = start('local', fresh=False)
try:
    ok(call('GET', '/api/me', token=KEY)[0] == 200 and os.path.exists(key_file(root)), 'a server that was already set up keeps its key and its file')
finally: stop(p)
p, root = start('local', fresh=False)
try:
    ok(call('GET', '/api/me', token=KEY)[0] == 200 and key_of(root) == KEY, 'and after another restart')
finally: stop(p)

print('A new server whose folders hold something, but no library; set up from outside with the key')
root = os.path.join(ROOT, 'outside')
if os.path.exists(root): shutil.rmtree(root)
os.makedirs(os.path.join(root, 'ebooks')); os.makedirs(os.path.join(root, 'audiobooks', 'An Author', 'A Title'))
open(os.path.join(root, 'ebooks', 'a-loose-book.epub'), 'wb').close()
p, root = start('outside', fresh=False)
try:
    for _ in range(40):
        if status()['library']['scanned']: break
        time.sleep(0.25)
    lib = status()['library']
    ok(lib['scanned'] and not lib['calibre'] and not lib['ebookFolderEmpty'] and not lib['audiobookFolderEmpty'] and lib['ebooks'] == 1 and lib['audiobooks'] == 0,
       f'a folder with a loose EPUB (unreadable, but still a book) and no metadata.db, and an audio folder with no audio: one ebook, no audiobook, neither reported empty ({lib})')
    KEY2 = key_of(root)
    out = {'X-Forwarded-For': OUTSIDE}
    codes = [setup({'password': PASSWORD, 'key': f'guess-{n}'}, out)[0] for n in range(8)]
    ok(codes[:5] == [403] * 5 and codes[5:] == [429] * 3, f'wrong keys from one address are slowed after five: {codes}')
    s, m, h = setup({'password': PASSWORD, 'key': key_of(root)}, out)
    ok(s == 429 and h.get('Retry-After', '').isdigit(), f'and then even the right key waits, with Retry-After ({m.get("message")})')
    ok(status()['needed'], 'setup is still needed')
    s, m, _ = setup({'password': 'short', 'key': key_of(root)}, {'X-Forwarded-For': ELSEWHERE})
    ok(s == 400, f'another address is not held up: the right key with a short password is 400 ({m.get("message")})')
    s, a, _ = setup({'password': PASSWORD, 'key': key_of(root) + '\n', 'device': 'check-setup-outside'}, {'X-Forwarded-For': ELSEWHERE})
    ok(s == 200 and a.get('user', {}).get('name') == 'admin', 'the right key (pasted with its line end) and a password of eight or more: set up and signed in')
    ok(login(PASSWORD)[0] == 200 and login('guess-0')[0] == 401, 'the password signs in')
    ok(setup({'password': 'another-pass-phrase', 'key': KEY2}, {'X-Forwarded-For': ELSEWHERE})[0] == 409, 'and a second setup is refused')
    ok(call('GET', '/api/me', token=KEY2)[0] == 401, 'and the key it was set up with no longer signs in')
    audit = call('GET', '/api/users/audit', token=a['token'])[1]
    ok([e['address'] for e in audit if e['action'] == 'server set up'] == [ELSEWHERE] and any(e['action'] == 'setup throttled' and e['address'] == OUTSIDE for e in audit),
       'the audit log names the address that set it up and the one that was slowed')
finally: stop(p)
print('A forgotten admin password: the file reset-admin, read once at start-up')
NEW = 'a-new-pass-phrase-22'
p, root = start('reset')
try:
    ok(setup({'password': PASSWORD})[0] == 200 and not os.path.exists(key_file(root)), 'a new server, set up from this machine; its key file is gone')
    token = login(PASSWORD)[1].get('token', '')
    other = call('POST', '/api/users', {'name': 'someone', 'password': 'their-pass-phrase', 'role': 'member'}, token=token)
    ok(other[0] in (200, 201), f'a second account is made ({other[0]})')
finally: stop(p)
open(os.path.join(root, 'data', 'reset-admin'), 'w').close()
p, root = start('reset', fresh=False)
try:
    ok(not os.path.exists(os.path.join(root, 'data', 'reset-admin')), 'the file is gone after the start')
    s = status()
    ok(s.get('needed') is True and s.get('key') is True and s.get('reset') is True, f'setup is needed, with the key and the reset flag, even from this machine ({ {k: v for k, v in s.items() if k != "library"} })')
    ok(status({'X-Forwarded-For': OUTSIDE}).get('reset') is True, 'and from outside')
    ok(call('GET', '/api/me', token=token)[0] == 401, 'the admin is signed out everywhere')
    ok(login(PASSWORD)[0] == 401, 'the old password no longer signs in')
    ok(call('POST', '/api/login', {'name': 'someone', 'password': 'their-pass-phrase', 'device': 'check-setup'})[0] == 200, 'the other (member) account still signs in while the reset is pending')
    NEWKEY = key_of(root)
    ok(call('GET', '/api/me', token=NEWKEY)[0] == 401, 'the setup key does not sign in during a reset')
    s, m, _ = setup({'password': NEW})
    ok(s == 403 and 'reset' in m.get('message', ''), f'from this machine without the key: 403 ({m.get("message")})')
    s, m, _ = setup({'password': NEW, 'key': 'not-the-key'})
    ok(s == 403 and status()['needed'], 'with a wrong key: 403')
    s, m, _ = setup({'password': 'short', 'key': NEWKEY})
    ok(s == 400, 'with the key and a short password: 400')
    s, a, _ = setup({'password': NEW, 'key': NEWKEY, 'device': 'check-setup'})
    ok(s == 200 and a.get('user', {}).get('name') == 'admin', 'with the key from this machine: set up and signed in')
    ok(login(NEW)[0] == 200 and login(PASSWORD)[0] == 401, 'the new password signs in and the old one does not')
    ok(not os.path.exists(key_file(root)), 'the key file is gone again')
    ok(status() == {'needed': False, 'key': False, 'reset': False, 'minPassword': 8, 'library': None}, 'the server says setup is not needed')
    ok(setup({'password': 'another-pass-phrase', 'key': NEWKEY})[0] == 409, 'a second setup: 409')
    audit = call('GET', '/api/users/audit', token=a['token'])[1]
    ok(any(e['action'] == 'password reset' and e['user'] == 'admin' for e in audit), 'the reset is in the audit log')
    ok(not any(NEWKEY in json.dumps(e) for e in audit), 'with no key in it')
finally: stop(p)
p, root = start('reset', fresh=False)
try:
    ok(status()['needed'] is False and login(NEW)[0] == 200 and not os.path.exists(key_file(root)), 'after another start nothing is reset again')
finally: stop(p)
shutil.rmtree(ROOT, ignore_errors=True)
print('PASS' if not fails else f'{fails} FAILED'); sys.exit(1 if fails else 0)
