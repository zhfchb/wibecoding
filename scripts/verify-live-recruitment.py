"""Live, paid-provider recruitment acceptance. Creates a dedicated synthetic workspace.

Run explicitly, resume the same state file after a failure; never blindly repeat paid tasks.
Credentials/state remain under ignored outputs/acceptance. Existing workspaces are untouched.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:18080')
    parser.add_argument('--resume', type=Path)
    args = parser.parse_args()
    if urlparse(args.base).hostname not in ('localhost', '127.0.0.1'):
        parser.error('Only local deployments are accepted')
    root = Path(__file__).resolve().parents[1]
    if args.resume:
        state_path = args.resume.resolve()
        state = json.loads(state_path.read_text(encoding='utf-8'))
    else:
        folder = root / 'outputs' / 'acceptance' / ('live-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
        folder.mkdir(parents=True)
        state_path = folder / 'state.json'
        state = dict(base=args.base, prefix='live' + datetime.now().strftime('%m%d%H%M%S'), password=secrets.token_urlsafe(18), results={})
    base = state['base']
    if urlparse(base).hostname not in ('localhost', '127.0.0.1'):
        parser.error('Resume state must target a local deployment')
    tokens = {}
    results = state['results']

    def save():
        state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')

    def request(role, method, path, body=None, expected=(200,), content_type='application/json'):
        headers = {'Content-Type': content_type}
        if role:
            headers['Authorization'] = 'Bearer ' + tokens[role]
        data = body if isinstance(body, bytes) else (json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None)
        req = urllib.request.Request(base + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                code, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            code, raw = error.code, error.read()
        if code not in expected:
            raise RuntimeError('%s %s returned %s: %s' % (method, path, code, raw.decode('utf-8', 'replace')[:300]))
        return json.loads(raw) if raw else None

    def once(key, work):
        if key not in results:
            print('Running ' + key, flush=True)
            results[key] = work()
            save()
            print('PASS ' + key, flush=True)
        return results[key]

    def check(condition, message):
        if not condition:
            raise RuntimeError(message)
        return True

    def poll(role, path):
        deadline = time.monotonic() + 660
        previous = None
        while time.monotonic() < deadline:
            task = request(role, 'GET', path)
            status = task['status']
            if status != previous:
                print('Task ' + status, flush=True)
                previous = status
            if status == 'SUCCEEDED':
                return task
            if status in ('FAILED', 'CANCELLED', 'TIMED_OUT'):
                raise RuntimeError('Task failed: ' + str(task.get('errorCode')) + ' ' + str(task.get('errorMessage')))
            time.sleep(3)
        raise RuntimeError('Task still active; resume this state, do not resubmit')

    save()
    print('STATE ' + str(state_path), flush=True)
    try:
        def register():
            account = request(None, 'POST', '/auth/register', dict(username=state['prefix'] + '_owner', password=state['password']))
            return dict(userId=account['userId'], username=account['username'])
        once('owner', register)
        tokens['owner'] = request(None, 'POST', '/auth/login', dict(username=results['owner']['username'], password=state['password']))['token']
        once('company', lambda: request('owner', 'GET', '/company'))
        def company_profile():
            catalog = request('owner', 'GET', '/company/options')
            province = catalog['regions'][0]
            city = province['children'][0]
            return request('owner', 'PUT', '/company', dict(name='[真实链路验收]虚构技术公司', description='专用虚构验收资料，不是真实企业或候选人。', profile=dict(industry=catalog['industries'][0], scale=catalog['scales'][0], provinceCode=province['value'], cityCode=city['value'])))
        once('company_profile', company_profile)
        for role in ('editor', 'viewer', 'interviewer'):
            once(role, lambda role=role: request('owner', 'POST', '/company/members', dict(username=state['prefix'] + '_' + role, password=state['password'], role='INTERVIEWER' if role == 'interviewer' else 'HR')))
            login = request(None, 'POST', '/auth/login', dict(username=results[role]['username'], password=state['password']))
            tokens[role] = login['token']
            check(login['companyRole'] == ('INTERVIEWER' if role == 'interviewer' else 'HR'), 'Company role mismatch')
        department = once('department', lambda: request('owner', 'POST', '/company/departments', dict(name='验收研发部', description='仅用于全流程验收')))
        job = once('position', lambda: request('owner', 'POST', '/recruitment/positions', dict(title='[真实链路验收]Java后端工程师', departmentId=department['id'], city='北京', salaryMin=15, salaryMax=25, requiredSkills=['Java', 'Spring Boot', 'MySQL'], preferredSkills=['Redis'], minYears=3, education='本科', description='开发Java后端接口，使用Spring Boot、MySQL与Redis。此岗位为虚构验收数据。')))
        job_path = '/recruitment/positions/' + str(job['id'])
        for role in ('editor', 'viewer'):
            once('grant_' + role, lambda role=role: request('owner', 'PUT', job_path + '/collaborators', dict(userId=results[role]['userId'], permission='EDITOR' if role == 'editor' else 'VIEWER')))
        def upload():
            existing = request('owner', 'GET', job_path + '/resumes')
            if existing:
                check(len(existing) == 1, 'Unexpected dedicated workspace contents')
                return existing[0]
            text = '''# 虚构验收简历：林验收
姓名：林验收（虚构人物）；学历：本科，计算机科学；Java后端工作经验4年。
曾在虚构星河软件公司负责订单系统，使用Java、Spring Boot、MyBatis、MySQL、Redis。
实现订单查询接口、索引优化、缓存失效处理，编写单元测试并参与接口联调。
无真实个人联系方式。本文件仅供AI招聘系统端到端功能验收。
'''
            boundary = 'live' + secrets.token_hex(12)
            body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="files"; filename="live-resume.md"\r\nContent-Type: text/markdown\r\n\r\n' + text + '\r\n--' + boundary + '--\r\n').encode('utf-8')
            uploaded = request('owner', 'POST', job_path + '/resumes', body, content_type='multipart/form-data; boundary=' + boundary)
            check(uploaded['successCount'] == 1, 'Resume parsing did not succeed: ' + json.dumps(uploaded, ensure_ascii=False))
            return request('owner', 'GET', job_path + '/resumes')[0]
        resume = once('resume', upload)
        check(resume['parseStatus'] == 'PARSED', 'Resume must be genuinely parsed')
        resume_path = '/recruitment/resumes/' + str(resume['id'])
        assignment = once('assignment', lambda: request('editor', 'POST', resume_path + '/interviews', dict(interviewerId=results['interviewer']['userId'])))
        assignment_path = '/recruitment/interviews/' + str(assignment['id'])
        def schedule():
            now = datetime.now(timezone(timedelta(hours=8)))
            start = now.replace(second=0, microsecond=0) + timedelta(minutes=1)
            return request('editor', 'PUT', assignment_path + '/schedule', dict(version=0, localStart=start.strftime('%Y-%m-%dT%H:%M:00'), localEnd=(start+timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:00'), timeZone='Asia/Shanghai', mode='VIDEO', location='虚构验收会议，不向外发送邀请'))
        once('schedule', schedule)
        def task(kind):
            if kind == 'match':
                submitted = once('match_task', lambda: request('editor', 'POST', job_path + '/match/tasks', dict(requestKey=state['prefix'] + '-match'), expected=(202,)))
                return poll('editor', '/recruitment/match/tasks/' + submitted['id'])
            submitted = once(kind + '_task', lambda: request('editor', 'POST', resume_path + '/materials/' + kind + '/tasks', dict(requestKey=state['prefix'] + '-' + kind), expected=(202,)))
            return poll('editor', '/recruitment/materials/tasks/' + submitted['id'])
        once('match_done', lambda: task('match'))
        def matching():
            rows = request('editor', 'GET', job_path + '/match/results')
            check(len(rows) == 1 and rows[0]['scoreDetail']['llmScore'] is not None, 'Missing real semantic score')
            check(results['match_done']['degradedCount'] == 0, 'Matching used fallback')
            return rows[0]
        match = once('match_result', matching)
        once('screening', lambda: request('editor', 'POST', '/recruitment/match/' + str(match['id']) + '/decision?approved=true'))
        once('QUESTIONS_done', lambda: task('QUESTIONS'))
        questions = once('questions', lambda: request('editor', 'GET', resume_path + '/questions'))
        check(len(questions) >= 6 and all(q['question'] and q['referenceAnswer'] for q in questions), 'Incomplete interview questions')
        once('REPORT_done', lambda: task('REPORT'))
        report = once('report', lambda: request('editor', 'GET', resume_path + '/report'))
        check(bool(report['summary']) and bool(report['reportMd']), 'Incomplete generated report')
        def rag():
            result = request('owner', 'POST', '/chat', dict(message='仅根据已上传的虚构简历，林验收有哪些后端技术经验？', sessionId=state['prefix'] + '-rag'))
            check(bool(result.get('sources')), 'RAG returned no source')
            return result
        once('rag', rag)
        evaluation_path = '/interviews/assignments/' + str(assignment['id']) + '/evaluation'
        once('evaluation_draft', lambda: request('interviewer', 'PUT', evaluation_path, dict(version=0, score=4, recommendation='PASS', summary='虚构验收草稿，非真实人员评价', submit=False)))
        def boundaries():
            managed = request('editor', 'GET', job_path + '/interviews')
            check(managed[0]['evaluation'] is None, 'Draft leaked to recruiter')
            request('viewer', 'POST', resume_path + '/outcome', dict(version=0, action='DECIDE', outcome='HIRED', reason='越权验证'), expected=(404,))
            request('interviewer', 'GET', job_path + '/progress', expected=(404,))
            request(None, 'GET', job_path + '/progress', expected=(401,))
            return True
        once('role_boundaries', boundaries)
        end = datetime.fromisoformat(results['schedule']['endAt'].replace('Z', '+00:00'))
        while datetime.now(timezone.utc) <= end:
            remaining = int((end-datetime.now(timezone.utc)).total_seconds())
            print('Waiting for real interview end: %ss' % remaining, flush=True)
            time.sleep(min(20, max(1, remaining+1)))
        once('evaluation_submitted', lambda: request('interviewer', 'PUT', evaluation_path, dict(version=1, score=4, recommendation='PASS', summary='虚构流程验收：已验证评价提交与状态联动，不代表真实面试', submit=True)))
        outcome_path = resume_path + '/outcome'
        once('hired', lambda: request('editor', 'POST', outcome_path, dict(version=0, action='DECIDE', outcome='HIRED', reason='虚构验收人工记录，不代表真实录用')))
        once('archived', lambda: request('owner', 'POST', outcome_path, dict(version=1, action='ARCHIVE', reason='虚构验收流程完成')))
        once('progress', lambda: request('viewer', 'GET', job_path + '/progress'))
        check(results['progress'][0]['outcome'] == 'HIRED' and results['progress'][0]['archived'] and results['progress'][0]['submittedEvaluations'] == 1, 'Progress did not reflect completed workflow')
        once('history', lambda: request('viewer', 'GET', outcome_path + '/history'))
        check(len(results['history']) == 2, 'Outcome history missing')
        once('reopened', lambda: request('editor', 'POST', outcome_path, dict(version=2, action='REOPEN', reason='虚构验收：验证重新开启记录')))
        once('rearchived', lambda: request('owner', 'POST', outcome_path, dict(version=3, action='ARCHIVE', reason='虚构验收：复核后再次归档')))
        once('final_history', lambda: request('viewer', 'GET', outcome_path + '/history'))
        check(len(results['final_history']) == 4, 'Reopen/archive history missing')
        state['passed'] = True
        state.pop('lastError', None)
        save()
        print('PASS live recruitment workflow; synthetic data retained for inspection', flush=True)
    except Exception as error:
        state['passed'] = False
        state['lastError'] = str(error)
        save()
        print('FAIL ' + str(error), flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
