from fastapi.testclient import TestClient
from app.main import app
from app.vectors import get_embeddings


def test_embedding_contract(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "token")
    class Model:
        def embed_documents(self, texts): return [[1., 0.] for _ in texts]
    app.dependency_overrides[get_embeddings] = lambda: Model()
    try:
        client = TestClient(app)
        headers = {"X-AI-Service-Token": "token"}
        assert client.post("/internal/v1/embeddings", json={"texts": ["Java"]}, headers=headers).json() == {"vectors": [[1., 0.]]}
        assert client.post("/internal/v1/embeddings", json={"texts": [""]}, headers=headers).status_code == 422
    finally: app.dependency_overrides.clear()
