"""Repeatable local acceptance; never starts production services or calls real AI providers."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
from urllib.parse import urlparse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-browser', action='store_true', help='Run backend and frontend build only')
    parser.add_argument('--ui-url', default='http://127.0.0.1:5173', help='Existing local Vite server for fixture browser tests')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    ai_python = root / 'ai-service' / '.venv' / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    mvn, npm = shutil.which('mvn'), shutil.which('npm')
    if not mvn or not npm or not ai_python.is_file():
        parser.error('Requires existing Maven, npm and ai-service/.venv Python; this script does not install dependencies.')
    if not args.skip_browser:
        if urlparse(args.ui_url).hostname not in ('127.0.0.1', 'localhost', '::1'):
            parser.error('--ui-url must point to localhost')
        try:
            with urllib.request.urlopen(args.ui_url, timeout=5) as response:
                if response.status != 200:
                    raise ValueError('UI returned non-200')
        except Exception:
            parser.error('Start the existing frontend Vite server first, or use --skip-browser.')
    destination = root / 'outputs' / 'acceptance' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    destination.mkdir(parents=True)
    env = dict(os.environ, RUN_AI_E2E='true', AI_TEST_PYTHON=str(ai_python), POSITION_UI_URL=args.ui_url)
    steps = [('backend', [mvn, 'clean', 'verify', '-q'], root), ('frontend-build', [npm, 'run', 'build'], root / 'frontend')]
    if not args.skip_browser:
        steps.extend((name, [sys.executable, str(root / 'scripts' / script)], root) for name, script in [
            ('progress-browser', 'verify-progress-ui.py'), ('outcome-browser', 'verify-outcome-ui.py'),
            ('collaboration-browser', 'verify-position-operations-ui.py')])
    report = dict(scope='H2/hash vectors + simulated AI + fixture browser APIs; no production acceptance claim', steps=[])
    failed = False
    for name, command, cwd in steps:
        print('Running ' + name, flush=True)
        log = destination / (name + '.log')
        with log.open('wb') as output:
            result = subprocess.run(command, cwd=str(cwd), env=env, stdout=output, stderr=subprocess.STDOUT)
        report['steps'].append(dict(name=name, exitCode=result.returncode, log=str(log)))
        if result.returncode:
            failed = True
            break
        if name == 'backend':
            totals = dict(tests=0, failures=0, errors=0, skipped=0)
            for xml_file in (root / 'target' / 'surefire-reports').glob('TEST-*.xml'):
                suite = ET.parse(xml_file).getroot()
                for key in totals:
                    totals[key] += int(suite.get(key, '0'))
            report['backendTests'] = totals
    report['passed'] = not failed
    (destination / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(('FAIL ' if failed else 'PASS ') + str(destination / 'report.json'), flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
