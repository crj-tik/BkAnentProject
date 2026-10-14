package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import java.util.function.Supplier;

/** Request-local dedicated credential; never filled from model metadata/userId or inherited by worker threads. */
public final class ListingRagCredentialContext {
    private static final ThreadLocal<RagCredentialContext> CURRENT = new ThreadLocal<>();
    private ListingRagCredentialContext() { }
    public static RagCredentialContext current() {
        RagCredentialContext context = CURRENT.get();
        return context == null ? com.bkanent.common.a2a.A2aCredentialContext.currentCredentials() : context;
    }
    public static Scope open(RagCredentialContext credential) {
        RagCredentialContext previous = CURRENT.get();
        CURRENT.set(credential);
        return () -> { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } };
    }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
    public static <T> T withCredential(RagCredentialContext credential, Supplier<T> action) {
        try (Scope ignored = open(credential)) { return action.get(); }
    }
    public static RagCredentialContext bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return new RagCredentialContext(authorization.substring(7));
    }
}
