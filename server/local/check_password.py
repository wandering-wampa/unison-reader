"""Checks POST /api/me/password on the local server (Start-LocalServer.ps1): the current password is required.
Leaves the reader's password as it was. The last check trips the throttle for that account, which a server restart clears."""
import json, sys, urllib.request
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8099'
creds = dict(l.split() for l in open(__file__.replace('server\\local\\check_password.py', 'build\\local-server\\credentials.txt').replace('server/local/check_password.py', 'build/local-server/credentials.txt')) if l.strip())
OLD = creds['reader']; NEW = 'a-new-pass-phrase-42'
fails = 0

def call(method, path, body=None, token=None):
    r = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    r.add_header('Content-Type', 'application/json')
    if token: r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r) as x: return x.status, json.loads(x.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        return e.code, (json.loads(t) if t.startswith('{') else {'message': t})

def ok(cond, what):
    global fails
    print(('  ok   ' if cond else '  FAIL ') + what)
    if not cond: fails += 1

def login(password): return call('POST', '/api/login', {'name': 'reader', 'password': password, 'device': 'check-password'})

s, a = login(OLD); ok(s == 200, 'the reader signs in'); token = a['token']
s, m = call('POST', '/api/me/password', {'password': NEW}, token)
ok(s == 400 and 'current' in m.get('message', '').lower(), f'no current password: refused with 400 ({m.get("message")})')
s, m = call('POST', '/api/me/password', {'current': 'not-the-password', 'password': NEW}, token)
ok(s == 403, f'a wrong current password: refused with 403 ({m.get("message")})')
ok(call('GET', '/api/me', token=token)[0] == 200, 'and this device is still signed in (403, not 401)')
ok(login(NEW)[0] == 401 and login(OLD)[0] == 200, 'and the password is unchanged')
s, m = call('POST', '/api/me/password', {'current': OLD, 'password': 'short'}, token)
ok(s == 400, f'the right current password with a new one that is too short: 400 ({m.get("message")})')
s, m = call('POST', '/api/me/password', {'current': OLD, 'password': NEW}, token)
ok(s == 200, 'the right current password: changed')
ok(login(OLD)[0] == 401 and login(NEW)[0] == 200, 'the new password signs in and the old one does not')
ok(call('GET', '/api/me', token=token)[0] == 200, 'this device stays signed in')
s, m = call('POST', '/api/me/password', {'current': NEW, 'password': OLD}, token)
ok(s == 200 and login(OLD)[0] == 200, 'changed back to the original')
codes = [call('POST', '/api/me/password', {'current': 'guess-%d' % i, 'password': NEW}, token)[0] for i in range(12)]
ok(429 in codes and 200 not in codes, f'repeated wrong tries are slowed: {codes}')
ok(login(OLD)[0] == 200, 'and that does not stop the person signing in')
print('PASS' if not fails else f'{fails} FAILED'); sys.exit(1 if fails else 0)
