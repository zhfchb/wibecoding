"""Fixture-only Playwright regression. Start Vite on 5188, then run with Python Playwright + Chrome."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("MATCH_UI_URL", "http://127.0.0.1:5188")
state = {"task": None, "requests": [], "submit_error": True, "poll_error": False,
         "resumes": [{"id": n, "fileName": "first.md" if n == 1 else "second.md", "skills": [],
                      "parseStatus": "FAILED", "parseError": "原解析失败", "retryable": True} for n in (1, 2)]}


def api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/progress'):
        route.fulfill(json=[]); return
    method = route.request.method
    if path.endswith("/resumes/parse-tasks/latest"):
        if state["poll_error"]:
            route.fulfill(status=503, json={"message": "fixture unavailable"})
            return
        data = state["task"]
    elif path.endswith("/resumes/parse-tasks") and method == "POST":
        payload = route.request.post_data_json
        state["requests"].append(payload)
        if state["submit_error"]:
            state["submit_error"] = False
            route.fulfill(status=503, json={"message": "fixture uncertain submission"})
            return
        data = {"id": "retry-" + str(len(state["requests"])), "positionId": 1,
                "requestKey": payload["requestKey"], "status": "QUEUED", "total": len(payload["resumeIds"]),
                "succeeded": 0, "failed": 0, "cancelled": 0,
                "items": [{"resumeId": n, "status": "QUEUED"} for n in payload["resumeIds"]]}
        state["task"] = data
    elif path.endswith("/cancel"):
        data = state["task"]
        data["status"] = "CANCELLED"
        for item in data["items"]:
            if item["status"] in ("QUEUED", "RUNNING"):
                item["status"] = "CANCELLED"
                data["cancelled"] += 1
    elif path.endswith("/match/tasks/latest"):
        data = None
    elif path.endswith("/match/results"):
        data = []
    elif path.endswith("/resumes"):
        data = state["resumes"]
    elif path.endswith("/positions/1"):
        data = {"id": 1, "canManage": True, "title": "简历重试测试岗位", "status": "OPEN", "requiredSkills": ["Java"], "preferredSkills": []}
    else:
        raise AssertionError("Unexpected fixture request: " + method + " " + path)
    route.fulfill(status=202 if method == "POST" and path.endswith("/parse-tasks") else 200, json=data)


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'测试',role:'ADMIN'}));")
    page.route("**/api/v1/**", api)
    page.goto(BASE + "/recruitment/positions/1")
    page.wait_for_load_state("networkidle")
    first = page.get_by_role("row").filter(has_text="first.md")
    second = page.get_by_role("row").filter(has_text="second.md")
    expect(first.get_by_role("button", name="重新解析", exact=True)).to_be_enabled()
    first.get_by_role("checkbox").check()
    second.get_by_role("checkbox").check()
    batch = page.get_by_role("button", name=re.compile("批量重新解析"))
    expect(batch).to_be_enabled()
    batch.click()
    expect(page.get_by_text("重新解析提交结果尚未确认", exact=False)).to_be_visible()
    batch.click()
    expect(page.get_by_text("重新解析任务进行中，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert len(state["requests"]) == 2
    assert state["requests"][0] == state["requests"][1]
    assert state["requests"][1]["resumeIds"] == [1, 2]

    page.reload()
    page.wait_for_load_state("networkidle")
    expect(page.get_by_text("重新解析任务进行中，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert len(state["requests"]) == 2

    state["resumes"][0].update(candidateName="恢复成功人选", parseStatus="PARSED", retryable=False, skills=["Java"])
    state["task"].update(status="PARTIAL", succeeded=1, failed=1)
    state["task"]["items"][0].update(status="SUCCEEDED", candidateName="恢复成功人选")
    state["task"]["items"][1].update(status="FAILED", errorMessage="解析服务暂不可用，请检查服务后重新解析")
    expect(page.get_by_text("重新解析结束：成功 1 份，失败 1 份", exact=True)).to_be_visible(timeout=8000)
    expect(page.get_by_role("row").filter(has_text="恢复成功人选").get_by_role("checkbox")).to_be_disabled()
    second = page.get_by_role("row").filter(has_text="second.md")
    expect(second.get_by_role("checkbox")).to_be_enabled()
    second.get_by_role("checkbox").check()
    batch.click()
    expect(page.get_by_text("重新解析任务进行中，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert state["requests"][-1]["resumeIds"] == [2]
    assert state["requests"][-1]["requestKey"] != state["requests"][-2]["requestKey"]
    page.get_by_role("button", name="取消重新解析", exact=True).click()
    page.get_by_role("button", name="取消解析", exact=True).click()
    expect(page.get_by_text("重新解析已取消，已完成结果保留", exact=True)).to_be_visible()

    state["poll_error"] = True
    expect(page.get_by_text("暂时无法获取重新解析任务状态", exact=False)).to_be_visible(timeout=8000)
    expect(second.get_by_role("button", name="重新解析", exact=True)).to_be_disabled()
    state["poll_error"] = False
    expect(second.get_by_role("button", name="重新解析", exact=True)).to_be_enabled(timeout=8000)
    page.set_viewport_size({"width": 390, "height": 844})
    page.wait_for_timeout(200)
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth"), "Mobile page overflow"
    output = ROOT / "outputs/ui-review"
    output.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(output / "resume-parse-mobile.png"), full_page=True)
    assert not errors, errors
    browser.close()
print("PASS resume fixture browser checks: batch selection, uncertain-request key reuse, refresh recovery, partial success, retry only failures, cancellation, polling outage, mobile render")
