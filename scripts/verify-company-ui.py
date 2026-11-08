"""Company foundation fixture regression. Vite on 5188; Python Playwright and Chrome required."""
from playwright.sync_api import sync_playwright, expect
from urllib.parse import urlparse
import re

state = {'company': {'id': 1, 'name': '验收公司', 'city': '上海', 'description': '简介', 'memberRole': 'ADMIN'},
         'members': [{'userId': 1, 'username': 'owner', 'role': 'ADMIN'}], 'writes': 0}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/members'):
        if route.request.method == 'POST':
            data = {'userId': 2, 'username': route.request.post_data_json['username'], 'role': 'HR'}
            state['members'].append(data)
            state['writes'] += 1
        else:
            data = state['members']
    elif path.endswith('/company'):
        if route.request.method == 'PUT':
            state['company'].update(route.request.post_data_json)
            state['writes'] += 1
        data = state['company']
    else:
        raise AssertionError(path)
    route.fulfill(json=data)


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'owner',role:'USER'}));")
    page.route('**/api/v1/**', api)
    page.goto('http://127.0.0.1:5188/company/profile')
    page.wait_for_load_state('networkidle')
    page.get_by_label('公司名称').fill('更新公司')
    page.get_by_role('button', name='保存公司资料', exact=True).click()
    expect(page.get_by_text('公司资料已保存', exact=True)).to_be_visible()
    assert state['company']['name'] == '更新公司'
    page.get_by_role('menuitem', name='成员管理', exact=True).click()
    page.get_by_role('button', name='新增HR成员', exact=True).click()
    page.get_by_label('账号', exact=True).fill('test_hr')
    page.get_by_label('初始密码', exact=True).fill('fixture-password')
    page.get_by_role('button', name=re.compile('^创\s*建$')).click()
    expect(page.get_by_role('cell', name='test_hr', exact=True)).to_be_visible()
    state['company']['memberRole'] = 'HR'
    page.reload()
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('button', name='新增HR成员', exact=True)).not_to_be_visible()
    page.get_by_role('menuitem', name='公司资料', exact=True).click()
    expect(page.get_by_label('公司名称')).to_be_disabled()
    assert state['writes'] == 2
    assert not errors, errors
    browser.close()
print('PASS company fixture: menu, profile save, HR account creation, HR read-only controls')
