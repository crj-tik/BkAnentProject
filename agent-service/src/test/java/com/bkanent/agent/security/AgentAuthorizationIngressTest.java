package com.bkanent.agent.security;

import com.bkanent.agent.authorization.*;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentAuthorizationIngressTest {
    private static final long NOW = 1700000000;
    private final KeyPair key;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private final AuthPermissionRpcService auth = mock(AuthPermissionRpcService.class);
    private final AgentAuthorizationProperties properties = new AgentAuthorizationProperties();
    private final AgentRagCredentialLeaseStore leases;
    private final AgentAuthorizationConsumer consumer;
    private final AgentRagCredentialFilter filter;

    AgentAuthorizationIngressTest() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        properties.setEnabled(true);
        properties.setAuthorityPublicKeys(Map.of("auth-key", Base64.getEncoder().encodeToString(key.getPublic().getEncoded())));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("auth", auth);
        leases = new AgentRagCredentialLeaseStore(new AgentRagConsumerProperties(), beans.getBeanProvider(AuthPermissionRpcService.class), clock);
        var monitor = new AuthorizationVersionMonitor(users -> Map.of(), Map.of("auth-key", key.getPublic()), clock);
        consumer = new AgentAuthorizationConsumer(properties,
                new AuthorizationCache(10, 100000, Duration.ofMinutes(5), 2), monitor, clock);
        beans.registerSingleton("consumer", consumer);
        filter = new AgentRagCredentialFilter(leases, properties, beans.getBeanProvider(AgentAuthorizationConsumer.class));
    }

    @Test void actualFilterReplaysBytesAndReaderBindsActorWithNoAuthRpcAndClearsNodeThread() throws Exception {
        byte[] body = " {\"message\":\"你好\"} \n".getBytes(StandardCharsets.UTF_8);
        var proof = proof(body, "run", "session");
        var request = request(body, proof);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            var actual = (jakarta.servlet.http.HttpServletRequest) incoming;
            assertThat(actual.getInputStream().readAllBytes()).isEqualTo(body);
            assertThat(actual.getReader().lines().reduce("", (a, b) -> a + b)).contains("你好");
            var principal = new AgentPrincipalContext(actual, auth, properties);
            assertThat(principal.userId()).isEqualTo("42");
            assertThat(leases.currentUserId()).isEqualTo("42");
            assertThat(leases.verifiedIdentity("42").principal().userId()).isEqualTo(42L);
            leases.capture("run", "42");
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(AgentAuthorizationScope.current()).isNull();
        assertThat(request.getAttribute(AgentAuthorizationScope.VERIFIED_ATTRIBUTE)).isNull();
        try (var scope = leases.openLease("run", "42")) {
            assertThat(AgentAuthorizationScope.current()).isNotNull();
            assertThat(leases.currentUserId()).isEqualTo("42");
        }
        assertThat(AgentAuthorizationScope.current()).isNull();
        verifyNoInteractions(auth);
    }

    @Test void tamperedBodyOrDuplicateHeadersNeverExecuteAndNeverFallback() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var proof = proof(body, "run", "session");
        var tampered = request("{\"extra\":1}".getBytes(StandardCharsets.UTF_8), proof);
        var response = new MockHttpServletResponse();
        filter.doFilter(tampered, response, (a, b) -> fail("must reject before execution"));
        assertThat(response.getStatus()).isEqualTo(409);
        for (String header : List.of("Authorization", AuthzTransportHeaders.RESPONSE_HEADER, AuthzTransportHeaders.REQUEST_BINDING_HEADER)) {
            var duplicated = request(body, proof);
            duplicated.addHeader(header, duplicated.getHeader(header));
            response = new MockHttpServletResponse();
            filter.doFilter(duplicated, response, (a, b) -> fail("duplicate headers must reject"));
            assertThat(response.getStatus()).isEqualTo(401);
        }
        var forged = request(body, proof);
        forged.removeHeader(AuthzTransportHeaders.RESPONSE_HEADER);
        forged.addHeader(com.bkanent.common.rpc.AuthPrincipalContext.MARKER_HEADER, com.bkanent.common.rpc.AuthPrincipalContext.MARKER_VALUE);
        response = new MockHttpServletResponse();
        filter.doFilter(forged, response, (a, b) -> fail("marker cannot replace signed proof"));
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(auth);
        assertThat(AgentAuthorizationScope.current()).isNull();
    }

    @Test void llmJsonIdentityClaimsNeverReplaceSignedActorAndBodyMutationRejects() throws Exception {
        byte[] body = "{\"actorId\":\"999\",\"sessionId\":\"forged\",\"businessAdmin\":true,\"permissions\":[\"*\"]}"
                .getBytes(StandardCharsets.UTF_8);
        var proof = proof(body, "run", "session");
        var accepted = new MockHttpServletResponse();
        filter.doFilter(request(body, proof), accepted, (incoming, outgoing) -> {
            var actual = (jakarta.servlet.http.HttpServletRequest) incoming;
            assertThat(new AgentPrincipalContext(actual, auth, properties).userId()).isEqualTo("42");
            assertThat(actual.getInputStream().readAllBytes()).isEqualTo(body);
        });
        assertThat(accepted.getStatus()).isEqualTo(200);

        byte[] changed = new String(body, StandardCharsets.UTF_8).replace("\"999\"", "\"998\"")
                .getBytes(StandardCharsets.UTF_8);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request(changed, proof), rejected, (incoming, outgoing) -> fail("tampered JSON must not execute"));
        assertThat(rejected.getStatus()).isEqualTo(409);
        verifyNoInteractions(auth);
        assertThat(AgentAuthorizationScope.current()).isNull();
    }

    @Test void nullRunProofCanBindActorButCannotCaptureExecutionAndDoesNotResign() throws Exception {
        byte[] body = new byte[0];
        var proof = proof(body, null, null);
        var response = new MockHttpServletResponse();
        filter.doFilter(request(body, proof), response, (incoming, outgoing) -> {
            assertThat(new AgentPrincipalContext((jakarta.servlet.http.HttpServletRequest) incoming, auth, properties).userId()).isEqualTo("42");
            leases.capture("new-run", "42");
        });
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(proof.response.context().runId()).isNull();
        assertThat(AgentAuthorizationScope.current()).isNull();
        verifyNoInteractions(auth);
    }

    @Test void realMockMvcRequestScopedPrincipalUsesActualFilterAndRawJsonParser() throws Exception {
        try (var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("auth", auth);
                factory.registerSingleton("properties", properties);
            });
            context.register(WebConfiguration.class);
            context.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
            byte[] body = "{\"message\":\"raw 你好\"}".getBytes(StandardCharsets.UTF_8);
            var proof = proof(body, "run", "session");
            mvc.perform(post("/agent/probe").header("Authorization", "Bearer real-token")
                    .header(AuthzTransportHeaders.RESPONSE_HEADER, AuthzWireCodec.encodeResponse(proof.response))
                    .header(AuthzTransportHeaders.REQUEST_BINDING_HEADER, AuthzWireCodec.encodeBinding(proof.binding))
                    .contentType("application/json").content(body)).andExpect(status().isOk());
            assertThat(AgentAuthorizationScope.current()).isNull();
            verifyNoInteractions(auth);
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class WebConfiguration {
        @org.springframework.context.annotation.Bean
        com.bkanent.agent.orchestration.OrchestrationStore store() {
            return mock(com.bkanent.agent.orchestration.OrchestrationStore.class);
        }
        @org.springframework.context.annotation.Bean
        @org.springframework.web.context.annotation.RequestScope
        AgentPrincipalContext principal(jakarta.servlet.http.HttpServletRequest request, AuthPermissionRpcService auth,
                                        AgentAuthorizationProperties properties) {
            return new AgentPrincipalContext(request, auth, properties);
        }
        @org.springframework.context.annotation.Bean ProbeController probe(AgentPrincipalContext principal) { return new ProbeController(principal); }
    }
    @RestController static class ProbeController {
        private final AgentPrincipalContext principal;
        ProbeController(AgentPrincipalContext principal) { this.principal = principal; }
        @PostMapping("/agent/probe") Map<String, String> probe(@RequestBody Map<String, String> body) {
            return Map.of("actor", principal.userId(), "message", body.get("message"));
        }
    }
    private MockHttpServletRequest request(byte[] body, Proof proof) {
        var request = new MockHttpServletRequest("POST", "/agent/probe");
        request.setServletPath("/agent/probe");
        request.setCharacterEncoding("UTF-8");
        request.setContent(body);
        request.addHeader("Authorization", "Bearer real-token");
        request.addHeader(AuthzTransportHeaders.RESPONSE_HEADER, AuthzWireCodec.encodeResponse(proof.response));
        request.addHeader(AuthzTransportHeaders.REQUEST_BINDING_HEADER, AuthzWireCodec.encodeBinding(proof.binding));
        return request;
    }
    private Proof proof(byte[] body, String run, String session) {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String policy = AuthzCanonicalHash.bodyHash(new byte[0]);
        var binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/probe", "/agent/probe", List.of(),
                "request", run, session, AuthzCanonicalHash.bodyHash(body), policy, nonce, NOW, NOW + 60);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("42", 1, 1, false, List.of("agent.chat.use"),
                null, AuthzContract.ALGORITHM, "auth-key", null), key.getPrivate());
        String hash = AuthzCanonicalHash.snapshotHash(snapshot);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 1, 0, hash, snapshot.permissions(), "agent-service",
                "request", run, session, policy, AuthzCanonicalHash.resolveRequestHash("real-token", binding), nonce,
                NOW, NOW + 300, NOW + 300, AuthzContract.ALGORITHM, "auth-key", null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 1, 0, AuthorizationStatus.ACTIVE, hash, NOW,
                NOW + 30, nonce, AuthzContract.ALGORITHM, "auth-key", null), key.getPrivate());
        return new Proof(binding, new AuthzResponse(snapshot, context, freshness));
    }
    private record Proof(RequestBinding binding, AuthzResponse response) { }
}
