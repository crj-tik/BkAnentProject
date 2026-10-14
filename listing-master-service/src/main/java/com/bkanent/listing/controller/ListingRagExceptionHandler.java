package com.bkanent.listing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.rag.RagException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Only public RAG codes and sanitized schema fields may cross the domain HTTP boundary. */
@RestControllerAdvice(assignableTypes = ListingRagController.class)
public class ListingRagExceptionHandler {
    @ExceptionHandler(RagException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handle(RagException exception) {
        HttpStatus status = switch (exception.code()) {
            case AUTHENTICATION_FAILED, AUTHENTICATION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, ENDPOINT_POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case INVALID_ARGUMENT, RESOURCE_SCOPE_TOO_LARGE -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INDEX_CONFLICT -> HttpStatus.CONFLICT;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case SPACE_NOT_READY, DEPENDENCY_UNAVAILABLE, RERANK_PROTOCOL_ERROR,
                    SCORING_PROFILE_MISMATCH -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = exception.code().name();
        Map<String, String> data = exception.field() == null ? null : Map.of("field", exception.field());
        return ResponseEntity.status(status).body(new ApiResponse<>(false, code, code, data));
    }
}
