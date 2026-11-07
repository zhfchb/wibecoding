package com.example.agent.service.materials;

public class MaterialFailure extends RuntimeException {
    private final String code;
    public MaterialFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
