package com.bkanent.common.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySharedExportPolicyTest {

    @Test
    void rejectsSensitiveBriefsEvenWhenPayloadFieldsAreAllowed() {
        MemoryJsonValue payload = MemoryJsonValue.object(Map.of("result", MemoryJsonValue.string("verified")));

        assertThatThrownBy(() -> MemorySharedExportPolicy.project("Bearer fake-development-token", payload))
                .isInstanceOf(MemoryException.class)
                .extracting(exception -> ((MemoryException) exception).code())
                .isEqualTo(MemoryErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> MemorySharedExportPolicy.project("desensitization_map: private mappings", payload))
                .isInstanceOf(MemoryException.class)
                .extracting(exception -> ((MemoryException) exception).code())
                .isEqualTo(MemoryErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void rejectsAuthenticationBeaconAndMappingFieldsInsideAllowedRoots() {
        MemoryJsonValue payload = MemoryJsonValue.object(Map.of("result", MemoryJsonValue.object(Map.of(
                "authBeacon", MemoryJsonValue.string("private-beacon-value")))));

        assertThatThrownBy(() -> MemorySharedExportPolicy.project("safe", payload))
                .isInstanceOf(MemoryException.class)
                .extracting(exception -> ((MemoryException) exception).code())
                .isEqualTo(MemoryErrorCode.INVALID_ARGUMENT);
    }
}
