"""Fixture-only progress filters, retry, read-only controls and mobile regression."""
from pathlib import Path
import os
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

scope = {}
exec(Path('scripts/verify-outcome-ui.py').read_text(encoding='utf-8').split('with sync_playwright() as p:')[0], scope)
fallback = scope['handler']
fail = [True]
rows = [dict(id=10, candidateName='候选人甲', fileName='java.md', outcome='PENDING', archived=False, submittedEvaluations=2),
        dict(id=11, candidateName='候选人乙', fileName='python.md', outcome='HIRED', archived=True, submittedEvaluations=1),
        dict(id=12, candidateName='候选人丙', fileName='go.md', outcome='PENDING', archived=False, submittedEvaluations=0)]

def handler(route):
    path = urlparse(route.request.url).path
    if path.endswith('/progress'):
        if fail[0]: route.fulfill(status=503, json=dict(message='fixture unavailable'))
        else: route.fulfill(json=rows)
    elif path.endswith('/positions/1'):
        route.fulfill(json=dict(id=1, title='只读岗位', status='OPEN', canManage=False, canOperate=False, requiredSkills=[], preferredSkills=[]))
    else: fallback(route)

with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport=dict(width=1440, height=1000))
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'fixture',role:'USER',companyRole:'HR'}))")
    page.route('**/api/v1/**', handler)
    page.goto(os.environ.get('POSITION_UI_URL', 'http://127.0.0.1:5173') + '/recruitment/positions/1'); page.wait_for_load_state('networkidle')
    card = page.locator('.ant-card').filter(has=page.get_by_text('招聘进度总览', exact=True)).last
    expect(card.get_by_text('招聘进度加载失败', exact=True)).to_be_visible()
    fail[0] = False
    card.get_by_role('button', name='重试进度').click()
    expect(card.get_by_text('总计 3', exact=True)).to_be_visible()
    expect(card.get_by_text('当前筛选 3 条', exact=True)).to_be_visible()
    def select(index, text):
        card.locator('.ant-select-selector').nth(index).click()
        page.locator('.ant-select-dropdown:visible').get_by_text(text, exact=True).click()
    select(2, '已有评价、仍待决定')
    expect(card.get_by_text('当前筛选 1 条', exact=True)).to_be_visible()
    expect(card.get_by_role('cell', name='候选人甲', exact=True)).to_be_visible()
    select(2, '全部跟进状态'); select(1, '已归档')
    expect(card.get_by_role('cell', name='候选人乙', exact=True)).to_be_visible()
    select(0, '待定')
    expect(card.get_by_text('没有符合条件的候选人', exact=True)).to_be_visible()
    select(0, '全部招聘结果'); select(1, '全部归档状态')
    card.get_by_role('textbox', name='搜索进度候选人').fill('JAVA.MD')
    expect(card.get_by_text('当前筛选 1 条', exact=True)).to_be_visible()
    card.get_by_role('button', name='招聘结果', exact=True).click()
    expect(page.get_by_role('button', name='保存招聘结果')).to_have_count(0)
    page.locator('.ant-modal-close').click()
    expect(page.locator('.ant-modal:visible')).to_have_count(0)
    page.set_viewport_size(dict(width=390, height=844))
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
    page.screenshot(path='outputs/ui-review/progress-mobile.png', full_page=True)
    assert not errors, errors
    browser.close()
print('PASS progress: retry, counts, combined filters, follow-up, search, read-only, mobile')
