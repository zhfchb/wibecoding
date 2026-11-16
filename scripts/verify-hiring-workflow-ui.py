"""Fixture-only workflow/notes/rounds regression; no real API writes or models."""
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

scope = {}
exec(Path('scripts/verify-outcome-ui.py').read_text(encoding='utf-8').split('with sync_playwright() as p:')[0], scope)
fallback = scope['handler']
state = dict(role='OWNER', notes=[], keys=[], dropped=False, assignments=[])

def handler(route):
    path = urlparse(route.request.url).path
    method = route.request.method
    if path.endswith('/positions/1'):
        route.fulfill(json=dict(id=1, title='流程练习岗位', status='CLOSED' if state.get('closed') else 'OPEN', canManage=state['role']=='OWNER', canOperate=state['role']!='VIEWER', requiredSkills=['Java'], preferredSkills=[]))
    elif path.endswith('/positions/1/interviews'):
        route.fulfill(json=state['assignments'])
    elif path.endswith('/company/members'):
        route.fulfill(json=[dict(userId=2,username='演示面试官',role='INTERVIEWER')])
    elif path.endswith('/resumes/10/interviews'):
        body=route.request.post_data_json
        assert body['roundNo']==2 and body['interviewerId']==2
        state['assignments'].append(dict(id=7,resumeId=10,interviewerId=2,roundNo=2,status='ASSIGNED',username='演示面试官',evaluation=None))
        route.fulfill(json=state['assignments'][-1])
    elif path.endswith('/notes'):
        if method=='POST':
            body=route.request.post_data_json
            assert state['role']!='VIEWER'
            assert body['kind']!='REVIEW_SUMMARY' or state['role']=='OWNER'
            if body['requestKey'] not in state['keys']:
                state['keys'].append(body['requestKey'])
                state['notes'].insert(0,dict(id=len(state['notes'])+1,username='演示HR',createdAt='2026-11-16T08:00:00Z',**body))
            if not state['dropped']:
                state['dropped']=True;route.abort('connectionreset');return
            route.fulfill(status=204)
        else:
            route.fulfill(json=dict(records=state['notes'],total=len(state['notes']),current=1,size=10))
    else:
        fallback(route)

with sync_playwright() as p:
    browser=p.chromium.launch(channel='chrome',headless=True)
    page=browser.new_page(viewport=dict(width=1440,height=1000))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'fixture',role:'USER',companyRole:'ADMIN'}))")
    page.route('**/api/v1/**',handler)
    url=os.environ.get('POSITION_UI_URL','http://127.0.0.1:5173')+'/recruitment/positions/1'
    page.goto(url);page.wait_for_load_state('networkidle')
    expect(page.get_by_text('招聘流程 · 下一步',exact=True)).to_be_visible()
    expect(page.get_by_text('简历已收录，下一步执行人岗匹配，再由HR核实证据并作初筛决定。',exact=True)).to_be_visible()
    page.get_by_role('button',name='跟进与汇总',exact=True).click()
    field=page.get_by_role('textbox',name='候选人跟进内容')
    field.fill('已沟通岗位意向，等待确认面试时间。')
    page.get_by_role('button',name='保存候选人记录',exact=True).click()
    expect(page.get_by_role('button',name='重试确认保存',exact=True)).to_be_visible()
    expect(field).to_be_disabled()
    page.get_by_role('button',name='重试确认保存',exact=True).click()
    expect(page.get_by_role('cell',name='已沟通岗位意向，等待确认面试时间。',exact=True)).to_be_visible()
    assert len(state['notes'])==1
    select=page.get_by_role('combobox',name='候选人记录类型')
    expect(select).to_be_enabled()
    page.screenshot(path='outputs/ui-review/hiring-note-before-select.png',full_page=True)
    select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible').get_by_text('负责人汇总意见',exact=True).click()
    field.fill('已提交意见存在分歧，需补充核实职责。')
    page.get_by_role('button',name='保存候选人记录',exact=True).click()
    expect(page.get_by_role('cell',name='负责人汇总意见',exact=True)).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    page.reload();page.wait_for_load_state('networkidle')
    page.get_by_role('button',name='跟进与汇总',exact=True).click()
    expect(page.get_by_role('cell',name='负责人汇总意见',exact=True)).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    page.get_by_role('button',name='分配面试',exact=True).click()
    select=page.get_by_role('combobox',name='面试轮次');select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible').get_by_text('第2轮',exact=True).click()
    select=page.get_by_role('combobox',name='选择面试官');select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible').get_by_text('演示面试官',exact=True).click()
    page.get_by_role('button',name='确认分配',exact=True).click()
    expect(page.get_by_role('cell',name='第2轮',exact=True)).to_be_visible()
    page.locator('.ant-modal:visible .ant-modal-close').click()
    state['closed']=True;page.reload();page.wait_for_load_state('networkidle')
    expect(page.get_by_text('岗位已关闭。可核对历史评价、补充跟进并整理招聘结果；新增或修改面试排期前请核对岗位状态。',exact=True)).to_be_visible()
    state['closed']=False
    state['role']='VIEWER';page.reload();page.wait_for_load_state('networkidle')
    page.get_by_role('button',name='跟进与汇总',exact=True).click()
    expect(page.get_by_role('cell',name='负责人汇总意见',exact=True)).to_be_visible()
    expect(page.get_by_role('button',name='保存候选人记录',exact=True)).to_have_count(0)
    page.locator('.ant-modal:visible .ant-modal-close').click()
    state['role']='EDITOR';page.reload();page.wait_for_load_state('networkidle')
    page.get_by_role('button',name='跟进与汇总',exact=True).click()
    page.get_by_role('combobox',name='候选人记录类型').press('ArrowDown')
    expect(page.locator('.ant-select-dropdown:visible').get_by_text('负责人汇总意见',exact=True)).to_have_count(0)
    page.keyboard.press('Escape')
    expect(page.locator('.ant-select-dropdown:visible')).to_have_count(0)
    page.set_viewport_size(dict(width=390,height=844))
    page.wait_for_function('document.documentElement.scrollWidth <= innerWidth')
    page.screenshot(path='outputs/ui-review/hiring-notes-mobile.png',full_page=True)
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), page.evaluate('Array.from(document.querySelectorAll("body *")).filter(el=>el.getBoundingClientRect().right>innerWidth+2).slice(-12).map(el=>({tag:el.tagName,cls:el.className,right:el.getBoundingClientRect().right}))')
    browser.close()
print('PASS workflow hint, second-round assignment, followup retry deduplication, owner summary, reload, viewer/editor controls, mobile')
