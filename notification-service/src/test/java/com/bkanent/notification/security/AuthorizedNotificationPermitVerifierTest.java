package com.bkanent.notification.security;

import com.bkanent.common.rpc.AuthorizedNotificationPermit;
import com.bkanent.common.rpc.AuthorizedNotificationPermitSignature;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.notification.config.NotificationAuthorizationProperties;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AuthorizedNotificationPermitVerifierTest {
    @Test
    void acceptsOnlyShortLivedCustomerEntrustPermitAndRejectsAlteredRecipient() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var properties = new NotificationAuthorizationProperties();
        var caller = new NotificationAuthorizationProperties.TrustedSystemCaller();
        caller.setCallerService("customer-service");
        caller.setPublicKey(ServiceIdentityCrypto.encodePublicKey(pair.getPublic()));
        properties.setTrustedSystemCallers(Map.of("customer-service-current", caller));
        var verifier = new AuthorizedNotificationPermitVerifier(properties);
        long issued = 1_800_000_000L;
        var unsigned = new AuthorizedNotificationPermit("customer-service", "customer-service-current",
                AuthorizedNotificationPermit.CUSTOMER_ENTRUST_REMINDER_OPERATION, 42L,
                AuthorizedNotificationPermit.CUSTOMER_ENTRUST_EXPIRING_SCENE, "房屋委托即将到期", "请及时跟进",
                "customer-entrust-expiring:7:2026-12-01", issued, issued + 30,
                AuthorizedNotificationPermitSignature.payloadHash(
                        AuthorizedNotificationPermit.CUSTOMER_ENTRUST_EXPIRING_SCENE, "房屋委托即将到期", "请及时跟进"), null);
        var valid = AuthorizedNotificationPermitSignature.sign(unsigned, pair.getPrivate());

        assertDoesNotThrow(() -> verifier.verify(valid, Instant.ofEpochSecond(issued + 1)));
        var altered = new AuthorizedNotificationPermit(valid.callerService(), valid.keyId(), valid.operationCode(),
                43L, valid.sceneCode(), valid.title(), valid.content(), valid.idempotencyKey(),
                valid.issuedAtEpochSecond(), valid.expiresAtEpochSecond(), valid.payloadHash(), valid.signature());
        assertThatThrownBy(() -> verifier.verify(altered, Instant.ofEpochSecond(issued + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify(valid, Instant.ofEpochSecond(issued + 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
