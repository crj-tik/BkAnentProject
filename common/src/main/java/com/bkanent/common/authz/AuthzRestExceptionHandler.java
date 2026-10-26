package com.bkanent.common.authz;

import com.bkanent.common.model.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** HTTP adapter for transport-neutral Authz errors raised by domain service checks. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthzRestExceptionHandler {
    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<ApiResponse<Void>> handle(AuthzException failure) {
        String code = failure.code().name();
        return ResponseEntity.status(HttpStatus.valueOf(failure.code().httpStatus()))
                .body(ApiResponse.fail(code, code));
    }
}
