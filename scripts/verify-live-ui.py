"""Read-only, unmocked browser checks against the dedicated live acceptance workspace."""
import argparse
import json
from pathlib import Path
import re
from playwright.sync_api import sync_playwright, expect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True, type=Path)
    parser.add_argument('--url', default='http://127.0.0.1:5173')
    args = parser.parse_args()
    state = json.loads(args.state.read_text(encoding='utf-8'))
    results = state['results']
    folder = args.state.parent
    report = dict(mocked=False, checks=[])
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='chrome', headless=True)
        try:
            for role in ('owner', 'editor', 'viewer', 'interviewer'):
                context = browser.new_context(viewport=dict(width=1440, height=1000))
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(args.url + '/login'); page.wait_for_load_state('networkidle')
                page.locator('#username').fill(results[role]['username'])
                page.locator('#password').fill(state['password'])
                page.get_by_role('button', name=re.compile('进入工作台')).click()
                expect(page).not_to_have_url(re.compile('/login$'))
                if role == 'interviewer':
                    expect(page).to_have_url(re.compile('/interviews$'))
                    label = '查看评价' if state.get('passed') else '准备面试与评价'
                    page.get_by_role('button', name=label, exact=True).click()
                    modal = page.locator('.ant-modal:visible')
                    expect(modal.get_by_text('已有面试题', exact=True)).to_be_visible()
                    expect(modal.get_by_text(results['questions'][0]['question'], exact=True)).to_be_visible()
                    if state.get('passed'):
                        expect(modal.get_by_text('评价已提交，不能修改', exact=True)).to_be_visible()
                        expect(page.get_by_role('button', name='提交评价', exact=True)).to_have_count(0)
                    else:
                        expect(page.get_by_role('button', name='提交评价', exact=True)).to_be_disabled()
                    page.screenshot(path=str(folder / ('browser-' + role + '.png')), full_page=True, animations='disabled')
                else:
                    page.goto(args.url + '/recruitment/positions/' + str(results['position']['id']))
                    page.wait_for_load_state('networkidle')
                    expect(page.get_by_text('招聘进度总览', exact=True)).to_be_visible()
                    expect(page.get_by_text('招聘进度加载失败', exact=True)).to_have_count(0)
                    if role == 'viewer':
                        expect(page.get_by_role('button', name='分配面试', exact=True)).to_have_count(0)
                    else:
                        expect(page.get_by_role('button', name='分配面试', exact=True)).to_be_visible()
                    page.get_by_role('button', name='面试题', exact=True).first.click()
                    drawer = page.locator('.ant-drawer-content:visible')
                    expect(drawer.get_by_text(results['questions'][0]['question'], exact=True)).to_be_visible()
                    drawer.locator('.ant-drawer-close').click()
                    expect(drawer).not_to_be_visible()
                    page.get_by_role('button', name='评估报告', exact=True).first.click()
                    drawer = page.locator('.ant-drawer-content:visible')
                    expect(drawer.get_by_text('加载报告失败', exact=True)).to_have_count(0)
                    expect(drawer.get_by_text('尚未生成报告', exact=False)).to_have_count(0)
                    expect(drawer.get_by_text(results['report']['summary'], exact=True)).to_be_visible()
                    page.screenshot(path=str(folder / ('browser-' + role + '.png')), full_page=True, animations='disabled')
                    drawer.locator('.ant-drawer-close').click()
                    expect(drawer).not_to_be_visible()
                    page.get_by_role('button', name='招聘结果', exact=True).first.click()
                    modal = page.locator('.ant-modal:visible')
                    if state.get('passed'):
                        expect(modal.get_by_text('已归档', exact=True)).to_be_visible()
                    if role == 'viewer':
                        expect(modal.get_by_role('button', name='保存招聘结果', exact=True)).to_have_count(0)
                        expect(modal.get_by_role('button', name='重新开启记录', exact=True)).to_have_count(0)
                    page.screenshot(path=str(folder / ('result-' + role + '.png')), full_page=True, animations='disabled')
                assert not errors, errors
                report['checks'].append(dict(role=role, passed=True))
                context.close()
        finally:
            browser.close()
    report['completedWorkflow'] = bool(state.get('passed'))
    (folder / 'browser-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS unmocked live UI: four roles, real questions/report, result permissions')


if __name__ == '__main__':
    main()
