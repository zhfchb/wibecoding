from types import SimpleNamespace
from fastapi.testclient import TestClient
from app.main import app, get_model
from app.chat import calculator
import app.chat as chat
import pytest


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "token")
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    class Model:
        def invoke(self, messages): return SimpleNamespace(content="answer")
        def stream(self, messages):
            yield SimpleNamespace(content="hello ")
            yield SimpleNamespace(content="世界😀")
    app.dependency_overrides[get_model] = lambda: Model()
    monkeypatch.setattr(chat, "retrieve", lambda query, owner: [
        {"text": "Java", "fileName": "a.md", "chunkIndex": 0, "score": .9}])
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_rag_scoped_retrieval(client, monkeypatch):
    owners = []
    monkeypatch.setattr(chat, "retrieve", lambda query, owner: owners.append(owner) or [])
    response = client.post("/internal/v1/rag", json={"message": "Java", "ownerId": 7},
                           headers={"X-AI-Service-Token": "token"})
    assert response.status_code == 200
    assert owners == [7]
    assert response.json()["sources"] == []


def test_stream_contract(client):
    response = client.post("/internal/v1/rag/stream", json={"message": "Java", "ownerId": 7},
                           headers={"X-AI-Service-Token": "token"})
    import json
    events = [json.loads(line) for line in response.text.splitlines()]
    assert events[0]["type"] == "sources"
    assert "".join(e["text"] for e in events if e["type"] == "token") == "hello 世界😀"
    assert events[-1]["type"] == "done"


def test_owner_required(client):
    assert client.post("/internal/v1/rag", json={"message": "Java"},
                       headers={"X-AI-Service-Token": "token"}).status_code == 422


def test_tools_reject_code_execution():
    assert calculator.invoke({"expression": "2*(3+4)"}) == "14"
    assert "Invalid" in calculator.invoke({"expression": "__import__('os').system('bad')"})
    assert "Invalid" in calculator.invoke({"expression": "9**9999999"})


def test_real_langchain_agent_tool_loop():
    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage
    class Model(FakeMessagesListChatModel):
        def bind_tools(self, tools, **kwargs): return self
    model = Model(responses=[
        AIMessage(content="", tool_calls=[{"name": "calculator", "args": {"expression": "2+3"}, "id": "call1", "type": "tool_call"}]),
        AIMessage(content="5"),
    ])
    assert chat.agent_answer(model, "2+3") == "5"
