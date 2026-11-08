"""Fixture-only position form checks. Uses the running local frontend; never writes real data."""
import os
import re
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get("POSITION_UI_URL", "http://127.0.0.1:5173")
submitted = []


def api(route):
    path = urlparse(route.request.url).path
    if route.request.method == "POST":
        assert path.endswith("/positions")
        submitted.append(route.request.post_data_json)
        route.fulfill(status=400, json={"message": "fixture only: submission captured"})
    else:
        if path.endswith('/company/departments'):
            route.fulfill(json=[{'id': 7, 'name': '正式研发部', 'status': 'ACTIVE'}])
            return
        assert path.endswith("/positions"), path
        route.fulfill(json=[{"id": 1, "title": "界面测试岗位", "department": "既有事业部", "city": "宁波",
                             "status": "OPEN", "requiredSkills": ["Java"], "resumeCount": 0}])


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'测试',role:'ADMIN'}));")
    page.route("**/api/v1/**", api)
    page.goto(BASE + "/recruitment")
    page.wait_for_load_state("networkidle")
    page.get_by_role("button", name=re.compile("创建招聘岗位$")).click()
    page.get_by_placeholder("如：Java 后端工程师").fill("表单验收")
    department = page.locator('#departmentId')
    department.click()
    page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").filter(has_text=re.compile("^正式研发部$")).click()
    expect(page.locator('.ant-select-selection-item').filter(has_text='正式研发部')).to_be_visible()
    city = page.get_by_placeholder("选择或输入城市")
    city.click()
    page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").filter(has_text=re.compile("^上海$")).click()
    expect(city).to_have_value("上海")
    city.fill("珠海")
    city.press("Tab")
    lower = page.get_by_placeholder("下限", exact=True)
    upper = page.get_by_role("spinbutton").nth(1)
    lower.fill("11")
    expect(upper).to_have_attribute("aria-valuemin", "12")
    upper.fill("12")
    upper.press("ArrowDown")
    expect(upper).to_have_value("12")
    lower.fill("20")
    lower.press("Tab")
    expect(upper).to_have_value("21")
    lower.fill("11")
    upper.fill("12")
    page.locator("#requiredSkills").fill("Java")
    page.locator("#requiredSkills").press("Enter")
    page.get_by_role("button", name=re.compile("^创\\s*建$")).click()
    expect(page.get_by_text("fixture only: submission captured", exact=False)).to_be_visible()
    assert len(submitted) == 1, submitted
    assert submitted[0]["departmentId"] == 7
    assert 'department' not in submitted[0]
    assert submitted[0]["city"] == "珠海"
    assert (submitted[0]["salaryMin"], submitted[0]["salaryMax"]) == (11, 12)
    assert not errors, errors
    browser.close()
print("PASS formal company department ID and city custom value; salary minimum, keyboard bound, lower-bound increase, submitted payload")
