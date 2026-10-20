package com.bkanent.agent.memory;

import com.bkanent.common.memory.MemoryException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Public failure contains only safe classifications, never checkpoint/source/provider bodies. */
@RestControllerAdvice
public class SupervisorMemoryExceptionHandler {
    @ExceptionHandler(MemoryException.class)
    public ResponseEntity<Map<String,String>> handle(MemoryException exception) {
        int status = switch (exception.code()) {
            case AUTHENTICATION_FAILED, AUTHENTICATION_EXPIRED -> 401;
            case FORBIDDEN, WITHDRAWN, DELETED -> 403;
            case VERSION_CONFLICT, CURSOR_INVALID -> 409;
            case INVALID_ARGUMENT, INVALID_EXTRACTION, CONTEXT_BUDGET_EXCEEDED -> 422;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", "MEMORY_" + exception.code().name()));
    }
}
