package com.bkanent.knowledge.controller;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.source.KnowledgeSourceException;
import com.bkanent.knowledge.source.SourceState;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class KnowledgeApiExceptionHandler {
    @ExceptionHandler(KnowledgeOperationException.class)
    public ResponseEntity<ApiResponse<Void>> operation(KnowledgeOperationException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.fail(failure.code(), failure.code()));
    }

    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<ApiResponse<Void>> authorization(AuthzException failure) {
        return ResponseEntity.status(failure.code().httpStatus())
                .body(ApiResponse.fail(failure.code().name(), failure.code().name()));
    }

    @ExceptionHandler(KnowledgeSourceException.class)
    public ResponseEntity<ApiResponse<Void>> source(KnowledgeSourceException failure) {
        SourceState state = failure.state();
        int status = switch (state) {
            case NOT_ACCESSIBLE -> 404;
            case STALE -> 409;
            case UNAVAILABLE -> 503;
            case CURRENT -> 500;
        };
        String code = state == SourceState.UNAVAILABLE ? "DEPENDENCY_UNAVAILABLE"
                : state == SourceState.STALE ? "SOURCE_VERSION_CONFLICT" : state.name();
        return ResponseEntity.status(status).body(ApiResponse.fail(code, code));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(DataAccessException failure) {
        return ResponseEntity.status(503).body(ApiResponse.fail("DEPENDENCY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception failure) {
        return ResponseEntity.internalServerError().body(ApiResponse.fail("INTERNAL_ERROR", "INTERNAL_ERROR"));
    }
}
