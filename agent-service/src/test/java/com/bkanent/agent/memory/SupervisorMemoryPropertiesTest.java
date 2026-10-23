package com.bkanent.agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorMemoryPropertiesTest {
    @Test void memoryIdentityIsRequiredWhileCompressionRemainsOptional() {
        var properties = new SupervisorMemoryProperties();
        assertFalse(properties.isCompressionEnabled());
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setPrivateKeyFile("/run/secrets/service-identity.pem");
        assertDoesNotThrow(properties::validate);
    }

    @Test void serviceIdentityKeyIdAndPrivateKeyFileAreRequired() {
        var properties = new SupervisorMemoryProperties();
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setPrivateKeyFile("/run/secrets/service-identity.pem");
        assertDoesNotThrow(properties::validate);
        properties.setKeyId("");
        assertThrows(IllegalArgumentException.class, properties::validate);
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
        properties.setPrivateKeyFile("/run/secrets/service-identity.pem");
        properties.setToolInlineChars(255); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setToolInlineChars(16385); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setToolInlineChars(4096); properties.setRecallChars(2047); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setRecallChars(16385); assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setRecallChars(8192); assertDoesNotThrow(properties::validate);
    }
}
