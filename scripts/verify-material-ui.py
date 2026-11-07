"""Fixture-only drawer regression. Requires Playwright + Chrome and Vite on port 5188."""
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("MATCH_UI_URL", "http://127.0.0.1:5188")
state = {"tasks": {}, "requests": [], "uncertain": True, "poll_error": False, "artifact_error": False,
         "questions": {10: [{"id": 1, "question": "旧面试题", "category": "技术", "difficulty": "EASY"}],
                       20: [{"id": 2, "question": "乙面试题", "category": "项目", "difficulty": "MEDIUM"}]},
         "report": {"id": 1, "summary": "旧报告", "reportMd": "# 旧报告正文", "matchScore": 80, "finalRecommend": "YES"}}


def api(route):
    path = urlparse(route.request.url).path
    method = route.request.method
    material = re.search(r"/resumes/(\d+)/materials/(QUESTIONS|REPORT)/tasks(/latest)?$", path)
    if material:
        resume_id, kind = int(material.group(1)), material.group(2)
        key = (resume_id, kind)
        if method == "POST":
            state["requests"].append((resume_id, kind, route.request.post_data_json["requestKey"]))
            if kind == "QUESTIONS" and state["uncertain"]:
                state["uncertain"] = False
                route.fulfill(status=503, json={"message": "fixture uncertain submission"})
                return
            data = {"id": "material-" + str(len(state["requests"])), "resumeId": resume_id, "kind": kind,
                    "requestKey": state["requests"][-1][2], "status": "QUEUED", "phase": "QUEUED", "stale": False}
            state["tasks"][key] = data
        else:
            if state["poll_error"]:
                route.fulfill(status=503, json={"message": "fixture unavailable"})
                return
            data = state["tasks"].get(key)
    elif path.endswith("/cancel"):
        task_id = path.split("/")[-2]
        data = next(t for t in state["tasks"].values() if t["id"] == task_id)
        data.update(status="CANCELLED", phase="FINISHED")
    elif path.endswith("/questions"):
        data = state["questions"][int(path.split("/")[-2])]
    elif path.endswith("/report"):
        if state["artifact_error"]:
            route.fulfill(status=503, json={"message": "fixture content read failed"})
            return
        data = state["report"]
    elif path.endswith("/match/tasks/latest") or path.endswith("/resumes/parse-tasks/latest"):
        data = None
    elif path.endswith("/match/results"):
        data = []
    elif path.endswith("/resumes"):
        data = [{"id": n, "candidateName": name, "skills": ["Java"], "parseStatus": "PARSED", "retryable": False}
                for n, name in [(10, "候选人甲"), (20, "候选人乙")]]
    elif path.endswith("/positions/1"):
        data = {"id": 1, "title": "资料生成测试岗位", "status": "OPEN", "requiredSkills": ["Java"], "preferredSkills": []}
    else:
        raise AssertionError("Unexpected request " + method + " " + path)
    route.fulfill(status=202 if material and method == "POST" else 200, json=data)


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.add_init_script("localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({userId:1,username:'测试',role:'ADMIN'}));")
    page.route("**/api/v1/**", api)
    page.goto(BASE + "/recruitment/positions/1")
    page.wait_for_load_state("networkidle")
    page.get_by_role("row").filter(has_text="候选人甲").get_by_role("button", name="面试题", exact=True).click()
    expect(page.get_by_text("旧面试题", exact=True)).to_be_visible()
    generate = page.get_by_role("button", name=re.compile("重新生成面试题$"))
    expect(generate).to_be_enabled()
    generate.click()
    expect(page.get_by_text("生成提交结果尚未确认", exact=False)).to_be_visible()
    generate.click()
    expect(page.get_by_text("生成任务已排队", exact=True)).to_be_visible()
    assert state["requests"][0] == state["requests"][1]
    page.locator(".ant-drawer-open .ant-drawer-close").click()
    page.reload()
    page.wait_for_load_state("networkidle")
    page.get_by_role("row").filter(has_text="候选人甲").get_by_role("button", name="面试题", exact=True).click()
    expect(page.get_by_text("生成任务已排队", exact=True)).to_be_visible()
    assert len(state["requests"]) == 2
    state["tasks"][(10, "QUESTIONS")].update(status="RUNNING", phase="GENERATING")
    expect(page.get_by_text("正在生成内容", exact=True)).to_be_visible(timeout=8000)
    expect(page.get_by_text("旧面试题", exact=True)).to_be_visible()
    state["tasks"][(10, "QUESTIONS")].update(status="FAILED", errorMessage="模拟失败，原内容保留")
    retry = page.get_by_role("button", name=re.compile("重试生成面试题$"))
    expect(retry).to_be_enabled(timeout=8000)
    expect(page.get_by_text("旧面试题", exact=True)).to_be_visible()
    retry.click()
    expect(page.get_by_text("生成任务已排队", exact=True)).to_be_visible()
    assert state["requests"][-1][2] != state["requests"][-2][2]
    page.get_by_role("button", name="取消生成", exact=True).click()
    page.get_by_role("button", name="取消本轮", exact=True).click()
    expect(page.get_by_text("生成任务已取消，原内容保留", exact=True)).to_be_visible()
    page.locator(".ant-drawer-open .ant-drawer-close").click()

    page.get_by_role("row").filter(has_text="候选人甲").get_by_role("button", name="评估报告", exact=True).click()
    expect(page.get_by_role("button", name=re.compile("重新生成报告$"))).to_be_enabled()
    page.get_by_role("button", name=re.compile("重新生成报告$")).click()
    expect(page.get_by_text("生成任务已排队", exact=True)).to_be_visible()
    state["tasks"][(10, "REPORT")].update(status="RUNNING", phase="SEARCHING")
    expect(page.get_by_text("正在查找参考资料", exact=True)).to_be_visible(timeout=8000)
    state["report"].update(summary="新报告结论", reportMd="# 新报告正文")
    state["artifact_error"] = True
    state["tasks"][(10, "REPORT")].update(status="SUCCEEDED", phase="FINISHED")
    expect(page.get_by_text("生成完成", exact=True)).to_be_visible(timeout=8000)
    expect(page.get_by_text("无法加载报告，已有内容保留", exact=False)).to_be_visible()
    expect(page.get_by_text("旧报告", exact=True)).to_be_visible()
    state["artifact_error"] = False
    page.get_by_role("button", name="重新加载报告", exact=True).click()
    expect(page.get_by_text("新报告结论", exact=True)).to_be_visible()
    state["tasks"][(10, "REPORT")]["stale"] = True
    expect(page.get_by_text("岗位、简历或匹配结果已变化", exact=False)).to_be_visible(timeout=8000)
    state["poll_error"] = True
    expect(page.get_by_text("暂时无法获取生成任务状态", exact=False)).to_be_visible(timeout=8000)
    expect(page.get_by_role("button", name=re.compile("重新生成报告$"))).to_be_disabled()
    state["poll_error"] = False
    expect(page.get_by_role("button", name=re.compile("重新生成报告$"))).to_be_enabled(timeout=8000)
    page.set_viewport_size({"width": 390, "height": 844})
    page.wait_for_timeout(200)
    assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth"), "Mobile drawer overflow"
    output = ROOT / "outputs/ui-review"
    output.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(output / "material-report-mobile.png"), full_page=True)
    page.locator(".ant-drawer-open .ant-drawer-close").click()
    page.set_viewport_size({"width": 1280, "height": 900})
    page.get_by_role("row").filter(has_text="候选人乙").get_by_role("button", name="面试题", exact=True).click()
    expect(page.get_by_text("乙面试题", exact=True)).to_be_visible()
    expect(page.get_by_text("旧面试题", exact=True)).not_to_be_visible()
    assert not errors, errors
    browser.close()
print("PASS material fixture browser checks: uncertain-submit idempotency, close/reload recovery, old content preservation, explicit retry, cancellation, report phases/completion/staleness, content-read recovery, polling outage, mobile drawer, candidate isolation")
