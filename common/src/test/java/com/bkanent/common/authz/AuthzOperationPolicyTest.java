package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthzOperationPolicyTest {
    @Test
    void baselineActionsAreSignedButNeverPermissionGrants() {
        assertThat(AuthzOperationPolicy.baselineOperations()).hasSize(26);
        assertThat(AuthzOperationPolicy.isBaseline("customer.follow.write.own")).isTrue();
        assertThat(AuthzOperationPolicy.isBaseline("customer.delete.own")).isFalse();

        var contextOperations = AuthzOperationPolicy.contextOperations(Set.of("contract.seal.own"));
        assertThat(contextOperations).contains("customer.follow.write.own", "contract.seal.own");
        assertThat(AuthzOperationPolicy.requiredPermissions(contextOperations))
                .containsExactly("contract.seal.own");
    }

    @Test
    void baselineActionRequiresSignedExecutionContextButNotSnapshotGrant() {
        assertThat(AuthzOperationPolicy.allows("listing.read", List.of("listing.read"), List.of())).isTrue();
        assertThat(AuthzOperationPolicy.allows("listing.read", List.of(), List.of())).isFalse();
        assertThat(AuthzOperationPolicy.allows("contract.seal.own", List.of("contract.seal.own"), List.of())).isFalse();
        assertThat(AuthzOperationPolicy.allows("contract.seal.own", List.of("contract.seal.own"),
                List.of("contract.seal.own"))).isTrue();
        assertThat(AuthzOperationPolicy.allows("invented.action", List.of("invented.action"),
                List.of())).isFalse();
    }
}
