package com.bkanent.business.security;

import com.bkanent.businessreview.security.BusinessReviewAuthorizationIngressFilter;
import com.bkanent.businessreview.security.BusinessReviewAuthorizationProperties;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.knowledge.config.KnowledgeAuthorizationProperties;
import com.bkanent.knowledge.security.KnowledgeAuthorizationIngressFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPairGenerator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformOperationsIngressSecurityTest {
    @Test
    void reviewAndKnowledgePathsRejectUnsignedRequests() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var keys = Map.of("auth-current", ServiceIdentityCrypto.encodePublicKey(key.getPublic()));
        var review = new BusinessReviewAuthorizationProperties();
        review.setAuthorityPublicKeys(keys);
        var knowledge = new KnowledgeAuthorizationProperties();
        knowledge.setAuthorityPublicKeys(keys);
        assertDenied(new BusinessReviewAuthorizationIngressFilter(review), "/platform-operations/reviews");
        assertDenied(new KnowledgeAuthorizationIngressFilter(knowledge), "/platform-operations/knowledge/v1/cases");
        assertThat(review.getAudience()).isEqualTo("platform-operations-service");
        assertThat(knowledge.getAudience()).isEqualTo("platform-operations-service");
    }

    private static void assertDenied(Filter filter, String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
