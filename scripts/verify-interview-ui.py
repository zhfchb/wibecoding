"""Two-session fixture regression. Never creates real members/assignments or calls models."""
import os
import re
import copy
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173')
state = {'assignment': None, 'manager_error': True, 'writes': [], 'interviewer_requests': []}


def handler(owner):
    def api(route):
        path = urlparse(route.request.url).path
        if path.endswith('/progress'):
            route.fulfill(json=[]); return
        method = route.request.method
        if not owner:
            state['interviewer_requests'].append(path)
        if path.endswith('/auth/login'):
            route.fulfill(json={'token': 'fixture', 'userId': 2, 'username': '技术面试官', 'role': 'USER', 'companyRole': 'INTERVIEWER'}); return
        if method != 'GET':
            state['writes'].append((method, path, route.request.post_data_json))
        assignment = state['assignment']
        if path.endswith('/company/members'):
            assert owner
            route.fulfill(json=[{'userId': 1, 'username': '招聘负责人', 'role': 'ADMIN'}, {'userId': 2, 'username': '技术面试官', 'role': 'INTERVIEWER'}, {'userId': 3, 'username': '普通HR', 'role': 'HR'}]); return
        if path.endswith('/positions/1/interviews'):
            assert owner
            if state['manager_error']:
                route.fulfill(status=503, json={'message': 'fixture load failure'}); return
            rows = [copy.deepcopy(assignment)] if assignment else []
            for row in rows:
                if row['evaluation'] and not row['evaluation'].get('submittedAt'):
                    row['evaluation'] = None
            route.fulfill(json=rows); return
        if path.endswith('/resumes/10/interviews'):
            assert owner and method == 'POST'
            assert route.request.post_data_json == {'interviewerId': 2}
            state['assignment'] = {'id': 7, 'positionId': 1, 'resumeId': 10, 'interviewerId': 2, 'username': '技术面试官', 'title': 'Java面试岗位', 'candidateName': '候选人甲', 'status': 'ASSIGNED', 'version': 0, 'evaluation': None}
            route.fulfill(json=state['assignment']); return
        if path.endswith('/interviews/7/revoke'):
            assert owner and method == 'POST'; assignment['status'] = 'REVOKED'
            route.fulfill(json={}); return
        if path.endswith('/interviews/assignments'):
            assert not owner
            route.fulfill(json=[assignment] if assignment and assignment['status'] != 'REVOKED' else []); return
        if '/interviews/assignments/7' in path:
            assert not owner
            if not assignment or assignment['status'] == 'REVOKED':
                route.fulfill(status=404, json={'message': 'fixture revoked'}); return
            if method == 'PUT':
                body = route.request.post_data_json
                if assignment['version'] != body['version'] or assignment['status'] == 'SUBMITTED':
                    route.fulfill(status=409, json={'message': 'fixture conflict'}); return
                assignment['version'] += 1
                assignment['status'] = 'SUBMITTED' if body['submit'] else 'DRAFT'
                assignment['evaluation'] = {k: body.get(k) for k in ['score', 'recommendation', 'strengths', 'concerns', 'summary']}
                assignment['evaluation']['submittedAt'] = '2026-11-08T11:00:00' if body['submit'] else None
            route.fulfill(json={'assignment': assignment, 'position': {'title': 'Java面试岗位', 'requiredSkills': ['Java'], 'minYears': 3, 'education': '本科', 'description': '开发业务服务'},
                                'candidate': {'name': '候选人甲', 'skills': ['Java'], 'yearsExp': 4, 'education': '本科'},
                                'questions': [{'category': '技术', 'question': '已有Java问题', 'assessPoint': '并发基础', 'referenceAnswer': '围绕证据回答'}]}); return
        assert owner and method == 'GET', (owner, method, path)
        if path.endswith('/positions/1'):
            data = {'id': 1, 'title': 'Java面试岗位', 'status': 'OPEN', 'canManage': True, 'canOperate': True, 'requiredSkills': ['Java'], 'preferredSkills': []}
        elif path.endswith('/resumes'):
            data = [{'id': 10, 'candidateName': '候选人甲', 'skills': ['Java'], 'parseStatus': 'PARSED'}]
        elif path.endswith('/latest'):
            route.fulfill(body='null', content_type='application/json'); return
        elif path.endswith('/match/results'):
            data = []
        else:
            raise AssertionError((method, path))
        route.fulfill(json=data)
    return api


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    manager = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    manager.on('pageerror', lambda e: errors.append(str(e)))
    manager.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'招聘负责人',role:'USER',companyRole:'ADMIN'}))")
    manager.route('**/api/v1/**', handler(True))
    manager.goto(BASE + '/recruitment/positions/1'); manager.wait_for_load_state('networkidle')
    manager.get_by_role('button', name='分配面试', exact=True).click()
    expect(manager.get_by_text('面试分配加载失败', exact=True)).to_be_visible()
    state['manager_error'] = False
    manager.get_by_role('button', name='重试加载', exact=True).click()
    manager.get_by_role('combobox', name='选择面试官', exact=True).click()
    expect(manager.locator('.ant-select-dropdown:visible').get_by_text('普通HR', exact=True)).to_have_count(0)
    manager.locator('.ant-select-dropdown:visible').get_by_text('技术面试官', exact=True).click()
    manager.get_by_role('button', name='确认分配', exact=True).click()
    expect(manager.get_by_role('cell', name='技术面试官', exact=True)).to_be_visible()
    reviewer = browser.new_page(viewport={'width': 1440, 'height': 1000})
    reviewer.on('pageerror', lambda e: errors.append(str(e)))
    reviewer.route('**/api/v1/**', handler(False))
    reviewer.goto(BASE + '/login')
    reviewer.locator('#username').fill('interviewer'); reviewer.locator('#password').fill('fixture-password')
    reviewer.get_by_role('button', name=re.compile('进入工作台')).click()
    expect(reviewer).to_have_url(re.compile('/interviews$'))
    expect(reviewer.get_by_role('menuitem', name='岗位工作台', exact=True)).to_have_count(0)
    reviewer.get_by_role('button', name='准备面试与评价', exact=True).click()
    expect(reviewer.get_by_text('已有Java问题', exact=True)).to_be_visible()
    reviewer.locator('#score').fill('4')
    reviewer.locator('#recommendation').press('ArrowDown')
    reviewer.locator('.ant-select-dropdown:visible').get_by_text('建议通过', exact=True).click()
    reviewer.locator('#summary').fill('草稿中的私密判断')
    reviewer.get_by_role('button', name='保存草稿', exact=True).click()
    expect(reviewer.get_by_text('评价草稿已保存', exact=True)).to_be_visible()
    manager.get_by_role('button', name='刷新分配', exact=True).click()
    expect(manager.get_by_text('草稿仅面试官本人可见', exact=True)).to_be_visible()
    expect(manager.get_by_text('草稿中的私密判断', exact=True)).to_have_count(0)
    state['assignment']['version'] += 1
    state['assignment']['evaluation']['summary'] = '另一个窗口草稿'
    reviewer.locator('#summary').fill('本地未保存评价')
    reviewer.get_by_role('button', name='保存草稿', exact=True).click()
    expect(reviewer.get_by_text('评价已有更新，请重新加载后继续', exact=True)).to_be_visible()
    expect(reviewer.locator('#summary')).to_have_value('本地未保存评价')
    reviewer.get_by_role('button', name='重新加载', exact=True).click()
    reviewer.locator('.ant-modal-confirm').get_by_role('button', name='重新加载', exact=True).click()
    expect(reviewer.locator('#summary')).to_have_value('另一个窗口草稿')
    reviewer.locator('#summary').fill('正式面试评价，证据完整')
    reviewer.get_by_role('button', name='提交评价', exact=True).click()
    expect(reviewer.get_by_text('评价已提交，不能修改', exact=True)).to_be_visible()
    expect(reviewer.locator('#summary')).to_be_disabled()
    expect(reviewer.get_by_role('button', name='提交评价', exact=True)).to_have_count(0)
    reviewer.set_viewport_size({'width': 390, 'height': 844})
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    reviewer.screenshot(path='outputs/ui-review/interview-evaluation-mobile.png', full_page=True)
    assert reviewer.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'), 'Mobile overflow'
    manager.get_by_role('button', name='刷新分配', exact=True).click()
    manager.get_by_role('button', name='查看已提交评价', exact=True).click()
    expect(manager.get_by_text('正式面试评价，证据完整', exact=True)).to_be_visible()
    manager.locator('.ant-modal-close:visible').last.click()
    manager.get_by_role('button', name=re.compile('^撤\s*销$')).click()
    manager.get_by_role('button', name='撤销分配', exact=True).click()
    expect(reviewer.get_by_text('暂无面试安排，请联系招聘负责人分配候选人', exact=True)).to_be_visible(timeout=15000)
    expect(reviewer.get_by_role('dialog')).not_to_be_visible()
    assert not any('/recruitment/' in path for path in state['interviewer_requests']), state['interviewer_requests']
    assert not errors, errors
    browser.close()
print('PASS two-session assignment/retry, interviewer login routing, private draft, version conflict/reload, locked submission, recruiter results, revocation, mobile')
