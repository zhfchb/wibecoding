"""Two-session scheduling fixtures. No real accounts, bookings, notifications or model calls."""
import os
import re
import copy
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173')
future = (datetime.now(timezone.utc) + timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0, tzinfo=None)
start = future.isoformat(timespec='minutes')
end = (future + timedelta(hours=1)).isoformat(timespec='minutes')
new_start = (future + timedelta(hours=2)).isoformat(timespec='minutes')
new_end = (future + timedelta(hours=3)).isoformat(timespec='minutes')
state = {'schedule': None, 'evaluation': None, 'evaluation_version': 0, 'overlap': True, 'events': [], 'history_error': True, 'advanced': False, 'writes': []}


def utc(value):
    return datetime.fromisoformat(value).replace(tzinfo=timezone(timedelta(hours=8))).astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def assignment(owner):
    schedule = state['schedule']
    evaluation = state['evaluation']
    status = 'SUBMITTED' if evaluation and evaluation.get('submittedAt') else 'DRAFT' if evaluation else 'ASSIGNED'
    flow = 'EVALUATED' if status == 'SUBMITTED' else 'UNSCHEDULED' if not schedule else 'CANCELLED' if schedule['status'] == 'CANCELLED' else 'AWAITING_EVALUATION' if schedule['status'] == 'COMPLETED' else 'UPCOMING'
    return {'id': 7, 'positionId': 1, 'resumeId': 10, 'interviewerId': 2, 'username': '技术面试官', 'title': '排期验收岗位', 'candidateName': '候选人甲',
            'status': status, 'version': state['evaluation_version'], 'evaluation': evaluation if not owner or status == 'SUBMITTED' else None, 'schedule': schedule, 'flowStatus': flow}


def detail():
    return {'assignment': assignment(False), 'position': {'title': '排期验收岗位', 'requiredSkills': ['Java'], 'minYears': 3},
            'candidate': {'name': '候选人甲', 'skills': ['Java'], 'yearsExp': 4}, 'questions': []}


def event():
    schedule = state['schedule']
    state['events'].insert(0, {**copy.deepcopy(schedule), 'id': schedule['version'], 'actorId': 1, 'username': '招聘负责人'})


