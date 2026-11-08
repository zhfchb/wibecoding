"""Fixture-only editor workflow regression. Uses Vite, never real accounts/models/data."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173')
state = {'role': 'OWNER', 'grants': [], 'match': None, 'parse': None, 'material': None, 'decision': 'PENDING', 'writes': [], 'activity_error': True}


def task(kind, actor=2):
    return {'id': kind + '-task', 'positionId': 1, 'resumeId': 10, 'actorId': actor, 'canCancel': actor == 2,
            'status': 'QUEUED', 'phase': 'QUEUED', 'kind': 'QUESTIONS', 'total': 1, 'totalBatches': 1, 'completedBatches': 0,
            'candidateCount': 1, 'succeeded': 0, 'failed': 0, 'cancelled': 0, 'items': [{'resumeId': 11, 'candidateName': '待解析候选人', 'status': 'QUEUED'}]}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/progress'):
        route.fulfill(json=[]); return
    method = route.request.method
    if method != 'GET':
        state['writes'].append((method, path, route.request.post_data_json if method in ('POST', 'PUT') else None))
    role = state['role']
    if path.endswith('/collaborators'):
        assert role == 'OWNER'
        if method == 'PUT':
            body = route.request.post_data_json
            assert body['userId'] == 2 and body['permission'] in ('VIEWER', 'EDITOR')
            state['grants'] = [{'userId': 2, 'username': 'HR协作成员', 'permission': body['permission']}]
        route.fulfill(json=state['grants']); return
    if path.endswith('/company/members'):
        route.fulfill(json=[{'userId': 1, 'username': '创建人'}, {'userId': 2, 'username': 'HR协作成员'}]); return
    if path.endswith('/activity'):
        assert role in ('OWNER', 'EDITOR')
        if state['activity_error']:
            route.fulfill(status=503, json={'message': 'fixture unavailable'}); return
        route.fulfill(json=[{'id': 1, 'actorId': 2, 'username': 'HR协作成员', 'action': 'MATCH_SUBMITTED', 'createdAt': '2026-11-08T10:00:00'}]); return
    match_task = '/match/tasks' in path
    parse_task = '/parse-tasks' in path
    material_task = '/materials/' in path
    if match_task or parse_task or material_task:
        if role == 'VIEWER':
            route.fulfill(status=404, json={'message': 'fixture downgraded'}); return
        kind = 'match' if match_task else 'parse' if parse_task else 'material'
        if method == 'POST' and path.endswith('/cancel'):
            assert state[kind]['canCancel']
            state[kind]['status'] = 'CANCELLED'
        elif method == 'POST':
            assert role == 'EDITOR'
            state[kind] = task(kind)
            if kind == 'parse':
                assert route.request.post_data_json['resumeIds'] == [11]
        route.fulfill(status=200, json=state[kind]); return
    if '/decision' in path:
        assert role == 'EDITOR'
        state['decision'] = 'APPROVED'
        route.fulfill(json={}); return
    assert method == 'GET', (method, path)
    if path.endswith('/positions/1'):
        value = {'id': 1, 'title': '招聘协作验收', 'canManage': role == 'OWNER', 'canOperate': role in ('OWNER', 'EDITOR'),
                 'status': 'OPEN', 'requiredSkills': ['Java'], 'preferredSkills': []}
    elif path.endswith('/match/results'):
        value = [{'id': 20, 'resumeId': 10, 'candidateName': '候选人甲', 'rankNo': 1, 'matchScore': 88, 'hrDecision': state['decision']}]
    elif path.endswith('/resumes'):
        value = [{'id': 10, 'candidateName': '候选人甲', 'parseStatus': 'PARSED', 'skills': ['Java']},
                 {'id': 11, 'candidateName': '待解析候选人', 'parseStatus': 'FAILED', 'retryable': True, 'skills': []}]
    elif path.endswith('/questions'):
        value = [{'id': 1, 'category': '技术', 'difficulty': 'EASY', 'question': '已有面试题'}]
    else:
        raise AssertionError((method, path))
    route.fulfill(json=value)


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'创建人',role:'USER'}))")
    page.route('**/api/v1/**', api)
    page.goto(BASE + '/recruitment/positions/1'); page.wait_for_load_state('networkidle')
    page.get_by_role('button', name='协作授权', exact=True).click()
    page.get_by_role('combobox', name='只读协作成员').click()
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='HR协作成员').click()
    page.get_by_role('combobox', name='协作权限', exact=True).press('ArrowDown')
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='招聘协作').click()
    page.get_by_role('button', name='添加协作成员', exact=True).click()
    expect(page.get_by_role('cell', name='HR协作成员', exact=True)).to_be_visible()
    role_select = page.get_by_role('combobox', name='修改 HR协作成员 权限', exact=True)
    role_select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='只读协作').click()
    expect(page.locator('.ant-table').get_by_text('只读协作', exact=True)).to_be_visible()
    role_select.press('ArrowDown')
    page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter(has_text='招聘协作').click()
    page.get_by_role('button', name='Close', exact=True).click()
    state['role'] = 'EDITOR'; state['match'] = task('match', actor=1)
    page.reload(); page.wait_for_load_state('networkidle')
    expect(page.get_by_text('招聘协作岗位', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='取消匹配', exact=True)).to_have_count(0)
    for label in ['上传简历', '协作授权', '关联部门', '更换部门']:
        expect(page.get_by_role('button', name=label, exact=True)).to_have_count(0)
    state['match'] = None
    page.reload(); page.wait_for_load_state('networkidle')
    page.get_by_role('button', name=re.compile('执行人岗匹配$')).click()
    page.get_by_role('button', name='取消匹配', exact=True).click()
    page.get_by_role('button', name='取消本轮', exact=True).click()
    expect(page.get_by_text('匹配任务已取消，原结果保留', exact=True)).to_be_visible()
    page.get_by_role('button', name=re.compile('通过$')).click()
    page.locator('.ant-popconfirm').get_by_role('button', name=re.compile('确|OK')).click()
    expect(page.get_by_text('已通过', exact=True)).to_be_visible()
    page.get_by_role('button', name='重新解析', exact=True).click()
    expect(page.get_by_role('button', name='取消重新解析', exact=True)).to_be_visible()
    page.get_by_role('button', name='面试题', exact=True).first.click()
    page.get_by_role('button', name=re.compile('重新生成面试题$')).click()
    expect(page.get_by_role('button', name='取消生成', exact=True)).to_be_visible()
    page.locator('.ant-drawer-open .ant-drawer-close').click()
    page.get_by_role('button', name='操作记录', exact=True).click()
    expect(page.get_by_text('无法读取操作记录，请重试或确认协作权限', exact=True)).to_be_visible()
    state['activity_error'] = False
    page.get_by_role('button', name='刷新记录', exact=True).click()
    expect(page.get_by_role('cell', name='HR协作成员', exact=True)).to_be_visible()
    expect(page.get_by_role('cell', name='发起人岗匹配', exact=True)).to_be_visible()
    page.get_by_role('button', name='Close', exact=True).click()
    expect(page.get_by_role('dialog')).not_to_be_visible()
    page.set_viewport_size({'width': 390, 'height': 844})
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    page.screenshot(path='outputs/ui-review/operations-editor-mobile.png', full_page=True)
    assert page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'), 'Mobile overflow'
    state['role'] = 'VIEWER'
    expect(page.get_by_text('只读协作岗位', exact=True)).to_be_visible(timeout=15000)
    expect(page.get_by_role('button', name='操作记录', exact=True)).to_have_count(0)
    assert not errors, errors
    browser.close()
print('PASS role upgrades/downgrades, editor decisions and three task kinds, own-vs-other cancellation, activity retry, restricted ownership controls, live downgrade, mobile')
