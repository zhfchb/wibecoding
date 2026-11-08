"""Fixture-only outcome workflow; no real users, records or model calls."""
import json
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

state = dict(version=0, outcome='PENDING', archived=False)
events = []
conflict = [True]

def handler(route):
    path = urlparse(route.request.url).path
    if path.endswith('/outcome/history'):
        route.fulfill(json=events)
    elif path.endswith('/outcome'):
        if route.request.method == 'POST':
            body = route.request.post_data_json
            if conflict[0]:
                conflict[0] = False
                route.fulfill(status=409, json=dict(message='结果版本变化')); return
            assert body['version'] == state['version'] and body['reason'].strip()
            state['version'] += 1
            if body['action'] == 'DECIDE': state['outcome'] = body['outcome']
            else: state['archived'] = body['action'] == 'ARCHIVE'
            events.insert(0, dict(state, action=body['action'], reason=body['reason'], username='fixture', changedAt='2030-01-01T00:00:00'))
        route.fulfill(json=state)
    elif path.endswith('/positions/1'):
        route.fulfill(json=dict(id=1, title='结果验证岗位', status='OPEN', canManage=True, canOperate=True, requiredSkills=[], preferredSkills=[]))
    elif path.endswith('/resumes'):
        route.fulfill(json=[dict(id=10, candidateName='候选人甲', parseStatus='PARSED', skills=[])])
    elif path.endswith('/latest'):
        route.fulfill(body='null', content_type='application/json')
    elif path.endswith('/match/results'):
        route.fulfill(json=[])
    else:
        route.fulfill(json=[])

with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport=dict(width=1440, height=1000))
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'fixture',role:'USER',companyRole:'ADMIN'}))")
    page.route('**/api/v1/**', handler)
    page.goto('http://127.0.0.1:5173/recruitment/positions/1')
    page.wait_for_load_state('networkidle')
    page.get_by_role('button', name='招聘结果', exact=True).click()
    expect(page.get_by_role('button', name='归档结果记录')).to_be_disabled()
    page.locator('.ant-modal:visible .ant-select-selector').click()
    page.locator('.ant-select-dropdown:visible').get_by_text('录用', exact=True).click()
    reason = page.get_by_role('textbox', name='招聘结果操作原因')
    reason.fill('人工确认录用')
    page.get_by_role('button', name='保存招聘结果').click()
    expect(page.get_by_role('button', name='刷新结果')).to_be_visible()
    expect(reason).to_have_value('人工确认录用')
    page.get_by_role('button', name='刷新结果').click()
    page.locator('.ant-modal:visible .ant-select-selector').click()
    page.locator('.ant-select-dropdown:visible').get_by_text('录用', exact=True).click()
    reason.fill('再次核对后录用')
    page.get_by_role('button', name='保存招聘结果').click()
    expect(page.get_by_text('版本 1', exact=True)).to_be_visible()
    reason.fill('流程结束')
    page.get_by_role('button', name='归档结果记录').click()
    expect(page.get_by_role('button', name='重新开启记录')).to_be_visible()
    reason.fill('重新复核')
    page.get_by_role('button', name='重新开启记录').click()
    expect(page.get_by_text('版本 3', exact=True)).to_be_visible()
    page.set_viewport_size(dict(width=390, height=844))
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    page.screenshot(path='outputs/ui-review/outcome-mobile.png', full_page=True)
    assert not errors, errors
    browser.close()
print('PASS outcome UI: conflict preserves input, decision, archive, reopen, history, mobile')
