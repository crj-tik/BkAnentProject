package com.bkanent.common.authz;

/** Service-operation ACL entries, separate from users' business permissions. */
public enum AuthzRpcOperation {
    RESOLVE_REQUEST_AUTHORIZATION, VALIDATE_DELEGATION
}
