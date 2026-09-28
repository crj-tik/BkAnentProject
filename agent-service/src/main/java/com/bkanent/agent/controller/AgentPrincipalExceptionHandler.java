package com.bkanent.agent.controller;

import com.bkanent.agent.security.AgentPrincipalException;
import com.bkanent.common.agent.PermissionErrorCodes;
import com.bkanent.common.model.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentPrincipalExceptionHandler {

    @ExceptionHandler(AgentPrincipalException.class)
    public ResponseEntity<ApiResponse<Void>> handlePrincipalException(AgentPrincipalException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(PermissionErrorCodes.PERMISSION_DENIED, exception.getMessage()));
    }
}
