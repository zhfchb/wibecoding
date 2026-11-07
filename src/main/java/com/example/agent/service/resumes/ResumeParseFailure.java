package com.example.agent.service.resumes;

public class ResumeParseFailure extends RuntimeException {
    private final String code;
    public ResumeParseFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
