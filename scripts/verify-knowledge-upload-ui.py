"""Fixture-only upload errors, manual refresh, timeout budget and success regression."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

state=dict(uploads=0,mode='unavailable',documents=[])
def handler(route):
    path=urlparse(route.request.url).path
    if path.endswith('/knowledge/upload'):
        state['uploads']+=1
        if state['mode']=='unavailable':
            route.fulfill(status=503,json=dict(error='VECTOR_STORE_UNAVAILABLE',message='向量库连接暂时不可用，请稍后重试；上传失败的资料不会显示为已完成'))
        elif state['mode']=='disconnected':route.abort('connectionreset')
        else:
            state['documents']=[dict(id=1,documentId='fixture-doc',fileName='fourth.md',fileSize=100,chunkCount=2)]
            route.fulfill(json=dict(documentId='fixture-doc',fileName='fourth.md',chunkCount=2))
    elif path.endswith('/knowledge/documents'):
        route.fulfill(json=dict(records=state['documents'],current=1,size=10,total=len(state['documents'])))
    else:route.fulfill(json={})

with sync_playwright() as p:
    browser=p.chromium.launch(channel='chrome',headless=True)
    page=browser.new_page(viewport=dict(width=1280,height=900))
    page.add_init_script("""
      localStorage.setItem('token','fixture');
      localStorage.setItem('user',JSON.stringify({userId:1,username:'fixture',role:'USER',companyRole:'HR'}));
      const originalOpen=XMLHttpRequest.prototype.open, originalSend=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open=function(method,url,...rest){this.fixtureUrl=String(url);return originalOpen.call(this,method,url,...rest)};
      XMLHttpRequest.prototype.send=function(...args){if(this.fixtureUrl.includes('/knowledge/upload')) window.fixtureUploadTimeout=this.timeout;return originalSend.apply(this,args)};
    """)
    page.route('**/api/v1/**',handler)
    page.goto(os.environ.get('POSITION_UI_URL','http://127.0.0.1:5173')+'/knowledge');page.wait_for_load_state('networkidle')
    file=Path('demo-data/knowledge-base/upload/04-初筛与证据规则.md').resolve()
    page.locator('input[type=file]').set_input_files(str(file))
    expect(page.get_by_text('上传失败：向量库连接暂时不可用，请稍后重试；上传失败的资料不会显示为已完成',exact=True)).to_be_visible()
    assert page.evaluate('window.fixtureUploadTimeout')==300000
    assert state['uploads']==1
    state['mode']='disconnected'
    page.get_by_role('button',name='重试此文件',exact=True).click()
    expect(page.get_by_text('上传失败：连接中断或处理超时，请先刷新资料列表核对是否上传完成，再决定是否重试',exact=True)).to_be_visible()
    page.locator('button').filter(has_text='刷新').click();page.wait_for_load_state('networkidle')
    assert state['uploads']==2
    state['mode']='success'
    page.get_by_role('button',name='核对后重试',exact=True).click()
    page.get_by_role('button',name='确认未成功，重试',exact=True).click()
    expect(page.get_by_role('cell',name='fourth.md',exact=True)).to_be_visible()
    assert state['uploads']==3
    page.screenshot(path='outputs/ui-review/knowledge-upload-recovery.png',full_page=True,animations='disabled')
    browser.close()
print('PASS knowledge upload: 503 message, disconnected message, no automatic reupload, 300s budget, manual recovery')
