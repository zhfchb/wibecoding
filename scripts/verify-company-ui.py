"""Company foundation fixture regression. Vite on 5188; Python Playwright and Chrome required."""
from playwright.sync_api import sync_playwright, expect
from urllib.parse import urlparse
import re
import os
import json
from pathlib import Path

state = {'company': {'id': 1, 'name': '验收公司', 'city': '上海', 'description': '简介', 'memberRole': 'ADMIN', 'needsCompletion': True, 'profile': {}},
         'members': [{'userId': 1, 'username': 'owner', 'role': 'ADMIN'}], 'writes': 0}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/options'):
        data = {'regions': json.loads(Path('src/main/resources/company-regions.json').read_text(encoding='utf-8')), 'industries': ['互联网与软件', '其他'], 'scales': ['1～20人', '21～99人'], 'natures': ['民营企业']}
    elif path.endswith('/members'):
        if route.request.method == 'POST':
            data = {'userId': len(state['members']) + 1, 'username': route.request.post_data_json['username'], 'role': route.request.post_data_json.get('role', 'HR')}
            state['members'].append(data)
            state['writes'] += 1
        else:
            data = state['members']
    elif path.endswith('/company'):
        if route.request.method == 'PUT':
            state['company'].update(route.request.post_data_json)
            state['writes'] += 1
            state['company']['needsCompletion'] = False
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
    page.goto(os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173') + '/company/profile')
    page.wait_for_load_state('networkidle')
    page.get_by_label('公司全称').fill('更新公司')
    expect(page.get_by_text('公司资料待完善', exact=False)).to_be_visible()
    def choose(field, label):
        try:
            page.locator('#' + field).locator('xpath=ancestor::div[contains(@class,"ant-select-selector")]').click(timeout=5000)
        except Exception:
            page.screenshot(path='outputs/ui-review/company-profile-debug.png', full_page=True)
            print(page.locator('body').inner_text())
            raise
        if page.locator('#' + field).get_attribute('readonly') is None:
            page.locator('#' + field).fill(label)
        list_id = page.locator('#' + field).get_attribute('aria-controls')
        popup = page.locator('#' + list_id).locator('xpath=ancestor::div[contains(@class,"ant-select-dropdown")]')
        popup.locator('.ant-select-item-option-content').filter(has_text=re.compile('^' + re.escape(label) + '$')).click()
    choose('profile_industry', '互联网与软件')
    choose('profile_scale', '21～99人')
    choose('profile_provinceCode', '浙江省')
    choose('profile_cityCode', '杭州市')
    choose('profile_provinceCode', '上海市')
    expect(page.locator('#profile_cityCode')).to_have_value('')
    expect(page.get_by_text('杭州市', exact=True)).not_to_be_visible()
    choose('profile_cityCode', '上海市')
    page.get_by_label('招聘联系邮箱（选填）').fill('invalid-email')
    page.get_by_role('button', name='保存公司资料', exact=True).click()
    expect(page.get_by_text('请输入有效邮箱', exact=True)).to_be_visible()
    assert state['writes'] == 0
    page.get_by_label('招聘联系邮箱（选填）').fill('hr@example.com')
    page.get_by_role('button', name='保存公司资料', exact=True).click()
    expect(page.get_by_text('公司资料已保存', exact=True)).to_be_visible()
    assert state['company']['name'] == '更新公司'
    assert state['company']['profile']['provinceCode'] == '310000'
    assert state['company']['profile']['cityCode'] == '310000'
    page.get_by_role('menuitem', name='成员管理', exact=True).click()
    page.get_by_role('button', name='新增公司成员', exact=True).click()
    page.get_by_label('账号', exact=True).fill('test_hr')
    page.get_by_label('初始密码', exact=True).fill('fixture-password')
    page.get_by_role('button', name=re.compile('^创\s*建$')).click()
    expect(page.get_by_role('cell', name='test_hr', exact=True)).to_be_visible()
    page.get_by_role('button', name='新增公司成员', exact=True).click()
    page.get_by_label('公司角色', exact=True).press('ArrowDown')
    page.locator('.ant-select-dropdown:visible').get_by_text('面试官', exact=True).click()
    page.get_by_label('账号', exact=True).fill('test_interviewer')
    page.get_by_label('初始密码', exact=True).fill('fixture-password')
    page.get_by_role('button', name=re.compile('^创\s*建$')).click()
    expect(page.get_by_role('cell', name='test_interviewer', exact=True)).to_be_visible()
    state['company']['memberRole'] = 'HR'
    page.reload()
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('button', name='新增公司成员', exact=True)).not_to_be_visible()
    page.get_by_role('menuitem', name='公司资料', exact=True).click()
    expect(page.get_by_label('公司全称')).to_be_disabled()
    assert state['writes'] == 3
    assert not errors, errors
    browser.close()
print('PASS company fixture: legacy completion notice, required fields, province/city clearing, municipality, email validation, profile save, members and HR read-only controls')
