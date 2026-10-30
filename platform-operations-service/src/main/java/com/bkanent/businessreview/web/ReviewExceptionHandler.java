package com.bkanent.businessreview.web;

import com.bkanent.businessreview.domain.ReviewDependencyException;
import com.bkanent.businessreview.persistence.JdbcReviewRepository.ReviewConflictException;
import com.bkanent.businessreview.persistence.JdbcReviewRepository.ReviewNotFoundException;
import com.bkanent.common.model.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.bkanent.businessreview.web")
public class ReviewExceptionHandler {
    @ExceptionHandler(ReviewNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("REVIEW_NOT_FOUND", "Review resource was not found"));
    }

    @ExceptionHandler(ReviewConflictException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(ReviewConflictException failure) {
        HttpStatus status = switch (failure.code()) {
            case "INVALID_ARGUMENT", "INVALID_CURSOR", "INVALID_PLAN_DEADLINE", "PLAN_ACTIONS_REQUIRED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(failure.code(), "Review state or expected version conflicts"));
    }

    @ExceptionHandler(ReviewDependencyException.class)
    public ResponseEntity<ApiResponse<Void>> dependency(ReviewDependencyException failure) {
        HttpStatus status = switch (failure.code()) {
            case "SOURCE_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "SOURCE_VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            case "UNSUPPORTED_CONTRACT_VERSION", "INVALID_ARGUMENT", "FIXTURE_NOT_FOUND" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.code(), "Review source request failed"));
    }
}
