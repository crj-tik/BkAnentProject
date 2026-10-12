package com.bkanent.memory.controller;

import com.bkanent.common.rag.RagException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class MemoryRagExceptionHandler {
    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, Object>> handle(RagException ex) {
        HttpStatus status = switch (ex.code()) {
            case AUTHENTICATION_FAILED, AUTHENTICATION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("success", false, "code", ex.code().name()));
    }
}
