package com.bkanent.knowledge.security;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.a2a.VerifiedKnowledgeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAuthorizationFailClosedTest {
    @Test
    void ownActorComesFromVerifiedContextAndRequiresTheExactOperation() {
        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.CREATE_OWN), Map.of())) {
            assertThat(KnowledgeAuthorization.requireOwnActor(KnowledgeOperations.CREATE_OWN)).isEqualTo("1");
            assertDenied(() -> KnowledgeAuthorization.requireOwnActor(KnowledgeOperations.EDIT_OWN));
        }
    }

    @Test
    void agentInvocationDoesNotGrantKnowledgeMutation() {
        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.AGENT_INVOKE), Map.of())) {
            assertThatCode(KnowledgeAuthorization::requireAgentInvocation).doesNotThrowAnyException();
            assertDenied(() -> KnowledgeAuthorization.requireOwnActor(KnowledgeOperations.CREATE_OWN));
        }
    }

    @Test
    void storeOperationWithoutTrustedStoreIdentityIsDenied() {
        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.READ_STORE), Map.of())) {
            assertDenied(() -> KnowledgeAuthorization.requireStoreIdentity(KnowledgeOperations.READ_STORE));
        }
    }

    private static void assertDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
    }
}
