"""Read-only runtime smoke checks after reorganizing modules. Uses existing demo accounts."""
import json
import os
from pathlib import Path
import re
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
interviews=json.loads((ROOT/'outputs/acceptance/interview-demo/state.json').read_text(encoding='utf-8'))
chat=json.loads((ROOT/'outputs/acceptance/chat-title-demo/state.json').read_text(encoding='utf-8'))
BASE=os.environ.get('POSITION_UI_URL','http://127.0.0.1:5173')
from urllib.parse import urlparse
if urlparse(BASE).hostname not in ('localhost','127.0.0.1'):raise ValueError('Local verification only')
with sync_playwright() as p:
    browser=p.chromium.launch(channel='chrome',headless=True)
    page=browser.new_page(viewport=dict(width=1440,height=1000))
    page_errors=[]
    page.on('pageerror', lambda error: page_errors.append(str(error)))
    def login(username,password):
        page.goto(BASE+'/login');page.wait_for_load_state('networkidle')
        page.locator('#username').fill(username);page.locator('#password').fill(password)
        page.get_by_role('button',name=re.compile('进入工作台')).click()
        expect(page).not_to_have_url(re.compile('/login$'))
    login(os.environ.get('DEMO_USERNAME','admin'),os.environ.get('DEMO_PASSWORD','admin123'))
    page.goto(BASE+f'/recruitment/positions/{interviews["positionId"]}');page.wait_for_load_state('networkidle')
    expect(page.get_by_text('招聘流程 · 下一步',exact=True)).to_be_visible()
    row=page.get_by_role('row').filter(has_text=interviews['assignments'][0]['candidate'])
    row.get_by_role('button',name='面试题',exact=True).last.click()
    expect(page.get_by_text('【演示预设】如何排查一条慢SQL？',exact=True)).to_be_visible()
    expect(page.get_by_text('参考答案要点：',exact=True)).to_have_count(3)
    page.get_by_role('button',name='关闭窗口',exact=True).click()
    expect(page.get_by_role('dialog')).not_to_be_visible()
    row.get_by_role('button',name='跟进与汇总',exact=True).first.click()
    expect(page.get_by_text('历史记录',exact=True)).to_be_visible()
    expect(page.get_by_role('cell',name=re.compile('演示预设.*已沟通岗位意向'))).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    page.screenshot(path=str(ROOT/'outputs/acceptance/restructure/hr-page.png'),full_page=True)
    login(interviews['username'],interviews['password'])
    expect(page.get_by_text('我的面试待办',exact=True)).to_be_visible()
    expect(page.get_by_role('cell',name='第2轮',exact=True)).to_be_visible()
    login(chat['username'],chat['password'])
    page.goto(BASE+'/chat');page.wait_for_load_state('networkidle')
    expect(page.get_by_text('手工确认的招聘问题',exact=True)).to_be_visible()
    page.get_by_text('手工确认的招聘问题',exact=True).first.click()
    expect(page.get_by_role('button',name='重命名会话',exact=True).first).to_be_visible()
    page.screenshot(path=str(ROOT/'outputs/acceptance/restructure/chat-page.png'),full_page=True)
    assert not page_errors, page_errors
    browser.close()
report=dict(passed=True,mocked=False,readOnly=True,modelCalls=0,checks=['HR workflow','extracted question drawer','followup history','interviewer rounds','preserved chat title'])
(ROOT/'outputs/acceptance/restructure/runtime-ui-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('PASS read-only HR, interview and chat runtime pages; no model calls')
