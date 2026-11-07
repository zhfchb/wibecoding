"""Import explicitly synthetic fixtures into the local demo stack.

Uses the business API for positions and direct fixture inserts for resumes.
No embeddings, AI parsing, candidate decisions or fabricated match scores.
Run: python scripts/seed-demo-data.py
"""
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get('DEMO_API_URL', 'http://127.0.0.1:18080/api/v1')


def api(path, payload=None, token=None):
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, headers=headers,
        data=json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def sql_text(value):
    if value is None:
        return 'NULL'
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def main():
    fixtures = json.loads((ROOT / 'demo-data/dataset.json').read_text(encoding='utf-8'))
    assert fixtures['kind'] == 'SYNTHETIC_DEMO'
    auth = api('/auth/login', {'username': os.environ.get('DEMO_USERNAME', 'admin'),
        'password': os.environ.get('DEMO_PASSWORD', 'admin123')})
    token = auth['token']
    owner = int(auth['userId'])
    existing = {p['title']: p for p in api('/recruitment/positions', token=token)}
    statements = ['SET NAMES utf8mb4;', 'START TRANSACTION;']
    for job in fixtures['positions']:
        payload = job['payload']
        assert '[演示]' in payload['title']
        position = existing.get(payload['title'])
        if position is None:
            position = api('/recruitment/positions', payload, token)
            existing[payload['title']] = position
            print('Created', job['code'], position['id'])
        pid = int(position['id'])
        for candidate_id in job['candidateIds']:
            c = fixtures['candidates'][candidate_id]
            assert c['kind'] == 'SYNTHETIC_DEMO'
            years = int(c['yearsExp'])
            name = c['candidateName']
            values = [str(pid), str(owner), sql_text(name),
                sql_text(json.dumps(c['skills'], ensure_ascii=False)), str(years),
                sql_text(c['education']), sql_text(c['lastCompany']), sql_text(c['rawText']),
                "'PARSED'", sql_text('DEMO_FIXTURE: manually structured synthetic data; no AI parse or vector indexing')]
            statements.append('INSERT INTO candidate_resume '
                '(position_id,user_id,candidate_name,skills,years_exp,education,last_company,raw_text,parse_status,parse_error) '
                'SELECT ' + ','.join(values) + ' WHERE NOT EXISTS '
                f'(SELECT 1 FROM candidate_resume WHERE position_id={pid} AND user_id={owner} AND candidate_name={sql_text(name)});')
    statements.append('COMMIT;')
    # Only inserts synthetic records; real resumes never enter this fixture path.
    result = subprocess.run(['docker', 'compose', '--env-file', '.env.local', '-f',
        'deploy/docker-compose.local.yml', 'exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -uagent -D agent'],
        input='\n'.join(statements).encode(), cwd=ROOT, capture_output=True)
    if result.returncode:
        raise RuntimeError('Fixture insert failed: ' + result.stderr.decode(errors='replace')[:300])
    positions = api('/recruitment/positions', token=token)
    demo = [p for p in positions if p['title'] in {j['payload']['title'] for j in fixtures['positions']}]
    print(json.dumps({'demoPositions': len(demo), 'resumeRecords': sum(p['resumeCount'] for p in demo)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
