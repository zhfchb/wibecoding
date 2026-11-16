"""Read-only live UI check of synthetic interviewer exercises; no model calls."""
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

root = Path(__file__).resolve().parents[1]
folder = root / 'outputs/acceptance/interview-demo'
state = json.loads((folder / 'state.json').read_text(encoding='utf-8'))
with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport=dict(width=1440, height=1000))
    page.goto('http://127.0.0.1:5173/login'); page.wait_for_load_state('networkidle')
    page.locator('#username').fill(state['username']); page.locator('#password').fill(state['password'])
    page.get_by_role('button', name=re.compile('进入工作台')).click()
    expect(page).to_have_url(re.compile('/interviews$'))
    for index, candidate in enumerate(state['assignments']):
        row = page.get_by_role('row').filter(has_text=candidate['candidate']).filter(has_text='第1轮')
        expect(row).to_be_visible()
        row.get_by_role('button').click()
        expect(page.get_by_role('dialog')).to_be_visible()
        expect(page.get_by_text('已有面试题', exact=True)).to_be_visible()
        expect(page.get_by_text('【演示预设】如何排查一条慢SQL？', exact=True)).to_be_visible()
        if index == 0:
            expect(page.get_by_role('button', name='保存草稿', exact=True)).to_be_enabled()
            expect(page.get_by_role('button', name='提交评价', exact=True)).to_be_enabled()
        elif index == 1:
            expect(page.get_by_text('面试尚未到计划结束时间，可先保存草稿', exact=True)).to_be_visible()
            expect(page.get_by_role('button', name='提交评价', exact=True)).to_be_disabled()
        else:
            expect(page.get_by_text('评价已提交，不能修改', exact=True)).to_be_visible()
            expect(page.get_by_role('button', name='提交评价', exact=True)).to_have_count(0)
        page.locator('.ant-modal-close').click()
        expect(page.get_by_role('dialog')).not_to_be_visible()
    page.screenshot(path=str(folder / 'interviewer-workbench.png'), full_page=True)
    browser.close()
(folder / 'ui-report.json').write_text(json.dumps(dict(passed=True, mocked=False, scenarios=3, writes=0)), encoding='utf-8')
print('PASS live interviewer demo: three assignments, prepared questions, draft/edit, future submission blocked, submitted locked')