def handler(owner):
    def api(route):
        path = urlparse(route.request.url).path
        method = route.request.method
        if method != 'GET':
            state['writes'].append((path, route.request.post_data_json))
        if '/schedule/history' in path:
            assert owner
            if state['history_error']:
                route.fulfill(status=503, json={'message': 'fixture unavailable'}); return
            route.fulfill(json=state['events']); return
        if path.endswith('/schedule') or path.endswith('/schedule/state'):
            assert owner
            body = route.request.post_data_json
            if body['version'] != (state['schedule']['version'] if state['schedule'] else 0):
                route.fulfill(status=409, json={'error': 'SCHEDULE_VERSION', 'message': '排期已变化，请刷新后重新核对'}); return
            if state['overlap']:
                route.fulfill(status=409, json={'error': 'SCHEDULE_OVERLAP', 'message': '所选时间与面试官或该候选人的其他面试冲突'}); return
            version = body['version'] + 1
            if path.endswith('/state'):
                assert body['action'] in ('CANCEL', 'COMPLETE')
                if body['action'] == 'CANCEL':
                    assert body['reason']; state['schedule'].update(status='CANCELLED', reason=body['reason'], version=version)
                else:
                    assert state['advanced']; state['schedule'].update(status='COMPLETED', reason=body.get('reason'), version=version)
            else:
                assert body['timeZone'] == 'Asia/Shanghai' and body['mode'] == 'VIDEO'
                state['schedule'] = {**body, 'version': version, 'status': 'SCHEDULED', 'startAt': utc(body['localStart']), 'endAt': utc(body['localEnd']), 'updatedBy': 1, 'updatedAt': '2030-01-01T00:00:00Z'}
            event(); route.fulfill(json=state['schedule']); return
        if path.endswith('/positions/1/interviews'):
            assert owner; route.fulfill(json=[assignment(True)]); return
        if path.endswith('/company/members'):
            assert owner; route.fulfill(json=[{'userId': 1, 'username': '招聘负责人', 'role': 'ADMIN'}, {'userId': 2, 'username': '技术面试官', 'role': 'INTERVIEWER'}]); return
        if path.endswith('/interviews/assignments'):
            assert not owner; route.fulfill(json=[assignment(False)]); return
        if path.endswith('/interviews/assignments/7'):
            assert not owner; route.fulfill(json=detail()); return
        if path.endswith('/interviews/assignments/7/evaluation'):
            assert not owner
            body = route.request.post_data_json
            assert body['version'] == state['evaluation_version']
            assert not body['submit'] or state['advanced']
            state['evaluation_version'] += 1
            state['evaluation'] = {k: body.get(k) for k in ['score', 'recommendation', 'summary', 'strengths', 'concerns']}
            state['evaluation']['submittedAt'] = '2030-01-01T00:00:00' if body['submit'] else None
            route.fulfill(json=detail()); return
        assert owner and method == 'GET', (owner, method, path)
        if path.endswith('/positions/1'):
            value = {'id': 1, 'title': '排期验收岗位', 'status': 'OPEN', 'canManage': True, 'canOperate': True, 'requiredSkills': ['Java'], 'preferredSkills': []}
        elif path.endswith('/resumes'):
            value = [{'id': 10, 'candidateName': '候选人甲', 'parseStatus': 'PARSED', 'skills': ['Java']}]
        elif path.endswith('/match/results'):
            value = []
        elif path.endswith('/latest'):
            route.fulfill(body='null', content_type='application/json'); return
        else:
            raise AssertionError((method, path))
        route.fulfill(json=value)
    return api


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    manager = browser.new_page(viewport={'width': 1440, 'height': 1000})
    reviewer = browser.new_page(viewport={'width': 1440, 'height': 1000})
    errors = []
    for page, owner in [(manager, True), (reviewer, False)]:
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify(" + ("{userId:1,username:'招聘负责人',role:'USER',companyRole:'ADMIN'}" if owner else "{userId:2,username:'技术面试官',role:'USER',companyRole:'INTERVIEWER'}") + "))")
        page.route('**/api/v1/**', handler(owner))
    manager.goto(BASE + '/recruitment/positions/1'); manager.wait_for_load_state('networkidle')
    manager.get_by_role('button', name='分配面试', exact=True).click()
    manager.get_by_role('button', name='安排时间', exact=True).click()
    manager.get_by_label('开始时间', exact=True).fill(start)
    manager.get_by_label('结束时间', exact=True).fill(end)
    manager.get_by_label('地点 / 会议号 / 电话说明', exact=True).fill('视频会议123')
    manager.get_by_label('会议链接（选填）', exact=True).fill('https://meeting.example.test/room')
    manager.get_by_role('button', name='保存排期', exact=True).click()
    expect(manager.get_by_text('所选时间与面试官或该候选人的其他面试冲突', exact=True)).to_be_visible()
    expect(manager.get_by_label('开始时间', exact=True)).to_have_value(start)
    state['overlap'] = False
    manager.get_by_role('button', name='保存排期', exact=True).click()
    expect(manager.get_by_role('button', name='调整排期', exact=True)).to_be_visible()
    reviewer.goto(BASE + '/interviews'); reviewer.wait_for_load_state('networkidle')
    reviewer.get_by_role('button', name='准备面试与评价', exact=True).click()
    expect(reviewer.get_by_role('button', name='提交评价', exact=True)).to_be_disabled()
    reviewer.locator('#summary').fill('改期不能覆盖这份面试草稿')
    manager.get_by_role('button', name='调整排期', exact=True).click()
    manager.get_by_label('开始时间', exact=True).fill(new_start)
    manager.get_by_label('结束时间', exact=True).fill(new_end)
    manager.get_by_label('变更原因（改期、取消、未到场必填）', exact=True).fill('面试官时间调整')
    manager.get_by_role('button', name='保存排期', exact=True).click()
    expect(reviewer.get_by_text('变更说明：面试官时间调整', exact=True)).to_be_visible(timeout=15000)
    expect(reviewer.locator('#summary')).to_have_value('改期不能覆盖这份面试草稿')
    reviewer.get_by_role('button', name='保存草稿', exact=True).click()
    expect(reviewer.get_by_text('评价草稿已保存', exact=True)).to_be_visible()
    manager.get_by_role('button', name='调整排期', exact=True).click()
    state['schedule']['version'] += 1; state['schedule']['reason'] = '另一位负责人已核对'; event()
    manager.get_by_label('变更原因（改期、取消、未到场必填）', exact=True).fill('候选人临时有事')
    manager.get_by_role('button', name='取消排期', exact=True).click()
    expect(manager.get_by_text('排期已变化，请关闭并刷新后重新核对，当前输入尚未覆盖服务器', exact=True)).to_be_visible()
    manager.get_by_role('button', name='关闭并刷新排期', exact=True).click()
    manager.get_by_role('button', name='调整排期', exact=True).click()
    manager.get_by_label('变更原因（改期、取消、未到场必填）', exact=True).fill('候选人临时有事')
    manager.get_by_role('button', name='取消排期', exact=True).click()
    expect(reviewer.get_by_text('排期已取消或标记未到场，当前不能保存评价，已保存草稿保留', exact=True)).to_be_visible(timeout=15000)
    expect(reviewer.get_by_role('button', name='保存草稿', exact=True)).to_be_disabled()
    expect(reviewer.locator('#summary')).to_have_value('改期不能覆盖这份面试草稿')
    expect(reviewer.get_by_role('button', name='取消排期', exact=True)).to_have_count(0)
    manager.get_by_role('button', name='排期记录', exact=True).click()
    expect(manager.get_by_text('排期记录读取失败，请重试或确认权限', exact=True)).to_be_visible()
    state['history_error'] = False
    manager.get_by_role('button', name='刷新排期记录', exact=True).click()
    expect(manager.get_by_role('cell', name='候选人临时有事', exact=True)).to_be_visible()
    manager.locator('.ant-modal-close:visible').last.click()
    manager.get_by_role('button', name='调整排期', exact=True).click()
    manager.get_by_label('变更原因（改期、取消、未到场必填）', exact=True).fill('双方已确认恢复安排')
    manager.get_by_role('button', name='保存排期', exact=True).click()
    expect(reviewer.get_by_text('变更说明：双方已确认恢复安排', exact=True)).to_be_visible(timeout=15000)
    epoch = int(datetime.fromisoformat(state['schedule']['endAt'].replace('Z', '+00:00')).timestamp() * 1000) + 1000
    for page in [manager, reviewer]:
        page.evaluate('(epoch) => { Date.now = () => epoch }', epoch)
    state['advanced'] = True
    manager.get_by_role('button', name='调整排期', exact=True).click()
    manager.get_by_role('button', name='确认已完成', exact=True).click()
    expect(reviewer.get_by_role('button', name='提交评价', exact=True)).to_be_enabled(timeout=15000)
    reviewer.locator('#score').fill('4')
    reviewer.locator('#recommendation').press('ArrowDown')
    reviewer.locator('.ant-select-dropdown:visible').get_by_text('建议通过', exact=True).click()
    reviewer.get_by_role('button', name='提交评价', exact=True).click()
    expect(reviewer.get_by_text('评价已提交，不能修改', exact=True)).to_be_visible()
    reviewer.set_viewport_size({'width': 390, 'height': 844})
    Path('outputs/ui-review').mkdir(parents=True, exist_ok=True)
    reviewer.screenshot(path='outputs/ui-review/interview-schedule-mobile.png', full_page=True)
    assert reviewer.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'), 'Mobile overflow'
    assert not errors, errors
    browser.close()
print('PASS two-session scheduling: conflict preservation, explicit zone, live reschedule without draft loss, version conflict, cancel/reopen, history retry, completion/evaluation, read-only interviewer, mobile')
