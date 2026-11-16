"""Local MySQL/API/browser title verification using explicit synthetic histories; no AI calls."""
import json
from pathlib import Path
import re
import runpy
import secrets
import urllib.error
from urllib.parse import urlparse
import uuid
from playwright.sync_api import sync_playwright, expect

root=Path(__file__).resolve().parents[1]
helpers=runpy.run_path(str(root/'scripts/seed-interview-demo.py'))
api,sql,text=helpers['api'],helpers['sql'],helpers['text']
if urlparse(helpers['BASE']).hostname not in ('127.0.0.1','localhost'):
    raise ValueError('Title fixture verification is localhost-only')
folder=root/'outputs/acceptance/chat-title-demo'
folder.mkdir(parents=True,exist_ok=True)
path=folder/'state.json'
if path.exists():
    state=json.loads(path.read_text(encoding='utf-8'))
else:
    state=dict(username='title_'+uuid.uuid4().hex[:12],password=secrets.token_urlsafe(18))
    path.write_text(json.dumps(state),encoding='utf-8')
if not re.fullmatch(r'title_[a-f0-9]{12}',state['username']):
    raise ValueError('Refusing to seed histories into a non-demo account')
if not state.get('registered'):
    try:api('POST','/auth/register',payload=dict(username=state['username'],password=state['password']))
    except urllib.error.HTTPError as error:
        if error.code!=400:raise
    api('POST','/auth/login',payload=dict(username=state['username'],password=state['password']))
    state['registered']=True;path.write_text(json.dumps(state),encoding='utf-8')
auth=api('POST','/auth/login',payload=dict(username=state['username'],password=state['password']))
owner=int(auth['userId']);token=auth['token']
sid='title-fixture-'+uuid.uuid4().hex[:16]
question='# Java岗位\n **必须技能** 是什么？'
expected='Java岗位 必须技能 是什么？'
statement=''
for role,content in [('user',question),('assistant','SYNTHETIC_CHAT_TITLE：这是一段很长的预设回答。'*20),('user','后续问题，不应覆盖首次问题标题')]:
    statement+=f'INSERT INTO chat_history(user_id,session_id,role,content) VALUES({owner},{text(sid)},{text(role)},{text(content)});\n'
sql(statement)
def current():return next(row for row in api('GET','/chat/sessions',token) if row['sessionId']==sid)
assert current()['title']==expected
api('PATCH',f'/chat/sessions/{sid}',token,dict(pinned=True))
assert current()['title']==expected and current()['pinned']
api('PATCH',f'/chat/sessions/{sid}',token,dict(title='长'*80))
try:
    api('PATCH',f'/chat/sessions/{sid}',token,dict(title='长'*81))
    raise AssertionError('Expected 81-unit title rejection')
except urllib.error.HTTPError as error:assert error.code==400
assert current()['title']=='长'*80
with sync_playwright() as p:
    browser=p.chromium.launch(channel='chrome',headless=True)
    page=browser.new_page(viewport=dict(width=1440,height=1000))
    page.goto('http://127.0.0.1:5173/login');page.wait_for_load_state('networkidle')
    page.locator('#username').fill(state['username']);page.locator('#password').fill(state['password'])
    page.get_by_role('button',name=re.compile('进入工作台')).click()
    expect(page).not_to_have_url(re.compile('/login$'))
    page.evaluate('(sid)=>localStorage.setItem("currentSessionId",sid)',sid)
    page.goto('http://127.0.0.1:5173/chat');page.wait_for_load_state('networkidle')
    row=page.locator('.ant-list-item').filter(has_text='长'*80)
    row.get_by_role('button',name='重命名会话',exact=True).click()
    expect(page.get_by_role('textbox',name='会话名称')).to_have_value('长'*80)
    page.get_by_role('textbox',name='会话名称').fill('手工确认的招聘问题')
    page.get_by_role('button',name=re.compile('保.*存')).click()
    expect(page.get_by_text('手工确认的招聘问题',exact=True)).to_be_visible()
    sql(f'INSERT INTO chat_history(user_id,session_id,role,content) VALUES({owner},{text(sid)},\'assistant\',{text("SYNTHETIC_CHAT_TITLE：之后的回答也不覆盖手工名称")});')
    page.reload();page.wait_for_load_state('networkidle')
    expect(page.get_by_text('手工确认的招聘问题',exact=True)).to_be_visible()
    expect(page.get_by_role('button',name='取消置顶',exact=True)).to_be_visible()
    page.get_by_role('button',name=re.compile('新.*建')).click()
    expect(page.get_by_role('dialog')).not_to_be_visible()
    page.screenshot(path=str(folder/'chat-title.png'),full_page=True)
    browser.close()
report=dict(passed=True,mocked=False,sessionId=sid,localTitle=True,firstQuestionStable=True,renamePreserved=True,max80Accepted=True,max81Rejected=True,newSessionNoNamingDialog=True,modelCalls=0)
(folder/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('PASS live title rules, first question stability, pin/rename persistence, 80/81 bounds and no naming dialog; zero AI calls')
