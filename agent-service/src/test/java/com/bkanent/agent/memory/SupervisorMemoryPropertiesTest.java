package com.bkanent.agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorMemoryPropertiesTest {
    @Test void defaultsAreBoundedAndRemainDisabled() {
        var properties = new SupervisorMemoryProperties();
        assertFalse(properties.isEnabled());
        assertFalse(properties.isCompressionEnabled());
        assertDoesNotThrow(properties::validate);
    }

    @Test void enabledModeRequiresDedicatedSecretWithMinimumLength() {
        var properties = new SupervisorMemoryProperties();
        properties.setEnabled(true);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setSigningSecret("short");
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setSigningSecret("a".repeat(32));
        assertDoesNotThrow(properties::validate);
    }

    @Test void compressionCannotBeEnabledOutsideMemoryV2() {
        var properties = new SupervisorMemoryProperties();
        properties.setCompressionEnabled(true);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test void promptReadsAndAcceptedDeliveriesHaveIndependentSwitches() {
        var properties = new SupervisorMemoryProperties();
        properties.setEnabled(true);
        properties.setSigningSecret("a".repeat(32));
        properties.setReadEnabled(false);
        properties.setDeliveryEnabled(true);
        assertFalse(properties.isReadEnabled());
        assertTrue(properties.isDeliveryEnabled());
        properties.setDeliveryEnabled(false);
        assertFalse(properties.isDeliveryEnabled());
        assertDoesNotThrow(properties::validate);
    }

    @Test void inconsistentBudgetsAndUnboundedCacheAreRejected() {
        var properties = new SupervisorMemoryProperties();
        properties.setSoftTokens(12000);
        properties.setHardTokens(12000);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setSoftTokens(8000);
        properties.setHardTokens(12000);
        properties.setCacheEntries(10001);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test void resultProjectionAndRecallBoundsAreValidated() {
        var properties = new SupervisorMemoryProperties();
        properties.setToolInlineChars(255); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setToolInlineChars(16385); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setToolInlineChars(4096); properties.setRecallChars(2047); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setRecallChars(16385); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setRecallChars(8192); assertDoesNotThrow(properties::validate);
    }
}
