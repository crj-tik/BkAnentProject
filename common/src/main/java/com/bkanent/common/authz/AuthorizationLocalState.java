package com.bkanent.common.authz;

/** Local execution state, distinct from the authority's account AuthorizationStatus. */
public enum AuthorizationLocalState {
    VALID, DIRTY, EXPIRED, UNVERIFIED
}
