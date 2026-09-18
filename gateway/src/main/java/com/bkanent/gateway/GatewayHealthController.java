package com.bkanent.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 网关自身的轻量健康入口，不依赖下游业务服务。
 */
@RestController
public class GatewayHealthController {

    @GetMapping("/gateway/health")
    public Map<String, String> health() {
        return Map.of(
                "service", "gateway",
                "status", "UP"
        );
    }
}
