package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class A2aCredentialContextTest {
    @Test void authenticationInterceptorHasStableRequiredName() {
        assertThat(new A2aAuthenticationToolInterceptor().getName()).isEqualTo("a2aAuthentication");
    }
    private A2aCredentialContext.Lease lease(String task, String actor, Runnable validation) {
        return new A2aCredentialContext.Lease(new A2aBeaconBinding(1, "r", "agent-service", "listing-master-service", actor,
                "message/send", "0".repeat(64), task), RagCredentialContext.beacon("opaque-" + actor),
                Instant.now().getEpochSecond() + 300, validation);
    }
    @Test void taskIdentityCannotBeReplacedOrRemovedByAnotherExecution() {
        var original = lease("ownership-test", "1", () -> { });
        try (var ignored = A2aCredentialContext.open(original)) {
            A2aCredentialContext.registerTask("ownership-test");
            try (var other = A2aCredentialContext.open(lease("ownership-test", "2", () -> { }))) {
                assertThatThrownBy(() -> A2aCredentialContext.registerTask("ownership-test")).isInstanceOf(RagException.class);
                A2aCredentialContext.discardTask("ownership-test");
            }
            try (var restored = A2aCredentialContext.openTask("ownership-test")) {
                assertThat(A2aCredentialContext.currentActorId()).isEqualTo("1");
            }
            A2aCredentialContext.discardTask("ownership-test");
        }
        assertThatThrownBy(() -> A2aCredentialContext.openTask("ownership-test")).isInstanceOf(RagException.class);
        assertThat(A2aCredentialContext.snapshot()).isNull();
    }
    @Test void supportedSdkExecutorCapturesAndClearsDedicatedThreadIdentity() throws Exception {
        var provider = new A2aContextExecutorProvider();
        try {
            for (String actor : java.util.List.of("1", "2")) {
                try (var ignored = A2aCredentialContext.open(lease("worker-" + actor, actor, () -> { }))) {
                    assertThat(provider.getA2aServerExecutor().submit(A2aCredentialContext::currentActorId).get(5, TimeUnit.SECONDS)).isEqualTo(actor);
                }
            }
            assertThat(provider.getA2aServerExecutor().submit(A2aCredentialContext::snapshot).get(5, TimeUnit.SECONDS)).isNull();
        } finally { provider.close(); }
    }
    @Test void restoredTaskRevalidatesCurrentAuthority() {
        var valid = new AtomicBoolean(true);
        var lease = lease("revoked-tool-test", "1", () -> { if (!valid.get()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); });
        try (var ignored = A2aCredentialContext.open(lease)) {
            A2aCredentialContext.registerTask("revoked-tool-test"); valid.set(false);
            assertThatThrownBy(() -> A2aCredentialContext.openTask("revoked-tool-test")).isInstanceOf(RagException.class);
            A2aCredentialContext.discardTask("revoked-tool-test");
        }
    }
}
