"""Fixture-only collaboration regression; never uses real accounts or writes real business data."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173')
state = {'owner': True, 'grants': [], 'load_error': True, 'granted': True, 'requests': []}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/progress'):
        route.fulfill(json=[]); return
    method = route.request.method
    state['requests'].append((method, path, state['owner']))
    job = {'id': 1, 'title': '协作验收岗位', 'department': '研发部', 'status': 'OPEN', 'requiredSkills': ['Java'],
           'preferredSkills': [], 'resumeCount': 1, 'canManage': state['owner']}
    if not state['owner'] and not state['granted'] and '/recruitment/' in path:
        route.fulfill(status=404, json={'message': 'fixture revoked'}); return
    if '/collaborators' in path:
        assert state['owner']
        if method == 'GET':
            if state['load_error']:
                route.fulfill(status=503, json={'message': 'fixture load error'}); return
        elif method == 'PUT':
            assert route.request.post_data_json == {'userId': 2, 'permission': 'VIEWER'}
            state['grants'] = [{'userId': 2, 'username': 'HR只读成员'}]
        elif method == 'DELETE':
            assert path.endswith('/2'); state['grants'] = []
        else:
            raise AssertionError((method, path))
        route.fulfill(json=state['grants']); return
    assert method == 'GET', (method, path)
    if path.endswith('/company/members'):
        data = [{'userId': 1, 'username': '岗位创建人'}, {'userId': 2, 'username': 'HR只读成员'}]
    elif path.endswith('/positions'):
        data = [job]
    elif path.endswith('/positions/1'):
        data = job
    elif path.endswith('/resumes'):
        data = [{'id': 10, 'candidateName': '授权候选人', 'skills': ['Java'], 'parseStatus': 'PARSED', 'retryable': True}]
    elif path.endswith('/match/results'):
        data = [{'id': 20, 'resumeId': 10, 'candidateName': '授权候选人', 'matchScore': 88, 'rankNo': 1, 'hrDecision': 'PENDING'}]
    elif path.endswith('/questions'):
        data = [{'id': 1, 'question': '已有面试题正文', 'category': '技术', 'difficulty': 'EASY'}]
    elif path.endswith('/report'):
        data = {'id': 1, 'summary': '已有报告摘要', 'reportMd': '已有评估报告正文', 'marketRefs': []}
    elif path.endswith('/latest'):
        assert state['owner'], 'read-only viewer must not poll task APIs'
        route.fulfill(status=200, body='null', content_type='application/json'); return
    else:
        raise AssertionError((method, path))
    route.fulfill(json=data)


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'岗位创建人',role:'USER'}))")
    page.route('**/api/v1/**', api)
    page.goto(BASE + '/recruitment/positions/1'); page.wait_for_load_state('networkidle')
    page.get_by_role('button', name='协作授权', exact=True).click()
    expect(page.get_by_text('授权列表加载失败', exact=True)).to_be_visible()
    state['load_error'] = False
    page.get_by_role('button', name='重试加载', exact=True).click()
    expect(page.get_by_text('尚未授权其他成员', exact=True)).to_be_visible()
    page.get_by_role('combobox', name='只读协作成员').click()
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='HR只读成员').click()
    page.get_by_role('button', name='添加协作成员', exact=True).click()
    expect(page.get_by_role('cell', name='HR只读成员', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='添加协作成员', exact=True)).to_be_disabled()
    page.get_by_role('button', name=re.compile('^撤\s*销$')).click()
    page.get_by_role('button', name='撤销授权', exact=True).click()
    expect(page.get_by_text('尚未授权其他成员', exact=True)).to_be_visible()
    page.get_by_role('button', name='Close', exact=True).click()
    state['owner'] = False
    page.goto(BASE + '/recruitment'); page.wait_for_load_state('networkidle')
    expect(page.get_by_text('只读协作', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name=re.compile('删除岗位'))).to_have_count(0)
    expect(page.get_by_role('button', name=re.compile('^关\s*闭$'))).to_have_count(0)
    page.get_by_role('button', name='查看候选人', exact=True).click(); page.wait_for_load_state('networkidle')
    expect(page.get_by_text('只读协作岗位', exact=True)).to_be_visible()
    for label in ['上传简历', '执行人岗匹配', '协作授权', '关联部门', '更换部门', '通过', '淘汰', '重新解析']:
        expect(page.get_by_role('button', name=label, exact=True)).to_have_count(0)
    page.get_by_role('button', name='面试题', exact=True).click()
    expect(page.get_by_text('已有面试题正文', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='重新生成面试题', exact=True)).to_have_count(0)
    page.locator('.ant-drawer-open .ant-drawer-close').click()
    page.get_by_role('button', name='评估报告', exact=True).click()
    expect(page.get_by_text('已有评估报告正文', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='重新生成报告', exact=True)).to_have_count(0)
    page.locator('.ant-drawer-open .ant-drawer-close').click()
    expect(page.get_by_text('已有评估报告正文', exact=True)).not_to_be_visible()
    page.set_viewport_size({'width': 390, 'height': 844})
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    page.screenshot(path='outputs/ui-review/collaboration-reader-mobile.png', full_page=True)
    state['granted'] = False
    page.get_by_role('button', name=re.compile('刷新$')).last.click()
    expect(page.get_by_text('岗位不可访问或授权已撤销', exact=True)).to_be_visible()
    expect(page.get_by_text('授权候选人', exact=True)).to_have_count(0)
    assert not any('/tasks' in path for method, path, owner in state['requests'] if not owner)
    assert not errors, errors
    browser.close()
print('PASS owner grant/revoke/retry, reader list and artifacts, hidden writes, no task polling, revocation clears page, mobile layout')
