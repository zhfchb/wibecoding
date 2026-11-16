"""FastAPI assembly only. Java owns authorization boundaries and business persistence."""
from fastapi import FastAPI
from app.features import resumes, recruitment, chat, workflows, vectors

app = FastAPI(title="Recruitment AI Service", version="0.1.0")


@app.get("/health")
def health():
    # Liveness only; does not imply model connectivity.
    return {"status": "ok"}


for router in (resumes.router, recruitment.router, chat.router, workflows.router, vectors.router):
    app.include_router(router)
