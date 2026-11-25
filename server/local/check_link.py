"""Exercises Wear OS sign-in (a device signed in by a code, DeviceLinks) against the local server (Start-LocalServer.ps1).
python server/local/check_link.py [base] ; prints PASS or the first thing that was wrong."""
import json, os, sys, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8099'
ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'build', 'local-server')
creds = dict(l.split() for l in open(os.path.join(ROOT, 'credentials.txt')).read().splitlines() if l.strip())
checks = [0]

def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header('Content-Type', 'application/json'); req.add_header('X-Device', 'link-check')
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

# ---- the watch asks for a code, without a sign-in
s, a = call('POST', '/api/link/start', {'device': 'Check Watch'})
ok(s == 200 and len(a['code']) == 6 and a['code'].isdigit() and len(a['secret']) == 64 and a['poll'] >= 1 and a['expires'] > 0, 'a watch is given a six-digit code and a secret')
code, secret = a['code'], a['secret']
ok(call('POST', '/api/link/claim', {'secret': secret}) == (200, {'status': 'waiting', 'token': None, 'user': None}), 'nobody approved yet: waiting')
ok(call('POST', '/api/link/claim', {'secret': 'f' * 64})[0] == 410, 'a secret the server never gave is gone')
ok(call('POST', '/api/link/claim', {'secret': code})[0] == 410, 'the code is not the secret')

# ---- the signed-in side
ok(call('GET', '/api/me/link/' + code)[0] == 401, 'looking a code up needs a sign-in')
ok(call('POST', '/api/me/link/' + code)[0] == 401, 'approving a code needs a sign-in')
s, info = call('GET', '/api/me/link/' + code, token=reader)
ok(s == 200 and info['device'] == 'Check Watch' and info['address'] and info['created'] > 0, 'the person is shown which device asked')
ok(call('POST', '/api/link/claim', {'secret': secret})[1]['status'] == 'waiting', 'looking is not approving')
wrong = '%06d' % ((int(code) + 1) % 1000000)
s, m = call('GET', '/api/me/link/' + wrong, token=reader)
ok(s == 404 and 'code' in m['message'], 'a wrong code is not found, and says so')
s, info = call('POST', '/api/me/link/' + code[:3] + '%20' + code[3:], token=reader)
ok(s == 200 and info['device'] == 'Check Watch', 'approved (the space the watch shows in the code is allowed)')
ok(call('GET', '/api/me/link/' + code, token=admin)[0] == 404, 'an approved code is nobody else\'s to approve')

# ---- the watch collects its own token, once
s, a = call('POST', '/api/link/claim', {'secret': secret})
ok(s == 200 and a['status'] == 'approved' and len(a['token']) == 64 and a['user']['name'] == 'reader', 'the watch is given a token for the person who approved')
watch = a['token']
ok(call('POST', '/api/link/claim', {'secret': secret})[0] == 410, 'the code is spent')
s, me = call('GET', '/api/me', token=watch)
ok(s == 200 and me['user']['name'] == 'reader' and any(d['device'] == 'Check Watch' for d in me['devices']), 'the token works and the watch is in the device list')
ok(watch != reader, 'the watch has its own token')
s, audit = call('GET', '/api/users/audit', token=admin)
ok(s == 200 and any(e['action'] == 'sign-in code approved' and e['user'] == 'reader' and 'Check Watch' in e['detail'] for e in audit), 'the approval is in the activity log')
ok(any(e['action'] == 'signed in' and e['detail'] == 'by code' and e['user'] == 'reader' for e in audit), 'so is the sign-in by code')

# ---- signing the watch out from the list
prefix = next(d['token'] for d in me['devices'] if d['device'] == 'Check Watch')
ok(call('DELETE', '/api/me/tokens/' + prefix[:8], token=reader)[0] == 200, 'signed out from the device list')
ok(call('GET', '/api/me', token=watch)[0] == 401, 'the watch\'s token is gone')

# ---- a name the device sends is cleaned; no name is a name
s, a = call('POST', '/api/link/start', {'device': ' \n\t '})
ok(s == 200 and call('GET', '/api/me/link/' + a['code'], token=admin)[1]['device'] == 'Watch', 'a blank device name becomes Watch')
s, b = call('POST', '/api/link/start', {'device': 'x' * 200})
ok(s == 200 and len(call('GET', '/api/me/link/' + b['code'], token=admin)[1]['device']) == 60, 'a long device name is cut at sixty')
ok(a['code'] != b['code'], 'two waiting codes differ')

# ---- only so many codes wait from one address (five): three are waiting now (two just made, none collected)
made = [call('POST', '/api/link/start', {'device': 'Flood'})[0] for _ in range(6)]
ok(made.count(200) == 3 and made[-1] == 429, 'the sixth waiting code from one address is refused: ' + str(made))

# ---- wrong codes are slowed like wrong passwords: five misses, then a wait
states = [call('GET', '/api/me/link/000000', token=reader)[0] for _ in range(6)]
ok(states[:5] == [404] * 5 and states[5] == 429, 'the sixth wrong code in a row must wait: ' + str(states))
ok(call('GET', '/api/me/link/' + a['code'], token=admin)[0] == 200, 'another person is not slowed by it')

print(f'PASS ({checks[0]} checks). The reader account is slowed for thirty seconds, and five codes wait from this address for ten minutes: restart the server before running this again.')
