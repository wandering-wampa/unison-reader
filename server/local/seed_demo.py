"""Gives the demo library's `admin` a shelf, places in a few books and a year of made-up reading and listening,
so the shelf, series and year pages have something to show. Run it once the demo server (Start-LocalServer.ps1
-Demo) has finished syncing; running it again changes nothing that is already there.

    python server/local/seed_demo.py http://localhost:8099 build/demo-server"""
import datetime, json, os, random, sys, urllib.request

base, root = sys.argv[1].rstrip('/'), sys.argv[2]
creds = dict(l.split() for l in open(os.path.join(root, 'credentials.txt')) if l.strip())


def call(method, path, body=None, token=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode())
    request.add_header('Content-Type', 'application/json')
    request.add_header('X-Device', 'demo-seed')
    if token: request.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(request, timeout=30) as r:
        return json.load(r)


token = call('POST', '/api/login', {'name': 'admin', 'password': creds['admin'], 'device': 'demo seed'})['token']
works = {w['work']['title']: w['work'] for w in call('GET', '/api/works', token=token)}
day = lambda y, m, d, h=20: int(datetime.datetime(y, m, d, h).timestamp() * 1000)
today = datetime.date.today()
year = today.year

FINISHED = [('The War of the Worlds', (year, 5, 28)), ('Frankenstein', (year, 8, 30)), ('A Study in Scarlet', (year, 10, 19))]
READING = [('The Time Machine', 0.0), ('The Sign of the Four', 0.22), ('Dracula', 0.38), ('Pride and Prejudice', 0.61), ("Alice's Adventures in Wonderland", 0.12), ('Treasure Island', 0.74)]
for title, when in FINISHED:
    call('PUT', f"/api/me/shelf/{works[title]['id']}", {'state': 'finished', 'finishedAt': day(*when)}, token)
for title, through in READING:
    work = works[title]
    call('PUT', f"/api/me/shelf/{work['id']}", {'state': 'reading'}, token)
    if through > 0:
        call('PUT', f"/api/works/{work['id']}/position", {'passage': int(work['passages'] * through), 'audio': 0}, token)

# A year of sessions, the same every run: which book was open in which months, and whether it has audio to listen to.
SEASONS = [((1, 1), 'Treasure Island'), ((3, 10), 'The War of the Worlds'), ((6, 2), 'Frankenstein'), ((9, 4), 'A Study in Scarlet'),
           ((10, 20), 'Pride and Prejudice'), ((11, 3), 'Dracula'), ((11, 12), 'The Time Machine')]
rng, sessions, d = random.Random(7), [], datetime.date(year, 1, 5)
while d <= today:
    recent = (today - d).days < 9
    if recent or rng.random() < 0.55:
        title = [t for (m, dd), t in SEASONS if (d.month, d.day) >= (m, dd)][-1]
        for _ in range(2 if rng.random() < 0.25 else 1):
            work = works[title]
            listen = bool(work.get('audioDir')) and rng.random() < 0.6
            minutes = rng.randint(12, 65)
            started = int(datetime.datetime(d.year, d.month, d.day, rng.choice([7, 12, 18, 21, 21, 22]), rng.randint(0, 59)).timestamp() * 1000)
            sessions.append({'work': work['id'], 'kind': 'listen' if listen else 'read', 'started': started, 'ended': started + minutes * 60_000,
                             'sentences': minutes * 9, 'pages': 0 if listen else int(minutes * 0.8)})
    d += datetime.timedelta(days=1)
print(call('POST', '/api/me/sessions', sessions, token)['message'], 'of', len(sessions), 'sessions;', len(FINISHED), 'finished,', len(READING), 'being read')
