package com.bkanent.marketing.campaign;

import com.bkanent.common.authz.*;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampaignVersionedAuthorizationTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuthPermissionRpcService> provider = mock(ObjectProvider.class);
    private final CampaignActorResolver actors = new CampaignActorResolver(provider);
    private final CampaignAuthorizationService permissions = new CampaignAuthorizationService(provider);

    @Test void rejectsMissingOrWrongActorAndUnlistedPermission() throws Exception {
        assertThrows(AuthzException.class, actors::currentActorId);
        try (var ignored = McpAuthorizationScope.open(context())) {
            assertEquals(42, actors.currentActorId());
            assertDoesNotThrow(() -> permissions.require(42, "marketing.campaign.prepare"));
            assertThrows(CampaignDomainException.class, () -> permissions.require(43, "marketing.campaign.prepare"));
            assertThrows(AuthzException.class, () -> permissions.require(42, "marketing.campaign.publish"));
        }
        verifyNoInteractions(provider);
    }

    private McpAuthorizationContract.Verified context() {
        long now = Instant.now().getEpochSecond();
        String hash = "0".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String operation = "marketing.campaign.prepare";
        var context = new AuthorizationContext("42", 1, 1, hash, List.of(operation), "marketing-content-service",
                "req-1", "run-1", "session-1", hash, hash, nonce, now - 1, now + 60, now + 60,
                Map.of("BROKER", 7L), "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("42", 1, 1, AuthorizationStatus.ACTIVE, hash,
                now - 1, now + 20, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "marketing-content-service", "42", List.of(operation),
                "run-1", null, "req-1", hash, hash, nonce, now - 1, now + 20);
        return new McpAuthorizationContract.Verified("42", "agent-service", "marketing-content-service", operation,
                "req-1", "prepare", context, freshness, binding);
    }
}
