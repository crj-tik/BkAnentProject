package com.bkanent.common.authz;

/** Account state from the authorization authority, not a local cache state. */
public enum AuthorizationStatus {
    ACTIVE, DISABLED, DELETED, LOCKED
}
