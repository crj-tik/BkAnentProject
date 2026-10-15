package com.bkanent.agent.studio;

import org.springframework.http.HttpStatus;

public final class StudioException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public StudioException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static StudioException missing() { return new StudioException(HttpStatus.NOT_FOUND, "WORK_NOT_FOUND", "工作不存在或当前账号无权访问"); }
    public static StudioException invalid(String message) { return new StudioException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message); }
    public static StudioException conflict(String code, String message) { return new StudioException(HttpStatus.CONFLICT, code, message); }
    public static StudioException unavailable(String message) { return new StudioException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE", message); }
}
