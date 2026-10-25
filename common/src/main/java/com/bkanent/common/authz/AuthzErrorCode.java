package com.bkanent.common.authz;

/** Stable transport-neutral errors; adapters map these to HTTP/RPC responses. */
public enum AuthzErrorCode {
    INVALID_ARGUMENT(400),
    AUTHENTICATION_REQUIRED(401),
    AUTHENTICATION_FAILED(401),
    EXPIRED(401),
    PERMISSION_DENIED(403),
    CONTEXT_CONFLICT(409),
    REPLAY_DETECTED(409),
    AUTHORIZATION_UNAVAILABLE(503);

    private final int httpStatus;

    AuthzErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
