package com.bkanent.common.memory;

import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KI-100 regression guard: the v2 consumer must stay pure-dubbo with the pinned
 * group/version, and deployment must never be able to point it at a URL or host —
 * the same consumer-side mis-selection that routed Listing to a REST instance.
 */
class MemoryV2ConsumerConfigurationTest {

    @Test
    void referenceIsPinnedToPureDubboWithFixedGroupAndVersion() {
        ReferenceConfig<MemoryV2RpcService> reference =
                MemoryV2ConsumerConfiguration.reference(new MockEnvironment());
        assertThat(reference.getInterface()).isEqualTo(MemoryV2Contract.INTERFACE_NAME);
        assertThat(reference.getGroup()).isEqualTo(MemoryV2Contract.DUBBO_GROUP);
        assertThat(reference.getVersion()).isEqualTo(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        assertThat(reference.getProtocol()).isEqualTo("dubbo");
        assertThat(reference.getUrl()).isNull();
        assertThat(reference.isCheck()).isFalse();
        assertThat(reference.getRetries()).isZero();
        assertThat(reference.getTimeout()).isEqualTo(5000);
    }

    @Test
    void urlOrHostPropertiesAreRejectedInsteadOfBeingHonoured() {
        for (String property : new String[]{"memory.v2.dubbo.url", "memory.v2.dubbo.host"}) {
            Environment environment = new MockEnvironment().withProperty(property, "10.0.0.1:20880");
            assertThatThrownBy(() -> MemoryV2ConsumerConfiguration.reference(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("memory v2 consumer must not be pointed at a URL or host");
        }
    }
}
