package com.bkanent.auth.service;

import org.springframework.http.HttpStatus;

public class UserManagementException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public UserManagementException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
