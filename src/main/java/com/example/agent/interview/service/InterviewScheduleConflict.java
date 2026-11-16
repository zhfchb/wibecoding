package com.example.agent.interview.service;



public class InterviewScheduleConflict extends RuntimeException {
    private final String code;
    public InterviewScheduleConflict(String code,String message) { super(message);this.code=code; }
    public String code() { return code; }
}
