"""Fixture-only regression. Requires running Vite; does not write to real APIs."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173')
state = {'load_error': True, 'bind_error': True, 'empty': False, 'writes': []}
position = {'id': 1, 'canManage': True, 'title': '历史招聘岗位', 'department': '历史事业部', 'status': 'OPEN',
            'requiredSkills': ['Java'], 'preferredSkills': [], 'resumeCount': 0}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/company/departments'):
        if state['load_error']:
            route.fulfill(status=503, json={'message': 'fixture unavailable'})
        else:
            route.fulfill(json=[] if state['empty'] else [{'id': 7, 'name': '正式研发部', 'status': 'ACTIVE'}])
    elif path.endswith('/positions/1/department'):
        assert route.request.method == 'PUT'
        body = route.request.post_data_json
        state['writes'].append(body)
        assert body == {'departmentId': 7}, body
        if state['bind_error']:
            route.fulfill(status=400, json={'message': '部门已停用，请刷新后重试'})
        else:
            position.update(departmentId=7, department='正式研发部', departmentStatus='ACTIVE', companyId=1)
            route.fulfill(json=position)
    elif path.endswith('/positions/1'):
        route.fulfill(json=position)
    elif path.endswith('/latest'):
        route.fulfill(status=200, body='null', content_type='application/json')
    else:
        assert route.request.method == 'GET', (path, route.request.method)
        route.fulfill(json=[])


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'HR测试',role:'USER'}))")
    page.route('**/api/v1/**', api)
    page.goto(BASE + '/recruitment/positions/1')
    page.wait_for_load_state('networkidle')
    expect(page.get_by_text('待关联', exact=True)).to_be_visible()
    page.get_by_role('button', name='关联部门', exact=True).click()
    expect(page.get_by_text('部门加载失败', exact=True)).to_be_visible()
    expect(page.locator('#departmentId')).to_be_disabled()
    state['load_error'] = False
    page.get_by_role('button', name=re.compile('^重\s*试$')).click()
    expect(page.locator('#departmentId')).to_be_enabled()
    page.locator('#departmentId').click()
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='正式研发部').click()
    page.get_by_role('button', name='确认关联', exact=True).click()
    expect(page.get_by_text('部门已停用，请刷新后重试', exact=True)).to_be_visible()
    expect(page.get_by_role('dialog')).to_be_visible()
    expect(page.get_by_text('待关联', exact=True)).to_be_visible()
    state['bind_error'] = False
    page.get_by_role('button', name='确认关联', exact=True).click()
    expect(page.get_by_role('dialog')).not_to_be_visible()
    expect(page.locator('.ant-descriptions-item-content').filter(has_text='正式研发部')).to_be_visible()
    expect(page.get_by_role('button', name='更换部门', exact=True)).to_be_visible()
    assert len(state['writes']) == 2
    position['departmentStatus'] = 'DISABLED'
    state['empty'] = True
    page.reload(); page.wait_for_load_state('networkidle')
    expect(page.get_by_text('部门已停用或不可用', exact=True)).to_be_visible()
    page.get_by_role('button', name='更换部门', exact=True).click()
    expect(page.get_by_text('暂无已启用部门，请联系公司管理员维护。', exact=True)).to_be_visible()
    page.get_by_role('button', name='确认关联', exact=True).click()
    expect(page.get_by_text('请选择已启用的公司部门', exact=True)).to_be_visible()
    assert len(state['writes']) == 2
    page.get_by_role('button', name=re.compile('^取\s*消$')).click()
    page.set_viewport_size({'width': 390, 'height': 844})
    page.get_by_role('button', name='更换部门', exact=True).click()
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    page.screenshot(path='outputs/ui-review/position-department-mobile.png', full_page=True)
    assert not errors, errors
    browser.close()
print('PASS legacy manual binding, load/retry, server rejection preserving original, ID payload, disabled status, no-active-department validation, mobile dialog')
