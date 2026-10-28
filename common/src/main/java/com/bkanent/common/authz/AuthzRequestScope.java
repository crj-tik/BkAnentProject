package com.bkanent.common.authz;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** One already verified HTTP request scope. It performs no Auth RPC and is never inherited by worker threads. */
public final class AuthzRequestScope {
    private static final ThreadLocal<AuthzResponse> CURRENT = new ThreadLocal<>();

    private AuthzRequestScope() { }

    public static Scope verifyAndOpen(AuthzResponse response, RequestBinding binding, String token,
                                      String audience, String method, String path, String rawQuery,
                                      byte[] body, Map<String, PublicKey> authorityKeys, Instant now) {
        AuthzWireCodec.verifyIngress(response, binding, token, audience, method,
                AuthzWireCodec.validatePath(path), AuthzWireCodec.parseQuery(rawQuery), body, authorityKeys, now);
        AuthzResponse previous = CURRENT.get();
        CURRENT.set(response);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    /**
     * Opens a downstream scope after its caller proof has been checked. The Auth-signed response
     * remains bound to the original service audience and request; this method does not perform Auth RPC.
     */
    public static Scope verifyAndOpenForwarded(AuthzResponse response, String expectedCallerService,
                                               Map<String, PublicKey> authorityKeys, Instant now) {
        AuthzContract.require(response != null && expectedCallerService != null);
        AuthorizationContext context = response.context();
        AuthzSignature.verifyResponse(response, expectedCallerService, context.requestId(),
                context.requestHash(), context.nonce(), authorityKeys, now);
        AuthzResponse previous = CURRENT.get();
        CURRENT.set(response);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public static boolean present() { return CURRENT.get() != null; }

    public static AuthzResponse requireCurrent() {
        AuthzResponse response = CURRENT.get();
        if (response == null) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        requireLive(response, Instant.now());
        return response;
    }

    public static long actorId() { return Long.parseLong(requireCurrent().context().actorId()); }

    public static boolean businessAdmin() { return requireCurrent().snapshot().businessAdmin(); }

    public static Long businessIdentity(String type) { return requireCurrent().context().businessIdentities().get(type); }

    public static long requireBrokerIdentity() {
        Long brokerId = businessIdentity("BROKER");
        AuthzContract.check(brokerId != null, AuthzErrorCode.PERMISSION_DENIED);
        return brokerId;
    }

    public static void requireOperation(String operation) {
        AuthzResponse response = requireCurrent();
        AuthzContract.check(AuthzOperationPolicy.allows(operation, response.context().allowedOperations(),
                response.snapshot().permissions()), AuthzErrorCode.PERMISSION_DENIED);
    }

    /** Admin scope is explicit business-wide; a missing broker mapping never turns into userId-as-brokerId. */
    public static void requireBrokerOwnership(Long resourceBrokerId, String operation, String businessWideOperation) {
        AuthzResponse response = requireCurrent();
        if (response.snapshot().businessAdmin()) {
            requireOperation(businessWideOperation);
            return;
        }
        requireOperation(operation);
        Long brokerId = response.context().businessIdentities().get("BROKER");
        AuthzContract.check(brokerId != null && resourceBrokerId != null && brokerId.equals(resourceBrokerId),
                AuthzErrorCode.PERMISSION_DENIED);
    }

    /** Every caller-local check also enforces the signed request, freshness and hard deadlines. */
    private static void requireLive(AuthzResponse response, Instant now) {
        AuthorizationContext context = response.context();
        AuthorizationSnapshot snapshot = response.snapshot();
        AuthzFreshness freshness = response.freshness();
        long second = now.getEpochSecond();
        AuthzContract.check(second < context.expiresAtEpochSecond()
                && second < context.runHardDeadlineEpochSecond()
                && second < freshness.notAfterEpochSecond()
                && (snapshot.grantDeadlineEpochSecond() == null
                    || second < snapshot.grantDeadlineEpochSecond()), AuthzErrorCode.EXPIRED);
    }

    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
