package com.example.agent.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.example.agent.service.company.InterviewScheduleConflict.class)
    public ResponseEntity<ApiErrorResponse> handleInterviewSchedule(com.example.agent.service.company.InterviewScheduleConflict ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(409,ex.code(),ex.getMessage()));
    }
    @ExceptionHandler(com.example.agent.service.materials.MaterialFailure.class)
    public ResponseEntity<ApiErrorResponse> handleMaterial(com.example.agent.service.materials.MaterialFailure ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(409,ex.code(),ex.getMessage()));
    }
    @ExceptionHandler(com.example.agent.service.resumes.ResumeParseFailure.class)
    public ResponseEntity<ApiErrorResponse> handleResumeParse(com.example.agent.service.resumes.ResumeParseFailure ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(409,ex.code(),ex.getMessage()));
    }
    @ExceptionHandler(com.example.agent.service.matching.MatchTaskFailure.class)
    public ResponseEntity<ApiErrorResponse> handleMatchTask(com.example.agent.service.matching.MatchTaskFailure ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(409, ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleAiService(AiServiceException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorResponse.of(502, "Bad Gateway", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiErrorResponse.of(ex.getStatusCode().value(), "Request rejected", "Request rejected"));
    }

    /** 业务异常:用户名占用等,返回 400 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad Request", ex.getMessage()));
    }

    /** 入参校验失败(@Valid),返回 400 + 所有字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad Request", message));
    }

    @ExceptionHandler({IllegalArgumentException.class, UnsupportedDocumentTypeException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "Bad Request", ex.getMessage()));
    }

    /** 认证失败:用户名密码错误,返回 401 */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(401, "Unauthorized", "用户名或密码错误"));
    }

    /** 其他认证异常(用户不存在等),返回 401 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(401, "Unauthorized", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "服务器内部错误"));
    }
}
