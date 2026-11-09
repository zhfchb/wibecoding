"""Live synthetic document deletion check; exercises real embeddings/Qdrant, not model generation.

Requires an existing live acceptance state and local Docker stack. Only its own temporary
document and cleanup row are changed; the global vector service is never stopped.
"""
import argparse
import json
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
from urllib.parse import urlparse


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state',required=True,type=Path)
    args=parser.parse_args()
    state=json.loads(args.state.read_text(encoding='utf-8'));base=state['base']
    if urlparse(base).hostname not in ('127.0.0.1','localhost'):parser.error('Local deployment only')
    root=Path(__file__).resolve().parents[1]
    match=re.search(r'^AI_SERVICE_TOKEN=(.+)$',(root/'.env.local').read_text(encoding='utf-8'),re.M)
    if not match:parser.error('Internal service token is not configured')
    internal_token=match[1].strip().strip('"').strip("'")
    def request(method,path,body=None,token=None,content_type='application/json',internal=False):
        headers={'Content-Type':content_type}
        if token:headers['Authorization']='Bearer '+token
        if internal:headers['X-AI-Service-Token']=internal_token
        data=body if isinstance(body,bytes) else json.dumps(body).encode('utf-8') if body is not None else None
        with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=120) as response:
            raw=response.read();return json.loads(raw) if raw else None
    login=request('POST','/api/v1/auth/login',dict(username=state['results']['owner']['username'],password=state['password']))
    token=login['token'];owner=login['userId']
    def sql(query):
        result=subprocess.run(['docker','exec','-i','wibecoding-local-mysql-1','mysql','-uroot','-plocal-root-password','-N','-B','agent'],input=query.encode(),capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError('MySQL probe failed')
        return result.stdout.decode().strip()
    def qdrant(method,path,body):
        program='import json,sys,urllib.request; p=json.load(sys.stdin); req=urllib.request.Request("http://qdrant:6333"+p["path"],data=json.dumps(p["body"]).encode(),headers={"Content-Type":"application/json"},method=p["method"]); print(json.dumps(json.load(urllib.request.urlopen(req,timeout=20))))'
        result=subprocess.run(['docker','exec','-i','wibecoding-local-ai-service-1','python','-c',program],input=json.dumps(dict(method=method,path=path,body=body)).encode(),capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError('Qdrant probe failed')
        return json.loads(result.stdout)['result']
    text='Temporary synthetic storage consistency probe '+secrets.token_hex(8)+'. No real candidate data.'
    boundary='probe'+secrets.token_hex(12)
    body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="storage-probe.md"\r\nContent-Type: text/markdown\r\n\r\n'+text+'\r\n--'+boundary+'--\r\n').encode()
    uploaded=request('POST','/api/v1/knowledge/upload',body,token,'multipart/form-data; boundary='+boundary)
    doc=uploaded['documentId'];assert re.fullmatch(r'[0-9a-f-]{36}',doc)
    filter_=dict(must=[dict(key='document_id',match=dict(value=doc))])
    def points():return qdrant('POST','/collections/knowledge/points/scroll',dict(filter=filter_,limit=100,with_payload=True,with_vector=True))['points']
    original=points();assert original and all(p['payload']['user_id']==owner for p in original)
    deleted=False
    try:
        request('DELETE','/api/v1/knowledge/documents/'+doc,token=token);deleted=True
        # Delay only this fixture row and retain/reinsert its own points to model physical cleanup lag.
        sql("INSERT INTO document_vector_cleanup(document_id,state,next_attempt,attempts) VALUES ('"+doc+"','PENDING',DATE_ADD(NOW(),INTERVAL 2 MINUTE),0) ON DUPLICATE KEY UPDATE state='PENDING',next_attempt=VALUES(next_attempt),lease_token=NULL")
        qdrant('PUT','/collections/knowledge/points?wait=true',dict(points=[dict(id=p['id'],vector=p['vector'],payload=p['payload']) for p in original]))
        remaining=len(points());assert remaining>0
        matches=request('POST','/internal/v1/retrieve',dict(query=text,ownerId=owner),internal=True)
        assert all(m['documentId']!=doc for m in matches),'Deleted document leaked to retrieval'
        assert sql("SELECT deleted FROM document WHERE document_id='"+doc+"'")=='1'
        assert sql("SELECT COUNT(*) FROM document_chunk WHERE document_id='"+doc+"'")=='0'
        sql("UPDATE document_vector_cleanup SET next_attempt=NOW() WHERE document_id='"+doc+"'")
        for attempt in range(15):
            if not points() and sql("SELECT COUNT(*) FROM document_vector_cleanup WHERE document_id='"+doc+"'")=='0':break
            time.sleep(1)
        else:raise RuntimeError('Physical cleanup did not finish in time')
        report=dict(documentId=doc,onlySyntheticFixtureChanged=True,physicalPointsPresentDuringCheck=remaining,deletedDocumentExcludedFromRealRetrieval=True,chunkRowsRemoved=True,physicalCleanupCompleted=True,outboxDrained=True)
        (args.state.parent/'storage-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('PASS real MySQL/Qdrant: deleted text excluded while vectors existed; background physical cleanup completed')
    finally:
        if deleted:sql("UPDATE document_vector_cleanup SET next_attempt=NOW() WHERE document_id='"+doc+"'")


if __name__=='__main__':main()
