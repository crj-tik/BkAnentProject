package com.bkanent.agent.authorization;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentGovernanceOverrideEntity;
import com.bkanent.agent.mapper.AgentGovernanceOverrideMapper;
import com.bkanent.agent.model.distributed.GovernanceRateLimitOverrideRequest;
import com.bkanent.agent.security.AgentPrincipalContext;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.agent.service.PermissionAuditService;
import com.bkanent.agent.service.SupervisorGovernanceService;
import com.bkanent.agent.service.SupervisorRateLimiter;
import com.bkanent.agent.stream.SessionEventAuditService;
import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupervisorGovernanceAuthorizationAuditTest {
    private static final long NOW = 1_700_000_000L;

    @Test
    void recordsTrustedPermissionRevisionAndCredentialEpochForVersionedGovernance() throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519");
        KeyPair authority = keys.generateKeyPair();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        AgentAuthorizationProperties properties = new AgentAuthorizationProperties();
        properties.setEnabled(true);
        properties.setAuthorityPublicKeys(Map.of("auth-key",
                Base64.getEncoder().encodeToString(authority.getPublic().getEncoded())));
        var monitor = new AuthorizationVersionMonitor(users -> Map.of(), properties.authorityKeys(), clock);
        var consumer = new AgentAuthorizationConsumer(properties,
                new AuthorizationCache(10, 1_000_000, java.time.Duration.ofMinutes(5), 4), monitor, clock);
        var entry = consumer.verify(response(authority), binding(), "real-token", "POST", "/agent/probe", null,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuthPermissionRpcService legacyAuth = mock(AuthPermissionRpcService.class);
        AgentPermissionService permissions = new AgentPermissionService(legacyAuth, mock(PermissionAuditService.class));
        permissions.setAuthorizationProperties(properties);
        SessionEventAuditService audit = mock(SessionEventAuditService.class);
        AgentGovernanceOverrideMapper overrides = mock(AgentGovernanceOverrideMapper.class);
        when(overrides.selectList(any())).thenReturn(List.of());
        when(overrides.selectOne(any())).thenReturn(null);
        when(audit.summarize()).thenReturn(Map.of());
        DomainCatalog catalog = mock(DomainCatalog.class);
        when(catalog.snapshot()).thenReturn(new DomainCatalog.CatalogSnapshot(List.of(), List.of(), DomainCatalog.SOURCE_REGISTRY));
        AgentPrincipalContext principal = mock(AgentPrincipalContext.class);
        when(principal.userId()).thenReturn("42");
        var service = new SupervisorGovernanceService(new DistributedAgentProperties(), mock(SupervisorRateLimiter.class),
                audit, overrides, catalog, new ObjectMapper(), permissions, principal);

        try (var ignored = AgentAuthorizationScope.open(entry)) {
            service.overrideRateLimit(new GovernanceRateLimitOverrideRequest("supervisor.tasks", 5));
        }

        var recorded = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
        verify(audit).recordRequired(recorded.capture());
        assertThat(recorded.getValue().metadata()).containsEntry("actorId", "42")
                .containsEntry("authorizationVersion", "7:5")
                .containsEntry("permissionRevision", 7L)
                .containsEntry("credentialEpoch", 5L);
        verify(overrides).insert(any(AgentGovernanceOverrideEntity.class));
        verifyNoInteractions(legacyAuth);
    }

    private RequestBinding binding() {
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String nonce = nonce("ingress");
        return new RequestBinding("gateway", "agent-service", "POST", "/agent/probe", "/agent/probe",
                List.of(), "request-1", "run-1", "session-1", AuthzCanonicalHash.bodyHash(body),
                AuthzCanonicalHash.bodyHash(new byte[0]), nonce, NOW, NOW + 60);
    }

    private AuthzResponse response(KeyPair authority) {
        RequestBinding binding = binding();
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("42", 7, 5, false,
                List.of("agent.governance.manage"), null, AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String requestHash = AuthzCanonicalHash.resolveRequestHash("real-token", binding);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 7, 5, permissionHash,
                snapshot.permissions(), "agent-service", binding.requestId(), binding.runId(), binding.sessionId(),
                binding.policyDigest(), requestHash, binding.nonce(), NOW, NOW + 300, NOW + 300,
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 7, 5, AuthorizationStatus.ACTIVE,
                permissionHash, NOW, NOW + 30, nonce("head"), AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        return new AuthzResponse(snapshot, context, freshness);
    }

    private static String nonce(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
