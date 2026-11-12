package com.bkanent.notification.security;

import com.bkanent.common.rpc.AuthorizedNotificationPermit;
import com.bkanent.common.rpc.AuthorizedNotificationPermitSignature;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.notification.config.NotificationAuthorizationProperties;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.time.Instant;

/** Verifies the single supported background notification capability locally, without Auth RPC. */
@Component
public class AuthorizedNotificationPermitVerifier {
    private static final String EXPECTED_CALLER = "customer-service";
    private static final long MAX_LIFETIME_SECONDS = 60;
    private static final long FUTURE_CLOCK_SKEW_SECONDS = 5;

    private final NotificationAuthorizationProperties properties;

    public AuthorizedNotificationPermitVerifier(NotificationAuthorizationProperties properties) {
        this.properties = properties;
    }

    public void verify(AuthorizedNotificationPermit permit, Instant now) {
        if (permit == null || now == null || !EXPECTED_CALLER.equals(permit.callerService())
                || !AuthorizedNotificationPermit.CUSTOMER_ENTRUST_REMINDER_OPERATION.equals(permit.operationCode())
                || !AuthorizedNotificationPermit.CUSTOMER_ENTRUST_EXPIRING_SCENE.equals(permit.sceneCode())
                || permit.expiresAtEpochSecond() - permit.issuedAtEpochSecond() > MAX_LIFETIME_SECONDS
                || permit.issuedAtEpochSecond() > now.getEpochSecond() + FUTURE_CLOCK_SKEW_SECONDS
                || now.getEpochSecond() >= permit.expiresAtEpochSecond()) {
            throw new IllegalArgumentException("Notification permit is outside its allowed caller, operation or time scope");
        }
        var caller = properties.getTrustedSystemCallers().get(permit.keyId());
        if (caller == null || !EXPECTED_CALLER.equals(caller.getCallerService())
                || caller.getPublicKey() == null || caller.getPublicKey().isBlank()) {
            throw new IllegalArgumentException("Notification caller key is not registered");
        }
        PublicKey key = ServiceIdentityCrypto.decodePublicKey(caller.getPublicKey());
        AuthorizedNotificationPermitSignature.verify(permit, key);
    }
}
