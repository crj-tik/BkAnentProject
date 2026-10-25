package com.bkanent.common.authz;

import java.io.Serial;

/** Never includes credentials, caller input, signed DTOs or nested exceptions in its message. */
public final class AuthzException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final AuthzErrorCode code;

    public AuthzException(AuthzErrorCode code) {
        super(code == null ? AuthzErrorCode.INVALID_ARGUMENT.name() : code.name());
        this.code = code == null ? AuthzErrorCode.INVALID_ARGUMENT : code;
    }

    public AuthzErrorCode code() {
        return code;
    }
}
