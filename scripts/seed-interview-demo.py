"""Local synthetic interviewer exercises. No model calls or edits to real candidates.

Uses APIs for membership, position, assignments and evaluations. Explicit fixtures
insert synthetic resumes/questions and historical schedules to enable immediate practice.
Credentials/state remain in ignored outputs/acceptance/interview-demo/.
"""
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import urllib.request
from urllib.parse import urlparse
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get('DEMO_API_URL', 'http://127.0.0.1:18080/api/v1')
STATE = ROOT / 'outputs/acceptance/interview-demo/state.json'
MARKER = 'SYNTHETIC_INTERVIEW_WORKBENCH_V1'
TITLE = '[演示] 面试工作台练习专用'


def api(method, path, token=None, payload=None):
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None)
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
        return json.loads(body) if body else None


def text(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def sql(statements):
    docker = shutil.which('docker') or r'E:\software\Docker\resources\bin\docker.exe'
    result = subprocess.run([docker, 'compose', '--env-file', '.env.local', '-f',
        'deploy/docker-compose.local.yml', 'exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"'],
        cwd=ROOT, input=('SET NAMES utf8mb4; START TRANSACTION;\n' + statements + '\nCOMMIT;').encode(), capture_output=True)
    if result.returncode:
        raise RuntimeError('Synthetic fixture insertion failed; no credentials logged')


def main():
    if urlparse(BASE).hostname not in ('127.0.0.1', 'localhost'):
        raise ValueError('This script only supports localhost')
    STATE.parent.mkdir(parents=True, exist_ok=True)
    state = json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else dict(username='interview_demo', password=secrets.token_urlsafe(15))
    def save(): STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
    auth = api('POST', '/auth/login', payload=dict(username=os.environ.get('DEMO_USERNAME', 'admin'), password=os.environ.get('DEMO_PASSWORD', 'admin123')))
    owner = int(auth['userId']); token = auth['token']
    if state.get('ownerId', owner) != owner:
        raise ValueError('Demo belongs to another owner; refusing to change it')
    state['ownerId'] = owner; save()
    members = api('GET', '/company/members', token)
    member = next((m for m in members if m['username'] == state['username']), None)
    if member is None:
        member = api('POST', '/company/members', token, dict(username=state['username'], password=state['password'], role='INTERVIEWER'))
    if member['role'] != 'INTERVIEWER':
        raise ValueError('Existing account is not an interviewer')
    reviewer = api('POST', '/auth/login', payload=dict(username=state['username'], password=state['password']))
    reviewer_token = reviewer['token']; reviewer_id = int(member['userId'])
    positions = api('GET', '/recruitment/positions', token)
    position = next((p for p in positions if p['title'] == TITLE), None)
    if position is None:
        position = api('POST', '/recruitment/positions', token, dict(title=TITLE, city='杭州', salaryMin=15, salaryMax=25,
            requiredSkills=['Java', 'Spring Boot', 'MySQL'], preferredSkills=['Redis'], minYears=3, education='本科',
            description=MARKER + '：纯虚构面试练习。候选人、题目、排期与预填评价均为演示预设，不代表真实面试或AI生成结果。'))
    if MARKER not in position.get('description', ''):
        raise ValueError('Refusing to seed an unmarked position')
    pid = int(position['id']); state['positionId'] = pid; save()
    names = ['[演示] 林同学-待填写评价', '[演示] 周同学-未来面试', '[演示] 陈同学-已提交示例']
    statements = []
    for name in names:
        values = f'{pid},{owner},{text(name)},{text(json.dumps(["Java", "Spring Boot", "MySQL"], ensure_ascii=False))},3,{text("本科")},{text("虚构练习团队")},{text(MARKER + "：虚构候选人，参与订单接口和SQL排查的练习描述，实际能力待面试核实。")},\'PARSED\',{text("DEMO_FIXTURE: synthetic structured record; no AI parse or vectors")}'
        statements.append('INSERT INTO candidate_resume(position_id,user_id,candidate_name,skills,years_exp,education,last_company,raw_text,parse_status,parse_error) SELECT ' + values +
            f' WHERE NOT EXISTS(SELECT 1 FROM candidate_resume WHERE position_id={pid} AND user_id={owner} AND candidate_name={text(name)});')
    sql('\n'.join(statements))
    resumes = api('GET', f'/recruitment/positions/{pid}/resumes', token)
    questions = [
        ('技术', '【演示预设】如何排查一条慢SQL？', '定位过程与验证证据', '结合慢日志、执行计划、索引和回归验证解释，不能仅列术语。'),
        ('项目', '【演示预设】请说明你在订单接口中实际负责的部分。', '个人职责与业务边界', '核对实际修改、异常场景、幂等处理和验证方式，不虚构生产指标。'),
        ('行为', '【演示预设】遇到不确定的问题会如何推进？', '沟通与核实能力', '说明假设、缺少的证据、沟通对象和验证步骤。'),
    ]
    summary = []
    for index, name in enumerate(names):
        rid = int(next(r for r in resumes if r['candidateName'] == name)['id'])
        statements = []
        for category, question, assess, answer in questions:
            statements.append('INSERT INTO interview_question(resume_id,position_id,category,question,assess_point,reference_answer,difficulty) SELECT ' +
                f'{rid},{pid},{text(category)},{text(question)},{text(assess)},{text(answer)},\'MEDIUM\' WHERE NOT EXISTS(SELECT 1 FROM interview_question WHERE resume_id={rid} AND question={text(question)});')
        sql('\n'.join(statements))
        assignment = api('POST', f'/recruitment/resumes/{rid}/interviews', token, dict(interviewerId=reviewer_id))
        aid = int(assignment['id'])
        if not assignment.get('schedule'):
            if index == 1:
                start = (datetime.now() + timedelta(days=1)).replace(hour=15, minute=0, second=0, microsecond=0)
                api('PUT', f'/recruitment/interviews/{aid}/schedule', token, dict(version=0, localStart=start.isoformat(), localEnd=(start + timedelta(minutes=45)).isoformat(),
                    timeZone='Asia/Shanghai', mode='VIDEO', location='演示会议室（虚构，不发送邀请）', reason='虚构未来面试练习'))
            else:
                # Explicit historical fixture; no real interview or time manipulation is claimed.
                end = (datetime.now(timezone.utc) - timedelta(days=1, hours=index)).replace(second=0, microsecond=0, tzinfo=None)
                start = end - timedelta(minutes=45)
                values = f'{aid},{reviewer_id},{rid},1,\'SCHEDULED\',{text(start)},{text(end)},\'Asia/Shanghai\',\'VIDEO\',{text("演示历史会议（虚构）")},{text(MARKER + "：手工预设过去排期，仅供立即练习评价")},{owner},UTC_TIMESTAMP()'
                sql('INSERT INTO recruitment_interview_schedule(assignment_id,interviewer_id,resume_id,version,status,start_utc,end_utc,time_zone,mode,location,reason,updated_by,updated_at) SELECT ' + values +
                    f' WHERE NOT EXISTS(SELECT 1 FROM recruitment_interview_schedule WHERE assignment_id={aid});\n' +
                    'INSERT INTO recruitment_interview_schedule_event(assignment_id,version,status,start_utc,end_utc,time_zone,mode,location,meeting_url,reason,actor_id,changed_at) ' +
                    f'SELECT assignment_id,version,status,start_utc,end_utc,time_zone,mode,location,meeting_url,reason,updated_by,updated_at FROM recruitment_interview_schedule WHERE assignment_id={aid} AND NOT EXISTS(SELECT 1 FROM recruitment_interview_schedule_event WHERE assignment_id={aid} AND version=1);')
                api('POST', f'/recruitment/interviews/{aid}/schedule/state', token, dict(version=1, action='COMPLETE', reason='演示预设：模拟面试已结束，不代表真实面试'))
        detail = api('GET', f'/interviews/assignments/{aid}', reviewer_token)
        if detail['assignment']['status'] == 'ASSIGNED' and index != 1:
            api('PUT', f'/interviews/assignments/{aid}/evaluation', reviewer_token, dict(version=detail['assignment']['version'], score=4 if index == 2 else None,
                recommendation='PASS' if index == 2 else None, strengths='【虚构预填】能按步骤说明SQL排查思路。', concerns='【虚构预填】需继续核实项目职责及异常处理。',
                summary='【虚构预填】演示评价，仅用于学习提交后锁定。' if index == 2 else '', submit=index == 2))
        summary.append(dict(candidate=name, resumeId=rid, assignmentId=aid))
    mine = api('GET', '/interviews/assignments', reviewer_token)
    selected = [a for a in mine if a['positionId'] == pid]
    assert len(selected) == 3
    for row in selected:
        detail = api('GET', f'/interviews/assignments/{row["id"]}', reviewer_token)
        assert len(detail['questions']) == 3
    state['assignments'] = summary; state['ready'] = True; save()
    (STATE.parent / 'test-account.txt').write_text('Username: ' + state['username'] + '\nPassword: ' + state['password'] + '\n', encoding='utf-8')
    print(json.dumps(dict(positionId=pid, interviewer=state['username'], candidates=summary, states=[a['status'] for a in selected], statePath=str(STATE)), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
