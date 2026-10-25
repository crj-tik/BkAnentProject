package com.bkanent.common.authz;

/** Dedicated ingress transport only; neither header is an identity without verification. */
public final class AuthzTransportHeaders {
    public static final String RESPONSE_HEADER = "X-Bkanent-Authz-Response";
    public static final String REQUEST_BINDING_HEADER = "X-Bkanent-Authz-Request-Binding";

    private AuthzTransportHeaders() { }
}
