"""Browser regression with fixture APIs, never real models or business data.

Requires Python Playwright and an installed Chrome. Start frontend on port 5188,
then run: python scripts/verify-match-task-ui.py
Override MATCH_UI_URL for another local dev-server address.
"""
import json
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("MATCH_UI_URL", "http://127.0.0.1:5188")
state = {"task": None, "keys": [], "results": [], "poll_error": False, "submit_error": False}


def route_api(route):
    path = urlparse(route.request.url).path
    if path.endswith('/progress'):
        route.fulfill(json=[]); return
    method = route.request.method
    if path.endswith("/resumes/parse-tasks/latest"):
        data = None
    elif path.endswith("/match/tasks/latest"):
        if state["poll_error"]:
            route.fulfill(status=503, json={"message": "fixture unavailable"})
            return
        data = state["task"]
    elif path.endswith("/match/tasks") and method == "POST":
        state["keys"].append(route.request.post_data_json["requestKey"])
        if state["submit_error"]:
            state["submit_error"] = False
            route.fulfill(status=503, json={"message": "fixture uncertain submission"})
            return
        data = {"id": "fixture-task-" + str(len(state["keys"])), "positionId": 1, "requestKey": state["keys"][-1],
                "status": "QUEUED", "phase": "QUEUED", "completedBatches": 0,
                "totalBatches": 1, "candidateCount": 1, "degradedCount": 0}
        state["task"] = data
    elif path.endswith("/cancel"):
        state["task"]["status"] = "CANCELLED"
        data = state["task"]
    elif path.endswith("/match/results"):
        data = state["results"]
    elif path.endswith("/resumes"):
        data = [{"id": 10, "candidateName": "界面测试候选人", "skills": ["Java"], "parseStatus": "PARSED"}]
    elif path.endswith("/positions/1"):
        data = {"id": 1, "canManage": True, "title": "界面测试岗位", "status": "OPEN", "requiredSkills": ["Java"], "preferredSkills": []}
    else:
        raise AssertionError("Unexpected fixture request: " + method + " " + path)
    route.fulfill(status=202 if method == "POST" and path.endswith("/match/tasks") else 200,
                  content_type="application/json", body=json.dumps(data, ensure_ascii=False))


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.add_init_script("localStorage.setItem('token','ui-fixture'); localStorage.setItem('user',JSON.stringify({userId:1,username:'界面测试',role:'ADMIN'}));")
    page.route("**/api/v1/**", route_api)
    page.goto(BASE + "/recruitment/positions/1")
    page.wait_for_load_state("networkidle")
    expect(page.get_by_role("button", name=re.compile("执行人岗匹配$"))).to_be_enabled()

    # An uncertain submission retries with the same key, instead of creating a second paid run.
    state["submit_error"] = True
    page.get_by_role("button", name=re.compile("执行人岗匹配$")).click()
    expect(page.get_by_text("提交结果尚未确认", exact=False)).to_be_visible()
    page.get_by_role("button", name=re.compile("执行人岗匹配$")).click()
    expect(page.get_by_text("匹配任务已排队，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert len(state["keys"]) == 2 and state["keys"][0] == state["keys"][1]

    # Refresh discovers the durable task from the server, without another POST.
    page.reload()
    page.wait_for_load_state("networkidle")
    expect(page.get_by_text("匹配任务已排队，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert len(state["keys"]) == 2
    state["task"].update(status="RUNNING", phase="SEMANTIC_SCORING")
    expect(page.get_by_text("评估技能匹配", exact=False)).to_be_visible(timeout=8000)

    state["results"] = [{"id": 1, "resumeId": 10, "candidateName": "界面测试候选人", "matchScore": 100,
                         "rankNo": 1, "skillHits": ["Java"], "skillGaps": [], "hrDecision": "PENDING",
                         "scoreDetail": {"ruleScore": 100, "llmScore": None, "scoringStatus": "DEGRADED"}}]
    state["task"].update(status="SUCCEEDED", degradedCount=1, completedBatches=1)
    expect(page.get_by_text("匹配完成，1 位候选人的语义评分未完成，仅使用规则分", exact=True)).to_be_visible(timeout=8000)
    expect(page.get_by_text("语义评分未完成", exact=True)).to_be_visible()

    # Poll failure is visibly unknown, and blocks additional submissions until polling recovers.
    state["poll_error"] = True
    expect(page.get_by_text("暂时无法获取匹配任务状态", exact=False)).to_be_visible(timeout=8000)
    expect(page.get_by_role("button", name=re.compile("执行人岗匹配$"))).to_be_disabled()
    state["poll_error"] = False
    expect(page.get_by_role("button", name=re.compile("执行人岗匹配$"))).to_be_enabled(timeout=8000)

    page.get_by_role("button", name=re.compile("执行人岗匹配$")).click()
    expect(page.get_by_text("匹配任务已排队，刷新页面后仍可查看", exact=True)).to_be_visible()
    state["task"].update(status="FAILED", errorCode="TIMEOUT", errorMessage="任务超时，请确认后重新匹配")
    expect(page.get_by_role("button", name=re.compile("重试人岗匹配$"))).to_be_enabled(timeout=8000)
    page.get_by_role("button", name=re.compile("重试人岗匹配$")).click()
    expect(page.get_by_text("匹配任务已排队，刷新页面后仍可查看", exact=True)).to_be_visible()
    assert state["keys"][-1] != state["keys"][-2]
    page.get_by_role("button", name="取消匹配", exact=True).click()
    page.get_by_role("button", name="取消本轮", exact=True).click()
    expect(page.get_by_text("匹配任务已取消，原结果保留", exact=True)).to_be_visible()
    page.set_viewport_size({"width": 390, "height": 844})
    expect(page.get_by_text("匹配任务已取消，原结果保留", exact=True)).to_be_visible()
    page.wait_for_timeout(200)  # Allow Ant Design's responsive breakpoint update before measuring.
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth"), "Mobile page overflows viewport"
    folder = ROOT / "outputs/ui-review"
    folder.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(folder / "match-task-mobile.png"), full_page=True)
    assert not errors, errors
    browser.close()
print("PASS fixture browser checks: uncertain-submit key reuse, refresh recovery, progress, warnings, polling failure, explicit retry, cancellation, mobile render")
