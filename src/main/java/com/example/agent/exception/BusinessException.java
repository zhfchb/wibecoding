package com.example.agent.exception;

/**
 * 业务异常:用户名占用、参数非法等,统一返回 400
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
