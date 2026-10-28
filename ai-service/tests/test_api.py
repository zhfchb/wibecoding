import pytest
from fastapi.testclient import TestClient
from app.main import app, get_parser, get_model


class Parser:
    def invoke(self, values):
        assert values["text"]
        return {"candidate_name": "测试", "skills": ["Java"], "years_exp": 3}


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "test-token")
    app.dependency_overrides[get_parser] = lambda: Parser()
    with TestClient(app) as client:
        yield client
    app.dependency_overrides.clear()


def test_parse(client):
    response = client.post("/internal/v1/resumes/parse", json={"text": "Java 三年"},
                           headers={"X-AI-Service-Token": "test-token"})
    assert response.status_code == 200
    assert response.json()["years_exp"] == 3
    assert response.json()["skills"] == ["Java"]


def test_auth(client):
    assert client.post("/internal/v1/resumes/parse", json={"text": "Java"}).status_code == 401


@pytest.mark.parametrize("text", ["", "  ", "x" * 12001])
def test_invalid_input(client, text):
    assert client.post("/internal/v1/resumes/parse", json={"text": text},
                       headers={"X-AI-Service-Token": "test-token"}).status_code == 422


def test_model_error_is_redacted(client):
    class Broken:
        def invoke(self, values):
            raise ValueError("private resume and credentials")
    app.dependency_overrides[get_parser] = lambda: Broken()
    response = client.post("/internal/v1/resumes/parse", json={"text": "Java"},
                           headers={"X-AI-Service-Token": "test-token"})
    assert response.status_code == 502
    assert "private" not in response.text


def test_invalid_model_output(client):
    class Invalid:
        def invoke(self, values):
            return {"skills": ["Java"], "years_exp": -5}
    app.dependency_overrides[get_parser] = lambda: Invalid()
    response = client.post("/internal/v1/resumes/parse", json={"text": "Java"},
                           headers={"X-AI-Service-Token": "test-token"})
    assert response.status_code == 502


def test_missing_model_key(monkeypatch):
    from fastapi import HTTPException
    monkeypatch.delenv("DEEPSEEK_CLIENT_API_KEY", raising=False)
    get_parser.cache_clear()
    get_model.cache_clear()
    with pytest.raises(HTTPException) as error:
        get_parser()
    assert error.value.status_code == 503


def test_missing_service_token(client, monkeypatch):
    monkeypatch.delenv("AI_SERVICE_TOKEN")
    assert client.post("/internal/v1/resumes/parse", json={"text": "Java"}).status_code == 503


def test_real_langchain_pipeline_with_mock_model_http(monkeypatch):
    import json
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            valid = (payload["response_format"] == {"type": "json_object"}
                     and "Java" in payload["messages"][-1]["content"]
                     and "candidate_name" in payload["messages"][0]["content"])
            body = json.dumps({
                "id": "test", "object": "chat.completion", "created": 1, "model": "deepseek-chat",
                "choices": [{"index": 0, "message": {"role": "assistant",
                             "content": '{"skills":["Java"],"years_exp":3}'}, "finish_reason": "stop"}],
            }).encode()
            self.send_response(200 if valid else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("DEEPSEEK_CLIENT_API_KEY", "test-key")
    monkeypatch.setenv("DEEPSEEK_BASE_URL", f"http://127.0.0.1:{server.server_port}/v1")
    get_parser.cache_clear()
    get_model.cache_clear()
    try:
        profile = get_parser().invoke({"text": "Java three years"})
        assert profile.years_exp == 3
        assert profile.skills == ["Java"]
    finally:
        get_parser.cache_clear()
        get_model.cache_clear()
        server.shutdown()
        server.server_close()
        thread.join()
