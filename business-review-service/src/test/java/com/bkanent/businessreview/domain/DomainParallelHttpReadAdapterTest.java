package com.bkanent.businessreview.domain;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DomainParallelHttpReadAdapterTest {
    private static final String VERSION = "domain-parallel-v1";
    private static final String LISTING_ITEM = """
            {"listingRef":{"sourceSystem":"listing-master-service","sourceId":"listing-1"},
             "revision":"listing-r1","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "observedAt":"2026-10-27T08:00:00Z","eventTime":"2026-10-26T08:00:00Z",
             "dataMode":"REAL","datasetId":"listing-ds","facts":{"maintenanceGap":true,"title":"A listing"}}
            """;
    private static final String KNOWLEDGE_ITEM = """
            {"knowledgeRef":{"knowledgeId":"knowledge-1","revision":"knowledge-r1",
             "contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
             "revision":"knowledge-r1","contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "publicationState":"PUBLISHED","observedAt":"2026-10-27T08:00:00Z","eventTime":null,
             "dataMode":"SIMULATED","datasetId":"knowledge-ds",
             "body":{"problem":"store review","actions":["verify"],"privateTranscript":"must not be copied"}}
            """;

    @Test
    void readsLocalContractDtosAndChecksExactRevisionsWithDelegatedProof() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<String> signedTargets = new ArrayList<>();
        DomainParallelRequestAuthenticator signer = (body, audience, operation, method, path) -> {
            assertThat(body).isNotEmpty();
            assertThat(operation).isEqualTo("business.review.read.store");
            assertThat(method).isEqualTo("POST");
            assertThat(path).startsWith("/");
            signedTargets.add(audience);
            return "test-delegated-proof";
        };
        DomainParallelHttpReadAdapter adapter = adapter(builder, signer);

        server.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DomainParallelAuthorizationSigner.HEADER, "test-delegated-proof"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("""
                        {"contractVersion":"domain-parallel-v1","keyword":"房源维护","limit":20}
                        """))
                .andRespond(withSuccess(envelope(LISTING_ITEM), org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://knowledge.test/knowledge/v1/published/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DomainParallelAuthorizationSigner.HEADER, "test-delegated-proof"))
                .andRespond(withSuccess(envelope(KNOWLEDGE_ITEM), org.springframework.http.MediaType.APPLICATION_JSON));
        ReviewDataset dataset = adapter.read(VERSION, 23L, "business.review.read.store");

        assertThat(dataset.facts()).hasSize(2);
        assertThat(dataset.effectiveMode()).isEqualTo(DataMode.MIXED);
        assertThat(dataset.facts()).extracting(fact -> fact.source().revision())
                .containsExactlyInAnyOrder("listing-r1", "knowledge-r1");
        server.verify();
        server.reset();

        server.expect(requestTo("http://listing.test/listings/business/v1/snapshots/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DomainParallelAuthorizationSigner.HEADER, "test-delegated-proof"))
                .andExpect(content().json("""
                        {"contractVersion":"domain-parallel-v1","refs":[
                          {"sourceSystem":"listing-master-service","sourceId":"listing-1","expectedRevision":"listing-r1"}]}
                        """))
                .andRespond(withSuccess(envelope("""
                        {"listingRef":{"sourceSystem":"listing-master-service","sourceId":"listing-1"},
                         "expectedRevision":"listing-r1","status":"CURRENT"}
                        """), org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://knowledge.test/knowledge/v1/published/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(DomainParallelAuthorizationSigner.HEADER, "test-delegated-proof"))
                .andExpect(content().json("""
                        {"contractVersion":"domain-parallel-v1","refs":[
                          {"knowledgeId":"knowledge-1","expectedRevision":"knowledge-r1",
                           "contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}
                        """))
                .andRespond(withSuccess(envelope("""
                        {"knowledgeRef":{"knowledgeId":"knowledge-1"},"expectedRevision":"knowledge-r1","status":"CURRENT"}
                        """), org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isTrue();
        assertThat(signedTargets).containsExactly("listing-master-service", "knowledge-service",
                "listing-master-service", "knowledge-service");
        server.verify();
    }

    @Test
    void emptySourceResultIsUnknownAndNeverAssumedReal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DomainParallelHttpReadAdapter adapter = adapter(builder, (body, audience, operation, method, path) -> "test-proof");
        server.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andRespond(withSuccess(envelope(""), org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://knowledge.test/knowledge/v1/published/search"))
                .andRespond(withSuccess(envelope(""), org.springframework.http.MediaType.APPLICATION_JSON));

        ReviewDataset dataset = adapter.read(VERSION, 23L, "business.review.create.store");

        assertThat(dataset.facts()).isEmpty();
        assertThat(dataset.effectiveMode()).isEqualTo(DataMode.UNKNOWN);
        server.verify();
    }

    @Test
    void unknownContractAndAuthenticationDenialFailClosed() {
        RestClient.Builder unusedBuilder = RestClient.builder();
        MockRestServiceServer unusedServer = MockRestServiceServer.bindTo(unusedBuilder).build();
        DomainParallelHttpReadAdapter unusedAdapter = adapter(unusedBuilder, (body, audience, operation, method, path) -> "test-proof");
        assertThatThrownBy(() -> unusedAdapter.read("other-contract", 23L, "business.review.read.store"))
                .isInstanceOfSatisfying(ReviewDependencyException.class,
                        error -> assertThat(error.code()).isEqualTo("UNSUPPORTED_CONTRACT_VERSION"));
        unusedServer.verify();

        RestClient.Builder deniedBuilder = RestClient.builder();
        MockRestServiceServer deniedServer = MockRestServiceServer.bindTo(deniedBuilder).build();
        DomainParallelHttpReadAdapter deniedAdapter = adapter(deniedBuilder, (body, audience, operation, method, path) -> "test-proof");
        deniedServer.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andExpect(header(DomainParallelAuthorizationSigner.HEADER, "test-proof"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> deniedAdapter.read(VERSION, 23L, "business.review.read.store"))
                .isInstanceOfSatisfying(ReviewDependencyException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ACCESS_DENIED"));
        deniedServer.verify();
    }

    @Test
    void staleOrInaccessibleReferenceDoesNotRemainCurrent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DomainParallelHttpReadAdapter adapter = adapter(builder, (body, audience, operation, method, path) -> "test-proof");
        ReviewDataset dataset = new ReviewDataset(VERSION, DataMode.REAL, java.time.Instant.now(),
                java.time.Instant.now().minusSeconds(100), java.time.Instant.now(), java.time.ZoneId.of("UTC"),
                List.of(new ReviewFact(FactKind.KNOWLEDGE,
                        new SourceReference("published-knowledge", "knowledge-1", "knowledge-r1",
                                java.time.Instant.now(), null, DataMode.REAL, "knowledge-ds", "PUBLISHED", null),
                        "knowledge summary", null, false, java.util.Map.of())));
        server.expect(requestTo("http://knowledge.test/knowledge/v1/published/check"))
                .andExpect(content().json("""
                        {"contractVersion":"domain-parallel-v1","refs":[
                          {"knowledgeId":"knowledge-1","expectedRevision":"knowledge-r1"}]}
                        """))
                .andRespond(withSuccess(envelope("""
                        {"knowledgeRef":{"knowledgeId":"knowledge-1"},"expectedRevision":"knowledge-r1","status":"STALE"}
                        """), org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(adapter.sourcesCurrent(dataset, "business.review.observation.read.store")).isFalse();
        server.verify();
    }

    private static DomainParallelHttpReadAdapter adapter(RestClient.Builder builder,
                                                         DomainParallelRequestAuthenticator signer) {
        DomainParallelProperties properties = new DomainParallelProperties();
        properties.setListingBaseUrl("http://listing.test");
        properties.setKnowledgeBaseUrl("http://knowledge.test");
        return new DomainParallelHttpReadAdapter(builder, properties, signer, new ObjectMapper(), false);
    }

    private static String envelope(String items) {
        return "{\"success\":true,\"code\":null,\"message\":null,\"data\":{" +
                "\"contractVersion\":\"" + VERSION + "\",\"items\":[" + items + "]}}";
    }
}
