package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.*;
import com.bkanent.common.rag.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class A2aBeaconStoreTest {
    private AuthTokenService tokens;
    private UserAccountService users;
    private UserManagementService roles;
    private UserAccountEntity account;
    private A2aBeaconStore store;
    private String token;

    @BeforeEach void setup() {
        var properties = new AuthTokenProperties();
        properties.setSecret("unit-test-only-secret-at-least-32-bytes");
        tokens = new AuthTokenService(properties, new InMemoryTokenRevocationStore());
        tokens.initialize();
        users = mock(UserAccountService.class); roles = mock(UserManagementService.class);
        account = new UserAccountEntity(); account.setId(7L); account.setDeleted(0);
        account.setAccountStatus("ACTIVE"); account.setRoleCode("BROKER");
        when(users.getById(7L)).thenReturn(account);
        when(roles.isActiveRole("BROKER")).thenReturn(true);
        when(roles.isActiveRole("ADMIN")).thenReturn(true);
        store = new A2aBeaconStore(tokens, users, roles);
        token = tokens.issueAccessToken(account);
    }
    private A2aBeaconBinding binding(String audience) {
        return new A2aBeaconBinding(1, "rpc", "agent-service", audience, "7", "message/send", "0".repeat(64), "task");
    }
    @Test void exactTransportFactsAndResolvingAudienceAreRequired() {
        var binding = binding("marketing-content-service");
        var beacon = store.issue(binding, new RagCredentialContext(token));
        var credentials = RagCredentialContext.beacon(beacon.value());
        assertThat(store.resolve(binding, credentials, "marketing-content-service").userId()).isEqualTo(7L);
        assertThat(beacon.value()).hasSize(43).doesNotContain(token);
        assertThat(beacon.toString()).doesNotContain(beacon.value());
        assertThatThrownBy(() -> store.resolve(binding, credentials, "listing-master-service")).isInstanceOf(RagException.class);
        var altered = new A2aBeaconBinding(1, "other", "agent-service", binding.audience(), "7", binding.method(), binding.bodyHash(), "task");
        assertThatThrownBy(() -> store.resolve(altered, credentials, binding.audience())).isInstanceOf(RagException.class);
    }
    @Test void marketingDelegatesPrincipalOnlyToExplicitListingMemoryRagConsumers() {
        var credentials = RagCredentialContext.beacon(store.issue(binding("marketing-content-service"), new RagCredentialContext(token)).value());
        for (String consumer : java.util.List.of("listing-master-service", "memory-service", "rag-service"))
            assertThat(store.principal(credentials, consumer).userId()).isEqualTo(7L);
        for (String consumer : java.util.List.of("auth-service", "platform-operations-service", "notification-service", "customer-service"))
            assertThatThrownBy(() -> store.principal(credentials, consumer)).isInstanceOf(RagException.class);
        var trade = RagCredentialContext.beacon(store.issue(binding("business-service"), new RagCredentialContext(token)).value());
        assertThatThrownBy(() -> store.principal(trade, "rag-service")).isInstanceOf(RagException.class);
    }
    @Test void originalTokenAndCurrentAccountRoleAreRecheckedEveryTime() {
        var binding = binding("listing-master-service");
        var credentials = RagCredentialContext.beacon(store.issue(binding, new RagCredentialContext(token)).value());
        for (String status : java.util.List.of("DISABLED", "DEPARTED")) {
            account.setAccountStatus(status);
            assertThatThrownBy(() -> store.resolve(binding, credentials, binding.audience())).isInstanceOf(RagException.class);
        }
        account.setAccountStatus("ACTIVE"); account.setDeleted(1);
        assertThatThrownBy(() -> store.principal(credentials, "rag-service")).isInstanceOf(RagException.class);
        account.setDeleted(0); account.setRoleCode("MANAGER"); when(roles.isActiveRole("MANAGER")).thenReturn(true);
        assertThatThrownBy(() -> store.principal(credentials, "rag-service")).isInstanceOf(RagException.class);
        account.setRoleCode("BROKER"); when(roles.isActiveRole("BROKER")).thenReturn(false);
        assertThatThrownBy(() -> store.principal(credentials, "rag-service")).isInstanceOf(RagException.class);
        when(roles.isActiveRole("BROKER")).thenReturn(true); tokens.revoke(token);
        assertThatThrownBy(() -> store.resolve(binding, credentials, binding.audience())).isInstanceOf(RagException.class);
    }
    @Test void leaseCannotOutliveOriginalCredentialOrSurviveRestart() {
        long expiry = Instant.now().getEpochSecond() + 120;
        var beacon = store.issue(binding("listing-master-service"), new RagCredentialContext(token), expiry);
        assertThat(beacon.expiresAtEpochSecond()).isLessThanOrEqualTo(expiry).isGreaterThan(Instant.now().getEpochSecond() + 60);
        var restarted = new A2aBeaconStore(tokens, users, roles);
        assertThatThrownBy(() -> restarted.resolve(binding("listing-master-service"), RagCredentialContext.beacon(beacon.value()), "listing-master-service"))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> store.issue(binding("listing-master-service"), new RagCredentialContext(token), 1))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> store.issue(binding("listing-master-service"), new RagCredentialContext(tokens.issueRefreshToken(account))))
                .isInstanceOf(RagException.class);
    }
}
