package com.bkanent.gateway.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** HTTP routes must not select Dubbo ports registered under the same service name. */
public class HttpInstanceListSupplier implements ServiceInstanceListSupplier {

    private final ServiceInstanceListSupplier delegate;

    public HttpInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(instances -> instances.stream()
                .filter(HttpInstanceListSupplier::isHttpInstance)
                .toList());
    }

    private static boolean isHttpInstance(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata != null && (metadata.containsKey("dubbo.endpoints")
                || metadata.containsKey("dubbo.metadata-service.url-params")
                || "dubbo".equalsIgnoreCase(metadata.get("protocol")))) {
            return false;
        }
        String scheme = instance.getScheme();
        return scheme == null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
