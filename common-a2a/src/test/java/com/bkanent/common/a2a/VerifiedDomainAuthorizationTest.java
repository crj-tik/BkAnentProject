package com.bkanent.common.a2a;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzRpcOperation;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.CallerProof;
import com.bkanent.common.authz.DelegatedAuthorizationRequest;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedDomainAuthorizationTest {
    private static final String BUSINESS_READ = "business.kpi.read.all";

    @Test
    void a2aToolChecksTheVerifiedParentContextLocally() {
        var request = delegatedRequest(List.of("agent.invoke.trade", BUSINESS_READ));
        var lease = A2aCredentialContext.Lease.versioned(request, "message/send", null);

        try (var ignored = A2aCredentialContext.open(lease)) {
            assertThatCode(() -> VerifiedDomainAuthorization.requireOperation(BUSINESS_READ))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> VerifiedDomainAuthorization.requireOperation("business.dashboard.read.all"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    @Test
    void missingRequestContextIsRejected() {
        assertThatThrownBy(() -> VerifiedDomainAuthorization.requireOperation(BUSINESS_READ))
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    void brokerScopeUsesOnlyVerifiedIdentityAndExplicitWideOperation() throws Exception {
        try (var ignored = McpAuthorizationScope.open(mcpVerified(
                List.of("contract.read.own"), Map.of("BROKER", 73L)))) {
            org.assertj.core.api.Assertions.assertThat(VerifiedDomainAuthorization.requireBrokerScope(
                    "contract.read.own", "contract.read.all")).isEqualTo(73L);
            assertThatThrownBy(() -> VerifiedDomainAuthorization.requireBrokerOwnership(
                    74L, "contract.read.own", "contract.read.all"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        try (var ignored = McpAuthorizationScope.open(mcpVerified(
                List.of("contract.read.all"), Map.of()))) {
            org.assertj.core.api.Assertions.assertThat(VerifiedDomainAuthorization.requireBrokerScope(
                    "contract.read.own", "contract.read.all")).isNull();
        }
        try (var ignored = McpAuthorizationScope.open(mcpVerified(
                List.of("contract.read.own"), Map.of()))) {
            assertThatThrownBy(() -> VerifiedDomainAuthorization.requireBrokerScope(
                    "contract.read.own", "contract.read.all"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private static McpAuthorizationContract.Verified mcpVerified(List<String> operations,
                                                                  Map<String, Long> identities) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext("1", 1, 1, hash, operations,
                "agent-service", "req-1", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 20, now + 21, identities, "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("1", 1, 1, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 10, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "contract-service", "1",
                operations, "run-1", null, "req-1", hash, hash, nonce, now - 1, now + 10);
        return new McpAuthorizationContract.Verified("1", "agent-service", "contract-service",
                operations.get(0), "req-1", "contract-tool", context, freshness, binding);
    }

    private static DelegatedAuthorizationRequest delegatedRequest(List<String> permissions) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext("1", 1, 1, hash, permissions,
                "business-service", "req-1", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 20, now + 21, Map.of(), "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("1", 1, 1, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 10, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "business-service", "1",
                List.of("agent.invoke.trade"), "run-1", "task-1", "req-1", hash, hash,
                nonce, now - 1, now + 10);
        var proof = new CallerProof("agent-service", "business-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                "req-1", hash, nonce, now - 1, now + 10, "Ed25519", "agent-current", null);
        return new DelegatedAuthorizationRequest(context, freshness, binding, proof);
    }
}
