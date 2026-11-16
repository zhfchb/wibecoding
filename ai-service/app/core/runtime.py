import os
import secrets
from functools import lru_cache
from fastapi import Header, HTTPException
from langchain_openai import ChatOpenAI


def authorize(x_ai_service_token: str = Header(default="")) -> None:
    token = os.environ.get("AI_SERVICE_TOKEN", "")
    if not token:
        raise HTTPException(503, "Service authentication is not configured")
    if not secrets.compare_digest(x_ai_service_token.encode(), token.encode()):
        raise HTTPException(401, "Invalid service token")


@lru_cache
def get_model():
    key = os.environ.get("DEEPSEEK_CLIENT_API_KEY", "")
    if not key:
        raise HTTPException(503, "Model is not configured")
    return ChatOpenAI(
        model=os.environ.get("AI_MODEL", "deepseek-chat"),
        api_key=key,
        base_url=os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
        temperature=0,
        timeout=45,
        max_retries=0,
    )
