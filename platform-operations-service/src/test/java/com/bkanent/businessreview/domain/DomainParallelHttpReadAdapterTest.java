package com.bkanent.businessreview.domain;

import com.bkanent.knowledge.service.PublishedKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    @Test
    void listingCheckRequestUsesNestedListingRefShape() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DomainParallelHttpReadAdapter adapter = adapter(builder);
        String checkBody = """
                {"success":true,"code":"0","message":"OK","data":{"contractVersion":"domain-parallel-v1","items":[
                  {"listingRef":{"sourceSystem":"legacy-local","sourceId":"41"},
                   "expectedRevision":"r-41","status":"CURRENT"}]}}
                """;
        server.expect(ExpectedCount.once(), requestTo("http://listing.test/listings/business/v1/snapshots/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"contractVersion":"domain-parallel-v1","refs":[
                          {"listingRef":{"sourceSystem":"legacy-local","sourceId":"41"},
                           "expectedRevision":"r-41"}]}
                        """))
                .andRespond(withSuccess(checkBody, org.springframework.http.MediaType.APPLICATION_JSON));

        // Listing v1 check contract requires the nested listingRef shape, not a flat ref.
        var fact = new com.bkanent.businessreview.domain.ReviewDomain.ReviewFact(
                com.bkanent.businessreview.domain.ReviewDomain.FactKind.LISTING_MAINTENANCE,
                new com.bkanent.businessreview.domain.ReviewDomain.SourceReference(
                        "legacy-local", "41", "r-41", java.time.Instant.parse("2026-11-05T08:00:00Z"),
                        null, com.bkanent.businessreview.domain.ReviewDomain.DataMode.UNKNOWN,
                        "legacy-local", "CURRENT", null),
                "房源公开快照已读取；具体维护状态仅按来源事实表达。", null, false, java.util.Map.of());
        var dataset = new com.bkanent.businessreview.domain.ReviewDomain.ReviewDataset(
                "domain-parallel-v1", com.bkanent.businessreview.domain.ReviewDomain.DataMode.UNKNOWN,
                java.time.Instant.parse("2026-11-05T08:00:00Z"),
                java.time.Instant.parse("2026-11-05T07:00:00Z"),
                java.time.Instant.parse("2026-11-05T08:00:00Z"),
                java.time.ZoneId.of("Asia/Shanghai"), java.util.List.of(fact));

        assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isTrue();
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
