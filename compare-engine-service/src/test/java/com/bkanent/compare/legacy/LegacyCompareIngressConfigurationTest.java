package com.bkanent.compare.legacy;

import com.bkanent.compare.controller.CompareController;
import com.bkanent.compare.rpc.CompareEngineRpcServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCompareIngressConfigurationTest {
    @Test
    void restAndRpcIngressesRequireExplicitLegacyEnableFlags() {
        assertCondition(CompareController.class, "rest-enabled");
        assertCondition(CompareEngineRpcServiceImpl.class, "rpc-enabled");
    }

    private void assertCondition(Class<?> ingress, String property) {
        ConditionalOnProperty condition = ingress.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, ingress.getSimpleName() + " must be conditional");
        assertEquals("compare.legacy", condition.prefix());
        assertArrayEquals(new String[] { property }, condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }
}
