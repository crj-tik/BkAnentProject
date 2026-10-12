package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import java.util.function.Supplier;

/** Request-local dedicated credential; never filled from model metadata/userId or inherited by worker threads. */
public final class ListingRagCredentialContext {
    private static final ThreadLocal<RagCredentialContext> CURRENT = new ThreadLocal<>();
    private ListingRagCredentialContext() { }
    public static RagCredentialContext current() {
        RagCredentialContext context = CURRENT.get();
        if (context == null) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        return context;
    }
    public static <T> T withCredential(RagCredentialContext credential, Supplier<T> action) {
        RagCredentialContext previous = CURRENT.get();
        try { CURRENT.set(credential); return action.get(); }
        finally { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } }
    }
    public static RagCredentialContext bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return new RagCredentialContext(authorization.substring(7));
    }
}
