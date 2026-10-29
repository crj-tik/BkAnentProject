package com.bkanent.business.security;

import com.bkanent.business.config.BusinessAuthorizationProperties;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPairGenerator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAnalyticsIngressTest {
    @Test
    void newAnalyticsPathRequiresSignedIdentityAndHealthRemainsReachable() throws Exception {
        var properties = new BusinessAuthorizationProperties();
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        properties.setAuthorityPublicKeys(Map.of("auth-current", ServiceIdentityCrypto.encodePublicKey(key.getPublic())));
        var filter = new BusinessAuthorizationIngressFilter(properties);

        var protectedRequest = new MockHttpServletRequest("GET", "/platform-operations/analytics/kpis/monthly");
        var denied = new MockHttpServletResponse();
        var deniedChain = new MockFilterChain();
        filter.doFilter(protectedRequest, denied, deniedChain);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(deniedChain.getRequest()).isNull();

        var healthRequest = new MockHttpServletRequest("GET", "/platform-operations/analytics/health");
        var healthChain = new MockFilterChain();
        filter.doFilter(healthRequest, new MockHttpServletResponse(), healthChain);
        assertThat(healthChain.getRequest()).isSameAs(healthRequest);
        assertThat(properties.getAudience()).isEqualTo("platform-operations-service");
    }
}
