package com.bkanent.customer.security;

import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.customer.config.CustomerAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CustomerAuthorizationIngressTest {
    @Test
    void bothConfigSourcesExposeSameSwitchAndAuthorityKey() throws Exception {
        Path root = Files.exists(Path.of("nacos/customer-service.yaml")) ? Path.of("") : Path.of("..");
        for (Path path : List.of(root.resolve("nacos/customer-service.yaml"),
                root.resolve("customer-service/src/main/resources/application.yml"))) {
            String yaml = Files.readString(path);
            assertTrue(yaml.contains("enabled: ${CUSTOMER_AUTHORIZATION_ENABLED:false}"));
            assertTrue(yaml.contains("auth-service-current: ${AUTH_SERVICE_IDENTITY_PUBLIC_KEY:}"));
        }
    }

    @Test
    void enabledIngressVerifiesSignedRequestAndClosesScope() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(CustomerAuthorizationProperties.class, CustomerAuthorizationIngressFilter.class)
                .withPropertyValues("customer.authorization.enabled=true",
                        "customer.authorization.authority-public-keys.auth-service-current="
                                + ServiceIdentityCrypto.encodePublicKey(key.getPublic()));
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var filter = context.getBean(CustomerAuthorizationIngressFilter.class);
            long now = Instant.now().getEpochSecond();
            String token = "customer-test-token", path = "/customers/1";
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            String hash = AuthzCanonicalHash.bodyHash(body);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String keyId = "auth-service-current";
            var binding = new RequestBinding("gateway", "customer-service", "PUT", path, path,
                    List.of(), "customer-request", null, null, hash, hash, nonce, now, now + 60);
            var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", 1, 1, false,
                    List.of("customer.update.own"), null, Map.of("BROKER", 41L),
                    AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
            var signed = AuthzSignature.sign(new AuthorizationContext("123", 1, 0, permissionHash,
                    List.of("customer.update.own"), "customer-service", binding.requestId(), null, null,
                    hash, AuthzCanonicalHash.resolveRequestHash(token, binding), nonce, now, now + 60,
                    now + 300, Map.of("BROKER", 41L), AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            var freshness = AuthzSignature.sign(new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                    permissionHash, now, now + 30, nonce, AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
            var request = new MockHttpServletRequest("PUT", path);
            request.setContent(body);
            request.addHeader("Authorization", "Bearer " + token);
            request.addHeader(AuthzTransportHeaders.RESPONSE_HEADER,
                    AuthzWireCodec.encodeResponse(new AuthzResponse(snapshot, signed, freshness)));
            request.addHeader(AuthzTransportHeaders.REQUEST_BINDING_HEADER, AuthzWireCodec.encodeBinding(binding));
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> {
                called.set(true);
                assertEquals(123L, AuthzRequestScope.actorId());
                assertEquals(41L, AuthzRequestScope.requireBrokerIdentity());
            });
            assertTrue(called.get());
            assertEquals(200, response.getStatus());
            assertFalse(AuthzRequestScope.present());

            var missing = new MockHttpServletRequest("PUT", path);
            var rejected = new MockHttpServletResponse();
            filter.doFilter(missing, rejected, (req, res) -> fail("unsigned request reached handler"));
            assertEquals(401, rejected.getStatus());
        });
    }
}
