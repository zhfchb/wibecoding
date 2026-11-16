package com.example.agent.exception;



/** A bounded retry of an idempotent vector-store request did not recover transport access. */
public class VectorStoreUnavailableException extends RuntimeException {
    public VectorStoreUnavailableException(Throwable cause) {
        super("向量库连接暂时不可用，请稍后重试；上传失败的资料不会显示为已完成", cause);
    }
}
