from types import SimpleNamespace
import pytest
from fastapi import HTTPException
import app.workflows as workflows
from app.chat import RagRequest


class Model:
    def __init__(self): self.calls = 0
    def invoke(self, messages):
        self.calls += 1
        return SimpleNamespace(content=f"draft-{self.calls}")


@pytest.fixture
def store(tmp_path, monkeypatch):
    monkeypatch.setattr(workflows, "retrieve", lambda query, owner: [
        {"text": "Java", "fileName": "a.md", "chunkIndex": 0, "score": .9}])
    model = Model()
    store = workflows.WorkflowStore(str(tmp_path / "workflow.sqlite"), lambda: model)
    yield store
    store.close()


def test_durable_interrupt_and_resume_across_instances(store):
    task = store.submit("Java?", 7, 2)
    assert task["status"] == "AWAITING_REVIEW"
    db = store.connection.execute("PRAGMA database_list").fetchone()[2]
    restarted = workflows.WorkflowStore(db, lambda: Model())
    try:
        pending = restarted.pending(7)
        assert pending[0]["taskId"] == task["taskId"]
        approved = restarted.review(task["taskId"], 7, True, "", 0)
        assert approved["status"] == "COMPLETED"
        assert approved["finalAnswer"] == task["draft"]
        assert restarted.pending(7) == []
    finally:
        restarted.close()


def test_reject_regenerates_then_stops_at_bound(store):
    task = store.submit("Java?", 7, 2)
    revised = store.review(task["taskId"], 7, False, "more evidence", 0)
    assert revised["status"] == "AWAITING_REVIEW"
    assert revised["draft"] != task["draft"]
    assert revised["rejectCount"] == 1
    with pytest.raises(HTTPException) as stale:
        store.review(task["taskId"], 7, False, "duplicate", 0)
    assert stale.value.status_code == 409
    stopped = store.review(task["taskId"], 7, False, "still insufficient", 1)
    assert stopped["status"] == "FAILED"
    assert stopped["finalAnswer"] == ""
    with pytest.raises(HTTPException): store.review(task["taskId"], 7, True, "", 2)


def test_task_owner_isolation(store):
    task = store.submit("Java?", 7, 2)
    assert store.pending(8) == []
    for action in [lambda: store.get(task["taskId"], 8), lambda: store.review(task["taskId"], 8, True, "", 0)]:
        with pytest.raises(HTTPException) as error: action()
        assert error.value.status_code == 404


def test_checkpoint_recovers_failed_generation(store):
    calls = []
    original = store.graph
    # Simulate crash after a persisted retrieve step by failing generation once.
    class Flaky:
        def invoke(self, messages):
            calls.append(1)
            if len(calls) == 1: raise RuntimeError("temporary")
            return SimpleNamespace(content="recovered")
    store.graph = workflows.review_graph(lambda: Flaky(), store.saver)
    with pytest.raises(HTTPException): store.submit("Java?", 7, 2)
    pending = store.pending(7)
    assert pending[0]["status"] == "AWAITING_REVIEW"
    assert pending[0]["draft"] == "recovered"


def test_adaptive_rewrite_uses_same_owner(monkeypatch):
    calls = []
    def retrieve(query, owner):
        calls.append((query, owner))
        return [] if len(calls) == 1 else [{"text": "Java", "score": .9}]
    monkeypatch.setattr(workflows, "retrieve", retrieve)
    request = RagRequest(message="Java?", ownerId=7, maxRetries=1)
    result = workflows.adaptive_graph(Model(), request).invoke(
        {"question": "Java?", "query": "Java?", "owner": 7, "attempts": 0})
    assert result["accepted"]
    assert len(calls) == 2
    assert all(owner == 7 for _, owner in calls)


def test_adaptive_exhaustion_does_not_invent_answer(monkeypatch):
    calls = []
    monkeypatch.setattr(workflows, "retrieve", lambda q, o: calls.append(q) or [])
    request = RagRequest(message="missing", ownerId=7, maxRetries=2)
    model = Model()
    result = workflows.adaptive_graph(model, request).invoke(
        {"question": "missing", "query": "missing", "owner": 7, "attempts": 0})
    assert len(calls) == 3
    assert model.calls == 2  # rewrite only; no unsupported answer generation
    assert not result["accepted"]
