package com.example.agent.service.matching;

/** Stable, non-sensitive failure details safe to display to the task owner. */
public class MatchTaskFailure extends RuntimeException {
    private final String code;

    public MatchTaskFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
