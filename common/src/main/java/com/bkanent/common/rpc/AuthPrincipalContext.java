package com.bkanent.common.rpc;

/**
 * Names used for the gateway-injected internal authentication context.
 */
public final class AuthPrincipalContext {

    public static final String USER_ID_HEADER = "X-Authenticated-User-Id";
    public static final String MARKER_HEADER = "X-Authenticated-Principal";
    public static final String MARKER_VALUE = "gateway";

    private AuthPrincipalContext() {
    }
}
