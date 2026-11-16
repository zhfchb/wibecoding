"""Unmocked browser upload of seven synthetic library documents, including failed file 04.

Creates a dedicated learning-test account; invokes real embeddings and one RAG answer.
State and generated credentials stay in ignored outputs/acceptance. Resume avoids reupload.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import secrets
import urllib.request
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',default='http://127.0.0.1:18080')
    parser.add_argument('--ui',default='http://127.0.0.1:5173')
    parser.add_argument('--resume',type=Path)
    parser.add_argument('--batch',action='store_true')
    args=parser.parse_args()
    if any(urlparse(url).hostname not in ('127.0.0.1','localhost') for url in (args.base,args.ui)):
        parser.error('Local deployment only')
    root=Path(__file__).resolve().parents[1]
    if args.resume:
        path=args.resume;state=json.loads(path.read_text(encoding='utf-8'))
    else:
        folder=root/'outputs/acceptance'/('knowledge-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        folder.mkdir(parents=True);path=folder/'state.json'
        state=dict(username='kbfix'+datetime.now().strftime('%m%d%H%M%S'),password=secrets.token_urlsafe(18),uploaded={})
    def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    save();print('STATE '+str(path),flush=True)
    def api(method,route,body=None,token=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(args.base+'/api/v1'+route,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        with urllib.request.urlopen(request,timeout=120) as response:
            raw=response.read();return json.loads(raw) if raw else None
    if not state.get('registered'):
        api('POST','/auth/register',dict(username=state['username'],password=state['password']))
        state['registered']=True;save()
    files=sorted((root/'demo-data/knowledge-base/upload').glob('*.md'));assert len(files)==7
    with sync_playwright() as p:
        browser=p.chromium.launch(channel='chrome',headless=True)
        try:
            page=browser.new_page(viewport=dict(width=1440,height=1000))
            page.goto(args.ui+'/login');page.wait_for_load_state('networkidle')
            page.locator('#username').fill(state['username']);page.locator('#password').fill(state['password'])
            page.get_by_role('button',name=re.compile('进入工作台')).click()
            expect(page).not_to_have_url(re.compile('/login$'))
            token=page.evaluate("localStorage.getItem('token')")
            page.goto(args.ui+'/knowledge');page.wait_for_load_state('networkidle')
            existing=api('GET','/knowledge/documents?page=1&size=100',token=token)['records']
            by_name={row['fileName']:row for row in existing}
            if args.batch:
                missing=[file for file in files if file.name not in by_name]
                if missing:
                    print('Uploading real batch of '+str(len(missing))+' materials',flush=True)
                    page.locator('input[type=file]').set_input_files([str(file) for file in missing])
                    expect(page.get_by_text('成功',exact=True)).to_have_count(len(missing),timeout=300000)
                existing=api('GET','/knowledge/documents?page=1&size=100',token=token)['records']
                by_name={row['fileName']:row for row in existing}
            for file in files:
                if file.name in by_name:
                    state['uploaded'][file.name]=by_name[file.name]['documentId'];save();continue
                print('Uploading material '+file.name[:2],flush=True)
                with page.expect_response(lambda response:response.url.endswith('/api/v1/knowledge/upload') and response.request.method=='POST',timeout=180000) as response_info:
                    page.locator('input[type=file]').set_input_files(str(file))
                response=response_info.value
                if response.status!=200:raise RuntimeError('Material '+file.name[:2]+' upload failed, HTTP '+str(response.status))
                result=response.json();assert result['chunkCount']>0
                expect(page.locator('.ant-card').filter(has=page.get_by_text('我的参考资料',exact=True)).get_by_role('cell',name=file.name,exact=True)).to_be_visible(timeout=15000)
                state['uploaded'][file.name]=result['documentId'];save()
                print('PASS material '+file.name[:2],flush=True)
            assert len(state['uploaded'])==7
            final=api('GET','/knowledge/documents?page=1&size=100',token=token)['records']
            assert len(final)==7 and len({row['documentId'] for row in final})==7
            page.screenshot(path=str(path.parent/'library.png'),full_page=True,animations='disabled')
            if 'rag' not in state:
                print('Checking sourced RAG answer',flush=True)
                answer=api('POST','/chat',dict(message='Java后端工程师岗位的必须技能是什么？请根据资料说明。',sessionId=state['username']+'-check'),token)
                assert answer.get('sources'),'No RAG source'
                assert any('02-' in source.get('fileName','') for source in answer['sources']),'Java job source missing'
                state['rag']=answer;save()
            if args.batch:
                session=state['username']+'-check'
                page.evaluate('(sid) => localStorage.setItem("currentSessionId",sid)',session)
                page.goto(args.ui+'/chat');page.wait_for_load_state('networkidle')
                expect(page.locator('summary').first).to_contain_text('.md')
                page.locator('summary').first.click()
                expect(page.locator('details[open] .source-preview')).not_to_be_empty()
                page.get_by_role('button',name='重命名会话',exact=True).click()
                page.get_by_role('textbox',name='会话名称').fill('真实批量上传验收')
                page.get_by_role('button',name=re.compile('保.*存')).click()
                expect(page.get_by_text('真实批量上传验收',exact=True)).to_be_visible()
                if page.get_by_role('button',name='置顶会话',exact=True).count():
                    page.get_by_role('button',name='置顶会话',exact=True).click()
                expect(page.get_by_role('button',name='取消置顶',exact=True)).to_be_visible()
                page.reload();page.wait_for_load_state('networkidle')
                expect(page.get_by_text('真实批量上传验收',exact=True)).to_be_visible()
                expect(page.get_by_role('button',name='取消置顶',exact=True)).to_be_visible()
                previous=page.locator('summary').count()
                page.get_by_placeholder('输入问题，回车发送（Shift+回车换行）').fill('Java岗位不会Redis就应该直接淘汰吗？请根据资料回答。')
                with page.expect_response(lambda r:r.url.endswith('/chat/stream'),timeout=120000):
                    page.get_by_role('button',name=re.compile('发.*送')).click()
                expect(page.get_by_role('button',name=re.compile('发.*送'))).not_to_have_class(re.compile('ant-btn-loading'),timeout=180000)
                assert page.locator('summary').count()>previous,'Stream source cards missing'
                expected=page.locator('summary').count()
                page.reload();page.wait_for_load_state('networkidle')
                expect(page.locator('summary')).to_have_count(expected)
                expect(page.get_by_text('真实批量上传验收',exact=True)).to_be_visible()
                page.screenshot(path=str(path.parent/'chat-sources-and-session.png'),full_page=True)
                state['batchAndChatUiPassed']=True;save()
            state['passed']=True;save()
            report=dict(passed=True,mocked=False,uploadedFiles=7,fourthMaterialPassed=True,noDuplicateDocuments=True,ragSources=len(state['rag']['sources']))
            report['batchAndChatUiPassed']=state.get('batchAndChatUiPassed',False)
            (path.parent/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            (path.parent/'test-account.txt').write_text('UI: '+args.ui+'\nUsername: '+state['username']+'\nPassword: '+state['password']+'\n',encoding='utf-8')
            print('PASS live library upload: all 7 materials, file 04, unique documents, sourced RAG',flush=True)
        finally:browser.close()


if __name__=='__main__':main()
