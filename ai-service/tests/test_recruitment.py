from fastapi.testclient import TestClient
from langchain_core.runnables import RunnableLambda
from app.main import app, get_model
from app.recruitment import SCHEMAS
import pytest


@pytest.mark.parametrize("operation,result", [
    ("match", {"items": [{"resumeId": 1, "score": 80}]}),
    ("position", {"title": "Java"}),
    ("report", {"summary": "test", "strength": "Java", "risk": "verify", "salarySuggest": "unknown",
                "finalRecommend": "YES", "reportMd": "report"}),
    ("interview", {"items": [{"category": "技术", "question": "Java?", "assessPoint": "test",
                               "referenceAnswer": "test", "difficulty": "EASY"}] * 6}),
])
def test_generation_contract(monkeypatch, operation, result):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "token")
    class Model:
        def with_structured_output(self, schema, **kwargs):
            assert schema is SCHEMAS[operation]
            return RunnableLambda(lambda value: schema.model_validate(result))
    app.dependency_overrides[get_model] = lambda: Model()
    try:
        response = TestClient(app).post("/internal/v1/generate",
            json={"operation": operation, "prompt": "test"}, headers={"X-AI-Service-Token": "token"})
        assert response.status_code == 200
    finally:
        app.dependency_overrides.clear()
