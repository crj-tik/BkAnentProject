package com.bkanent.gateway.loadbalancer;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpInstanceListSupplierTest {

    @Test
    void excludesDubboRegistrationsAndKeepsHttpAndHttpsInstances() {
        ServiceInstance http = new DefaultServiceInstance("http", "agent-service", "agent", 9002, false);
        ServiceInstance https = new DefaultServiceInstance("https", "agent-service", "agent", 9443, true);
        ServiceInstance dubbo = new DefaultServiceInstance("dubbo", "agent-service", "agent", 20882,
                false, Map.of("dubbo.endpoints", "[{\"port\":20882,\"protocol\":\"dubbo\"}]"));
        ServiceInstanceListSupplier delegate = mock(ServiceInstanceListSupplier.class);
        when(delegate.getServiceId()).thenReturn("agent-service");
        when(delegate.get()).thenReturn(Flux.just(List.of(dubbo, http, https)));

        HttpInstanceListSupplier supplier = new HttpInstanceListSupplier(delegate);

        assertEquals("agent-service", supplier.getServiceId());
        assertEquals(List.of(http, https), supplier.get().blockFirst());
    }

    @Test
    void doesNotFallBackToDubboWhenTheHttpServiceIsOffline() {
        ServiceInstance dubbo = new DefaultServiceInstance("dubbo", "agent-service", "agent", 20882,
                false, Map.of("dubbo.metadata-service.url-params", "{}"));
        ServiceInstanceListSupplier delegate = mock(ServiceInstanceListSupplier.class);
        when(delegate.get()).thenReturn(Flux.just(List.of(dubbo)));

        assertEquals(List.of(), new HttpInstanceListSupplier(delegate).get().blockFirst());
    }
}
