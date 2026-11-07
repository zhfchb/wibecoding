import os
import math
import logging
from functools import lru_cache
from fastapi import APIRouter, Depends, HTTPException
from langchain_openai import OpenAIEmbeddings
from pydantic import BaseModel, Field, field_validator
import httpx
from app.runtime import authorize
from app.network import get_vector_http_client

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(authorize)])


class EmbeddingRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=32)
    @field_validator("texts")
    @classmethod
    def check_texts(cls, values):
        if any(not text.strip() or len(text) > 12000 for text in values):
            raise ValueError("Invalid embedding text")
        return values


@lru_cache
def get_embeddings():
    key = os.environ.get("RAG_EMBEDDING_API_KEY", "")
    if not key: raise HTTPException(503, "Embedding model not configured")
    return OpenAIEmbeddings(api_key=key,
        base_url=os.environ.get("RAG_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1"),
        model=os.environ.get("RAG_EMBEDDING_MODEL", "BAAI/bge-m3"),
        check_embedding_ctx_length=False, max_retries=0, request_timeout=45,
        http_client=get_vector_http_client())


@router.post("/internal/v1/embeddings")
def embed(request: EmbeddingRequest, model=Depends(get_embeddings)):
    try:
        vectors = model.embed_documents(request.texts)
        if len(vectors) != len(request.texts) or any(not vector or any(not math.isfinite(v) for v in vector) for vector in vectors):
            raise ValueError()
        return {"vectors": vectors}
    except Exception as exc:
        # Exception messages can include provider payloads. Log only types.
        logger.warning('embedding_upstream_failed error_type=%s cause_type=%s',
                       type(exc).__name__, type(exc.__cause__).__name__)
        raise HTTPException(502, "Embedding failed") from None


class RerankRequest(BaseModel):
    query: str = Field(min_length=1, max_length=12000)
    documents: list[str] = Field(min_length=1, max_length=100)
    topK: int = Field(ge=1, le=100)


@router.post("/internal/v1/rerank")
def rerank(request: RerankRequest):
    key = os.environ.get("RAG_RERANK_API_KEY") or os.environ.get("RAG_EMBEDDING_API_KEY", "")
    if not key: raise HTTPException(503, "Rerank model not configured")
    base = os.environ.get("RAG_RERANK_BASE_URL", os.environ.get("RAG_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1"))
    try:
        client = get_vector_http_client() or httpx
        response = client.post(base.rstrip("/") + "/rerank", headers={"Authorization": "Bearer " + key},
            json={"model": os.environ.get("RAG_RERANK_MODEL", "BAAI/bge-reranker-v2-m3"), "query": request.query,
                  "documents": request.documents, "top_n": min(request.topK, len(request.documents)), "return_documents": False}, timeout=45)
        response.raise_for_status()
        return {"results": response.json()["results"]}
    except Exception: raise HTTPException(502, "Rerank failed") from None
