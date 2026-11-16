"""Fixture browser regression for sources, session controls, Markdown and batch uploads."""
import json
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

source = dict(fileName='Java岗位.md', text='必须技能：Java、Spring Boot、MySQL。', chunkIndex=0, score=0.9)
state = dict(title=None, pinned=False, messages=[], calls=[], retry=False, unknown=False)
draft = '# 初筛清单\n\n**必须技能**\n\n- Java\n- MySQL\n\n| 技能 | 证据 |\n| --- | --- |\n| Java | 项目 |\n\n```java\nSystem.out.println(1);\n```\n\n<script>window.unsafeDraft=true</script>'

def handler(route):
    request = route.request
    path = urlparse(request.url).path
    if path.endswith('/chat/sessions'):
        route.fulfill(json=[dict(sessionId='fixture-session', content='初始会话', title=state['title'], pinned=state['pinned'])])
    elif '/chat/sessions/' in path and request.method == 'PATCH':
        state.update(request.post_data_json)
        route.fulfill(status=204)
    elif '/chat/history/' in path:
        route.fulfill(json=state['messages'])
    elif path.endswith('/chat/stream'):
        state['messages'] = [dict(role='assistant', content='必须技能：Java。 [1]', sourcesJson=json.dumps([source]))]
        # Include multiline data, significant spaces and an explicit source event.
        route.fulfill(content_type='text/event-stream', body='event:sources\ndata:' + json.dumps([source]) + '\n\ndata:必须技能：\n\ndata:Java。 [1]\n\n')
    elif path.endswith('/chat'):
        route.fulfill(json=dict(answer='Java。 [1]', sources=[source]))
    elif path.endswith('/agent/tasks/pending'):
        route.fulfill(json=[dict(taskId='fixture-review', question='整理清单', draft=draft, status='AWAITING_REVIEW', rejectCount=0, sources=[source])])
    elif path.endswith('/knowledge/documents'):
        route.fulfill(json=dict(records=[], current=1, size=10, total=0))
    elif path.endswith('/knowledge/upload'):
        body = request.post_data_buffer.decode('utf-8', errors='replace')
        name = re.search(r'filename="([^"]+)"', body).group(1)
        state['calls'].append(name)
        if name == '02.md' and not state['retry']:
            route.fulfill(status=400, json=dict(message='模拟文件失败'))
        elif name == '04.md' and not state['unknown']:
            route.abort('connectionreset')
        else:
            route.fulfill(json=dict(fileName=name, documentId=name, chunkCount=1))
    else:
        route.fulfill(json={})

with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport=dict(width=1440, height=1000))
    page.add_init_script("localStorage.setItem('token','fixture'); localStorage.setItem('user', JSON.stringify({userId:1,username:'fixture',role:'USER',companyRole:'HR'}));localStorage.setItem('currentSessionId','fixture-session');")
    page.route('**/api/v1/**', handler)
    page.goto('http://127.0.0.1:5173/chat'); page.wait_for_load_state('networkidle')
    page.get_by_placeholder('输入问题，回车发送（Shift+回车换行）').fill('必须技能？')
    page.get_by_role('button', name=re.compile('发.*送')).click()
    expect(page.locator('summary').filter(has_text='Java岗位.md')).to_be_visible()
    page.locator('summary').click()
    expect(page.get_by_text(source['text'], exact=True)).to_be_visible()
    page.reload(); page.wait_for_load_state('networkidle')
    expect(page.locator('summary')).to_contain_text('Java岗位.md')
    page.get_by_role('button', name='置顶会话', exact=True).click()
    expect(page.get_by_role('button', name='取消置顶', exact=True)).to_be_visible()
    page.get_by_role('button', name='重命名会话', exact=True).click()
    page.get_by_role('textbox', name='会话名称').fill('Java招聘资料')
    page.get_by_role('button', name=re.compile('保.*存')).click()
    expect(page.get_by_text('Java招聘资料', exact=True)).to_be_visible()
    page.reload(); page.wait_for_load_state('networkidle')
    expect(page.get_by_text('Java招聘资料', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='取消置顶', exact=True)).to_be_visible()
    page.get_by_role('switch').click()
    page.get_by_placeholder('输入问题，回车发送（Shift+回车换行）').fill('再问一次')
    page.get_by_role('button', name=re.compile('发.*送')).click()
    expect(page.locator('summary')).to_have_count(2)
    page.goto('http://127.0.0.1:5173/agent'); page.wait_for_load_state('networkidle')
    expect(page.get_by_role('heading', name='初筛清单')).to_be_visible()
    expect(page.locator('.markdown-body strong')).to_have_text('必须技能')
    expect(page.locator('.markdown-body table')).to_be_visible()
    expect(page.locator('.markdown-body pre code')).to_be_visible()
    assert page.evaluate('window.unsafeDraft === undefined')
    page.goto('http://127.0.0.1:5173/knowledge'); page.wait_for_load_state('networkidle')
    files = [dict(name=f'{i:02}.md', mimeType='text/markdown', buffer=b'# fixture') for i in range(1, 5)]
    page.locator('input[type=file]').set_input_files(files)
    expect(page.get_by_text('结果待核对', exact=True)).to_be_visible()
    assert state['calls'] == ['01.md', '02.md', '03.md', '04.md'], state['calls']
    expect(page.get_by_text('成功', exact=True)).to_have_count(2)
    state['retry'] = True
    page.get_by_role('button', name='重试此文件', exact=True).click()
    expect(page.get_by_text('成功', exact=True)).to_have_count(3)
    assert state['calls'] == ['01.md', '02.md', '03.md', '04.md', '02.md']
    state['unknown'] = True
    page.get_by_role('button', name='核对后重试', exact=True).click()
    page.get_by_role('button', name='确认未成功，重试', exact=True).click()
    expect(page.get_by_text('成功', exact=True)).to_have_count(4)
    assert state['calls'][-1] == '04.md' and len(state['calls']) == 6
    page.screenshot(path='outputs/ui-review/library-improvements.png', full_page=True)
    browser.close()
print('PASS: streaming/history/plain sources, rename/pin reload, safe GFM, batch partial failure and targeted retries')
