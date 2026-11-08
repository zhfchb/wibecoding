package com.example.agent.service.company;

public class InterviewScheduleConflict extends RuntimeException {
    private final String code;
    public InterviewScheduleConflict(String code,String message) { super(message);this.code=code; }
    public String code() { return code; }
}
