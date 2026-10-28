"""LangGraph query refinement and durable human approval workflows."""
import os
import sqlite3
from datetime import datetime, timezone, timedelta
from functools import lru_cache
from pathlib import Path
from threading import RLock
from typing import TypedDict
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt, Command
from langgraph.checkpoint.sqlite import SqliteSaver
from pydantic import BaseModel, Field
from app.main import authorize, get_model
from app.chat import RagRequest, retrieve, sources, messages_for

router = APIRouter(dependencies=[Depends(authorize)])


class AdaptiveState(TypedDict, total=False):
    question: str
    query: str
    owner: int
    attempts: int
    matches: list
    accepted: bool
    answer: str


def adaptive_graph(model, request):
    def search(state):
        matches = retrieve(state["query"], state["owner"])
        return {"matches": matches, "accepted": bool(matches) and max(m["score"] for m in matches) >= request.minScore}
    def rewrite(state):
        result = model.invoke([("system", "重写查询以便检索，只输出查询，不改变原始意图。"), ("human", state["question"])])
        return {"query": str(result.content)[:12000], "attempts": state["attempts"] + 1}
    def generate(state):
        if not state["accepted"]:
            return {"answer": "未找到足够相关的知识库证据，请补充资料或调整问题。"}
        return {"answer": model.invoke(messages_for(request, state["matches"])).content}
    def route(state):
        return "generate" if state["accepted"] or state["attempts"] >= request.maxRetries else "rewrite"
    graph = StateGraph(AdaptiveState)
    graph.add_node("retrieve", search)
    graph.add_node("rewrite", rewrite)
    graph.add_node("generate", generate)
    graph.add_edge(START, "retrieve")
    graph.add_conditional_edges("retrieve", route, {"generate": "generate", "rewrite": "rewrite"})
    graph.add_edge("rewrite", "retrieve")
    graph.add_edge("generate", END)
    return graph.compile()


@router.post("/internal/v1/rag/adaptive")
def adaptive(request: RagRequest, model=Depends(get_model)):
    try:
        result = adaptive_graph(model, request).invoke({"question": request.message, "query": request.message,
            "owner": request.ownerId, "attempts": 0}, {"recursion_limit": 30})
        return {"answer": result["answer"], "sources": sources(result["matches"])}
    except Exception:
        raise HTTPException(502, "Adaptive workflow failed") from None


class ReviewState(TypedDict, total=False):
    question: str
    owner: int
    matches: list
    draft: str
    finalAnswer: str
    status: str
    rejectCount: int
    reviewFeedback: str
    maxRejects: int


def review_graph(model_factory, saver):
    def search(state):
        return {"matches": retrieve(state["question"], state["owner"])}
    def generate(state):
        request = RagRequest(message=state["question"], ownerId=state["owner"])
        messages = messages_for(request, state["matches"])
        if state.get("reviewFeedback"):
            messages.append(("human", "人工审核反馈：" + state["reviewFeedback"]))
        draft = model_factory().invoke(messages).content
        return {"draft": draft, "status": "AWAITING_REVIEW", "finalAnswer": ""}
    def review(state):
        decision = interrupt({"draft": state["draft"], "rejectCount": state["rejectCount"]})
        if decision["approved"]:
            return Command(update={"status": "COMPLETED", "finalAnswer": state["draft"]}, goto=END)
        count = state["rejectCount"] + 1
        if count >= state["maxRejects"]:
            return Command(update={"status": "FAILED", "rejectCount": count,
                "reviewFeedback": decision["feedback"], "finalAnswer": ""}, goto=END)
        return Command(update={"rejectCount": count, "reviewFeedback": decision["feedback"],
            "status": "RUNNING"}, goto="generate")
    graph = StateGraph(ReviewState)
    graph.add_node("retrieve", search)
    graph.add_node("generate", generate)
    graph.add_node("review", review)
    graph.add_edge(START, "retrieve")
    graph.add_edge("retrieve", "generate")
    graph.add_edge("generate", "review")
    return graph.compile(checkpointer=saver)


def now():
    return datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None).isoformat()


