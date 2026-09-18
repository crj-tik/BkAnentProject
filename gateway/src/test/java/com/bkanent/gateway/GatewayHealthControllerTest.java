package com.bkanent.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayHealthControllerTest {

    @Test
    void returnsGatewayHealth() {
        Map<String, String> response = new GatewayHealthController().health();

        assertThat(response).containsEntry("service", "gateway");
        assertThat(response).containsEntry("status", "UP");
    }
}
