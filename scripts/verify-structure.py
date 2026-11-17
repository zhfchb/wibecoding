"""Read-only Java/Python HTTP contract comparison for a structural refactor.

Requires pre-refactor baseline-api.json and baseline-python-api.json in --baseline.
Never generates content, calls models, or changes business data.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
METHODS = {'get','post','put','patch','delete','head','options','trace'}


def contracts(spec):
    def expand(value, seen=()):
        if isinstance(value, list):return [expand(item, seen) for item in value]
        if not isinstance(value, dict):return value
        if '$ref' in value and value['$ref'].startswith('#/'):
            ref=value['$ref']
            if ref in seen:return {'recursive': True}
            target=spec
            for part in ref[2:].split('/'):target=target[part.replace('~1','/').replace('~0','~')]
            return expand(target,seen+(ref,))
        return {key:expand(item,seen) for key,item in value.items()}
    return {method.upper()+' '+path: expand({key:operation[key] for key in ('parameters','requestBody','responses','security') if key in operation})
            for path,item in spec['paths'].items() for method,operation in item.items() if method in METHODS}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,default=ROOT/'outputs/acceptance/restructure')
    parser.add_argument('--java-url',default='http://127.0.0.1:18080')
    args=parser.parse_args()
    if urlparse(args.java_url).hostname not in ('localhost','127.0.0.1'):
        parser.error('Local verification only')
    with urllib.request.urlopen(args.java_url+'/v3/api-docs',timeout=20) as response:java=json.load(response)
    docker=shutil.which('docker') or r'E:\software\Docker\resources\bin\docker.exe'
    result=subprocess.run([docker,'compose','--env-file','.env.local','-f','deploy/docker-compose.local.yml','exec','-T','ai-service',
        'python','-c','import json; from app.main import app; print(json.dumps(app.openapi()))'],cwd=ROOT,capture_output=True,check=True)
    python=json.loads(result.stdout)
    report={'passed':True,'readOnly':True,'modelCalls':0,'services':{}}
    for name,current,filename in [('java',java,'baseline-api.json'),('python',python,'baseline-python-api.json')]:
        before=contracts(json.loads((args.baseline/filename).read_text(encoding='utf-8')))
        after=contracts(current)
        changed=sorted(key for key in set(before)|set(after) if before.get(key)!=after.get(key))
        report['services'][name]={'operations':len(after),'changedContracts':changed}
        report['passed'] &= not changed
        (args.baseline/('after-'+name+'-api.json')).write_text(json.dumps(current,ensure_ascii=False,indent=2),encoding='utf-8')
    (args.baseline/'contract-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))
    if not report['passed']:raise SystemExit(1)


if __name__=='__main__':main()
