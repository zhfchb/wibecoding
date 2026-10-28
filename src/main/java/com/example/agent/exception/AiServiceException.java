package com.example.agent.exception;

/** Sanitized upstream failure; never includes request data or credentials. */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message) { super(message); }
}
