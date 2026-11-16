"""Exercise new workflow APIs/UI only on the marked synthetic interview demo."""
import json
import os
from pathlib import Path
import re
import runpy
from datetime import datetime, timedelta
from playwright.sync_api import sync_playwright, expect

root=Path(__file__).resolve().parents[1]
fixture=runpy.run_path(str(root/'scripts/seed-interview-demo.py'))
api=fixture['api']
folder=root/'outputs/acceptance/interview-demo'
state=json.loads((folder/'state.json').read_text(encoding='utf-8'))
auth=api('POST','/auth/login',payload=dict(username=os.environ.get('DEMO_USERNAME','admin'),password=os.environ.get('DEMO_PASSWORD','admin123')))
token=auth['token']; pid=state['positionId']
position=api('GET',f'/recruitment/positions/{pid}',token)
assert fixture['MARKER'] in position['description']
first=state['assignments'][0]; third=state['assignments'][2]
followup='【演示预设】已沟通岗位意向，等待确认下一次面试时间；此记录仅用于练习，不代表真实联系。'
summary='【演示预设】首轮评价已提交，拟安排第2轮继续核实职责；仅展示汇总入口，不代表真实面试结论。'
for candidate,kind,content,key in [(first,'FOLLOWUP',followup,'workflow_demo_followup_v1'),(third,'REVIEW_SUMMARY',summary,'workflow_demo_summary_v1')]:
    body=dict(kind=kind,content=content,nextContactAt=None,requestKey=key)
    for _ in range(2):api('POST',f'/recruitment/resumes/{candidate["resumeId"]}/notes',token,body)
    records=api('GET',f'/recruitment/resumes/{candidate["resumeId"]}/notes',token)['records']
    assert sum(row['content']==content for row in records)==1
second=api('POST',f'/recruitment/resumes/{third["resumeId"]}/interviews',token,dict(interviewerId=api('POST','/auth/login',payload=dict(username=state['username'],password=state['password']))['userId'],roundNo=2))
assert second['id']!=third['assignmentId'] and second['roundNo']==2
if not second.get('schedule'):
    start=(datetime.now()+timedelta(days=2)).replace(hour=16,minute=0,second=0,microsecond=0)
    api('PUT',f'/recruitment/interviews/{second["id"]}/schedule',token,dict(version=0,localStart=start.isoformat(),localEnd=(start+timedelta(minutes=45)).isoformat(),timeZone='Asia/Shanghai',mode='VIDEO',location='演示第2轮会议（虚构，不发送通知）',reason='工作流学习样例'))
managed=api('GET',f'/recruitment/positions/{pid}/interviews',token)
assert next(row for row in managed if row['id']==first['assignmentId'])['evaluation'] is None
assert next(row for row in managed if row['id']==third['assignmentId'])['status']=='SUBMITTED'
with sync_playwright() as p:
    browser=p.chromium.launch(channel='chrome',headless=True)
    page=browser.new_page(viewport=dict(width=1440,height=1000))
    def login(username,password):
        page.goto('http://127.0.0.1:5173/login');page.wait_for_load_state('networkidle')
        page.locator('#username').fill(username);page.locator('#password').fill(password)
        page.get_by_role('button',name=re.compile('进入工作台')).click()
        expect(page).not_to_have_url(re.compile('/login$'))
    login(os.environ.get('DEMO_USERNAME','admin'),os.environ.get('DEMO_PASSWORD','admin123'))
    page.goto(f'http://127.0.0.1:5173/recruitment/positions/{pid}');page.wait_for_load_state('networkidle')
    expect(page.get_by_text('招聘流程 · 下一步',exact=True)).to_be_visible()
    page.get_by_role('row').filter(has_text=first['candidate']).get_by_role('button',name='跟进与汇总',exact=True).first.click()
    expect(page.get_by_role('cell',name=followup,exact=True)).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    page.get_by_role('row').filter(has_text=third['candidate']).get_by_role('button',name='分配面试',exact=True).click()
    expect(page.get_by_role('cell',name='第2轮',exact=True)).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    login(state['username'],state['password'])
    expect(page.get_by_text('我的面试待办',exact=True)).to_be_visible()
    expect(page.get_by_role('cell',name='第2轮',exact=True)).to_be_visible()
    select=page.get_by_role('combobox',name='按面试任务筛选');select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible').get_by_text('待准备',exact=True).click()
    expect(page.get_by_role('cell',name='第2轮',exact=True)).to_be_visible()
    page.screenshot(path=str(folder/'hiring-workflow-live.png'),full_page=True)
    browser.close()
report=dict(passed=True,mocked=False,positionId=pid,roundTwoAssignment=second['id'],retryDuplicates=0,privateDraftPreserved=True)
(folder/'hiring-workflow-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('PASS live workflow: marked demo notes, retry deduplication, independent round two, private draft, HR/reviewer UI and task filter')