class WorkflowStore:
    """One process per data volume. Serializes resume and persists indexes + checkpoints."""
    def __init__(self, path, model_factory=get_model):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute("CREATE TABLE IF NOT EXISTS ai_tasks (id TEXT PRIMARY KEY, owner INTEGER NOT NULL, created TEXT NOT NULL, updated TEXT NOT NULL)")
        self.connection.commit()
        self.lock = RLock()
        self.saver = SqliteSaver(self.connection)
        self.graph = review_graph(model_factory, self.saver)

    def close(self):
        self.connection.close()

    def config(self, task_id):
        return {"configurable": {"thread_id": task_id}, "recursion_limit": 30}

    def owned(self, task_id, owner):
        row = self.connection.execute("SELECT created, updated FROM ai_tasks WHERE id=? AND owner=?", (task_id, owner)).fetchone()
        if not row: raise HTTPException(404, "Task not found")
        return row

    def snapshot(self, task_id, owner):
        created, updated = self.owned(task_id, owner)
        snapshot = self.graph.get_state(self.config(task_id))
        state = snapshot.values
        return {"taskId": task_id, "threadId": task_id, "question": state.get("question", ""),
                "status": state.get("status", "RUNNING"), "draft": state.get("draft", ""),
                "finalAnswer": state.get("finalAnswer", ""), "sources": sources(state.get("matches", [])),
                "rejectCount": state.get("rejectCount", 0), "reviewFeedback": state.get("reviewFeedback", ""),
                "createdAt": created, "updatedAt": updated}

    def submit(self, question, owner, max_rejects):
        with self.lock:
            task_id = str(uuid4())
            stamp = now()
            self.connection.execute("INSERT INTO ai_tasks VALUES (?, ?, ?, ?)", (task_id, owner, stamp, stamp))
            self.connection.commit()
            try:
                self.graph.invoke({"question": question, "owner": owner, "rejectCount": 0,
                    "reviewFeedback": "", "maxRejects": max_rejects, "status": "RUNNING"}, self.config(task_id))
            except Exception:
                # Keep checkpoint pending tasks so a subsequent get can resume transient failure.
                raise HTTPException(502, "Workflow execution failed; task can be recovered: " + task_id) from None
            return self.snapshot(task_id, owner)

    def get(self, task_id, owner):
        with self.lock:
            self.owned(task_id, owner)
            snapshot = self.graph.get_state(self.config(task_id))
            if snapshot.next and not any(task.interrupts for task in snapshot.tasks):
                try: self.graph.invoke(None, self.config(task_id))
                except Exception: raise HTTPException(502, "Workflow recovery failed") from None
            return self.snapshot(task_id, owner)

    def pending(self, owner):
        with self.lock:
            ids = [row[0] for row in self.connection.execute("SELECT id FROM ai_tasks WHERE owner=? ORDER BY created", (owner,))]
            return [task for task_id in ids if (task := self.get(task_id, owner))["status"] in ("AWAITING_REVIEW", "RUNNING")]

    def review(self, task_id, owner, approved, feedback, expected_reject_count):
        with self.lock:
            task = self.get(task_id, owner)
            if task["status"] != "AWAITING_REVIEW": raise HTTPException(409, "Task is not awaiting review")
            if task["rejectCount"] != expected_reject_count: raise HTTPException(409, "Review revision is stale")
            try:
                self.graph.invoke(Command(resume={"approved": approved, "feedback": feedback}), self.config(task_id))
            except Exception:
                raise HTTPException(502, "Workflow resume failed") from None
            self.connection.execute("UPDATE ai_tasks SET updated=? WHERE id=?", (now(), task_id))
            self.connection.commit()
            return self.snapshot(task_id, owner)


@lru_cache
def get_store():
    return WorkflowStore(os.environ.get("AI_CHECKPOINT_DB", "data/workflows.sqlite"))


class OwnerRequest(BaseModel):
    ownerId: int = Field(gt=0)


class TaskRequest(OwnerRequest):
    taskId: str = Field(min_length=1, max_length=100)


class SubmitRequest(OwnerRequest):
    question: str = Field(min_length=1, max_length=12000)
    maxRejects: int = Field(default=2, ge=1, le=10)


class ReviewRequest(TaskRequest):
    approved: bool
    feedback: str = Field(default="", max_length=4000)
    expectedRejectCount: int = Field(ge=0)


@router.post("/internal/v1/tasks/submit")
def submit(request: SubmitRequest, store=Depends(get_store)):
    return store.submit(request.question, request.ownerId, request.maxRejects)


@router.post("/internal/v1/tasks/get")
def get_task(request: TaskRequest, store=Depends(get_store)):
    return store.get(request.taskId, request.ownerId)


@router.post("/internal/v1/tasks/pending")
def pending(request: OwnerRequest, store=Depends(get_store)):
    return store.pending(request.ownerId)


@router.post("/internal/v1/tasks/review")
def review(request: ReviewRequest, store=Depends(get_store)):
    return store.review(request.taskId, request.ownerId, request.approved, request.feedback, request.expectedRejectCount)
