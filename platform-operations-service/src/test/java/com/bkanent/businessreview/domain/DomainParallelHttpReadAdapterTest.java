package com.bkanent.businessreview.domain;

import com.bkanent.knowledge.service.PublishedKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class DomainParallelHttpReadAdapterTest {
    @Test
    void unknownContractIsRejectedBeforeAnySourceCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DomainParallelHttpReadAdapter adapter = adapter(builder);

        assertThatThrownBy(() -> adapter.read("retired-contract", 23L, "business.review.read.store"))
                .isInstanceOfSatisfying(ReviewDependencyException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("UNSUPPORTED_CONTRACT_VERSION"));
        server.verify();
    }

    @Test
    void listingAuthorizationDenialFailsClosed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DomainParallelHttpReadAdapter adapter = adapter(builder);
        server.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.read("domain-parallel-v1", 23L, "business.review.read.store"))
                .isInstanceOfSatisfying(ReviewDependencyException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("SOURCE_ACCESS_DENIED"));
        server.verify();
    }

    private static DomainParallelHttpReadAdapter adapter(RestClient.Builder builder) {
        DomainParallelProperties properties = new DomainParallelProperties();
        properties.setListingBaseUrl("http://listing.test");
        return new DomainParallelHttpReadAdapter(builder, properties,
                (body, audience, operation, method, path) -> "test-proof",
                new ObjectMapper(), false, org.mockito.Mockito.mock(PublishedKnowledgeService.class));
    }
}
