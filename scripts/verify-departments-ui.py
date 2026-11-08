"""Fixture-only department regression; Playwright + Chrome, Vite on port 5188."""
import re
import unicodedata
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

state = {'role': 'ADMIN', 'rows': [], 'failure': False}


def normalize(name):
    return ' '.join(unicodedata.normalize('NFKC', name).split()).lower()


def api(route):
    path = urlparse(route.request.url).path
    method = route.request.method
    if path.endswith('/company'):
        data = {'id': 1, 'name': '验收公司', 'memberRole': state['role']}
    elif '/company/departments' in path:
        if method == 'GET':
            if state['failure']:
                route.fulfill(status=503, json={'message': 'fixture unavailable'})
                return
            data = state['rows']
        elif path.endswith('/status'):
            item = next(row for row in state['rows'] if row['id'] == int(path.split('/')[-2]))
            item['status'] = 'ACTIVE' if route.request.post_data_json['enabled'] else 'DISABLED'
            data = item
        else:
            payload = route.request.post_data_json
            item_id = int(path.split('/')[-1]) if method == 'PUT' else None
            if any(normalize(row['name']) == normalize(payload['name']) and row['id'] != item_id for row in state['rows']):
                route.fulfill(status=400, json={'message': '本公司已存在同名部门（含已停用部门）'})
                return
            if item_id:
                data = next(row for row in state['rows'] if row['id'] == item_id)
                data.update(payload)
            else:
                data = dict(payload, id=len(state['rows']) + 1, status='ACTIVE')
                state['rows'].append(data)
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
    page.goto('http://127.0.0.1:5188/company/departments')
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('menuitem', name='部门管理', exact=True)).to_be_visible()
    expect(page.get_by_text('暂无部门，点击右上角新增部门', exact=True)).to_be_visible()
    page.get_by_role('button', name='新增部门', exact=True).click()
    page.get_by_label('部门名称', exact=True).fill('研发部')
    page.get_by_label('部门说明（选填）').fill('业务研发')
    page.get_by_role('button', name='保存部门', exact=True).click()
    row = page.get_by_role('row').filter(has_text='研发部')
    expect(row).to_be_visible()
    row.get_by_role('button', name=re.compile('^停\s*用$')).click()
    page.get_by_role('button', name='确认修改', exact=True).click()
    expect(row.get_by_text('已停用', exact=True)).to_be_visible()
    page.get_by_role('button', name='新增部门', exact=True).click()
    page.get_by_label('部门名称', exact=True).fill(' 研发部 ')
    page.get_by_role('button', name='保存部门', exact=True).click()
    expect(page.get_by_text('本公司已存在同名部门', exact=False)).to_be_visible()
    assert len(state['rows']) == 1
    page.get_by_role('dialog').get_by_role('button', name=re.compile('^取\s*消$')).click()
    row.get_by_role('button', name=re.compile('^编\s*辑$')).click()
    page.get_by_label('部门名称', exact=True).fill('平台研发部')
    page.get_by_role('button', name='保存部门', exact=True).click()
    row = page.get_by_role('row').filter(has_text='平台研发部')
    expect(row).to_be_visible()
    row.get_by_role('button', name=re.compile('^启\s*用$')).click()
    page.get_by_role('button', name='确认修改', exact=True).click()
    expect(row.get_by_text('启用中', exact=True)).to_be_visible()
    page.get_by_label('搜索部门', exact=True).fill('不存在')
    expect(page.get_by_text('没有符合条件的部门，请调整筛选', exact=True)).to_be_visible()
    page.get_by_label('搜索部门', exact=True).fill('')
    state['failure'] = True
    page.get_by_role('button', name='刷新部门', exact=True).click()
    expect(page.get_by_text('部门加载失败，已有数据未清除，请重试', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='新增部门', exact=True)).to_be_disabled()
    state['failure'] = False
    page.get_by_role('button', name='重试加载', exact=True).click()
    expect(row).to_be_visible()
    page.set_viewport_size({'width': 390, 'height': 844})
    page.wait_for_timeout(200)
    assert page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth')
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    page.screenshot(path='outputs/ui-review/departments-mobile.png', full_page=True)
    state['role'] = 'HR'
    page.reload()
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('button', name='新增部门', exact=True)).not_to_be_visible()
    expect(page.get_by_role('button', name=re.compile('^编\s*辑$'))).not_to_be_visible()
    expect(page.get_by_text('平台研发部', exact=True)).to_be_visible()
    assert not errors, errors
    browser.close()
print('PASS department fixture: menu, empty state, create/edit/disable/enable, disabled-name duplicate, search, load failure recovery, mobile, HR read-only')
