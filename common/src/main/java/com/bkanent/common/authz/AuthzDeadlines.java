package com.bkanent.common.authz;

/** Fixed deadlines in epoch seconds. No helper accepts cache TTL or renews an established run. */
public final class AuthzDeadlines {
    public static final long CONTEXT_SECONDS = 300;
    public static final long REQUEST_SECONDS = 60;
    public static final long FRESHNESS_SECONDS = 30;

    private AuthzDeadlines() { }

    /** Only a new, genuinely authenticated entry may establish a new hard deadline. */
    public static long entryRunHardDeadline(long authenticatedAt, long accessExpiresAt, Long grantDeadline) {
        return bounded(authenticatedAt, CONTEXT_SECONDS, accessExpiresAt, Long.MAX_VALUE, grantDeadline);
    }

    /** Preserve both original parent expiry and original run deadline across refresh/rebuild. */
    public static long contextExpiresAt(long now, long parentExpiresAt, long runHardDeadline, Long grantDeadline) {
        return bounded(now, CONTEXT_SECONDS, parentExpiresAt, runHardDeadline, grantDeadline);
    }

    public static long requestExpiresAt(long now, long contextExpiresAt) {
        return bounded(now, REQUEST_SECONDS, contextExpiresAt, Long.MAX_VALUE, null);
    }

    public static long freshnessNotAfter(long authorityReadStartedAt) {
        AuthzContract.require(authorityReadStartedAt > 0 && authorityReadStartedAt <= Long.MAX_VALUE - FRESHNESS_SECONDS);
        return authorityReadStartedAt + FRESHNESS_SECONDS;
    }

    public static long effectiveDeadline(AuthorizationContext context, AuthorizationSnapshot snapshot,
                                         AuthzFreshness freshness) {
        new AuthzResponse(snapshot, context, freshness);
        long deadline = Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond());
        deadline = Math.min(deadline, freshness.notAfterEpochSecond());
        return snapshot.grantDeadlineEpochSecond() == null ? deadline
                : Math.min(deadline, snapshot.grantDeadlineEpochSecond());
    }

    private static long bounded(long now, long seconds, long parent, long run, Long grant) {
        AuthzContract.require(now > 0 && now <= Long.MAX_VALUE - seconds && parent > 0 && run > 0
                && (grant == null || grant > 0));
        long expires = Math.min(now + seconds, Math.min(parent, run));
        if (grant != null) expires = Math.min(expires, grant);
        AuthzContract.check(expires > now, AuthzErrorCode.EXPIRED);
        return expires;
    }
}
