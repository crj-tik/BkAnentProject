package com.bkanent.businessreview.domain;

import com.bkanent.businessreview.domain.ReviewDomain.DataMode;
import com.bkanent.common.a2a.VerifiedKnowledgeContext;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeSourceRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.service.PublishedKnowledgeService;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.SourceBinding;
import com.bkanent.knowledge.source.SourceState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalKnowledgeSourceTest {
    @Test
    void localKnowledgePreservesPermissionAndInvalidatesWithdrawnSource() {
        String hash = "a".repeat(64);
        var now = OffsetDateTime.parse("2026-10-30T00:00:00Z");
        var repository = mock(KnowledgeRepository.class);
        var interview = mock(InterviewTranscriptSource.class);
        var service = new PublishedKnowledgeService(repository, interview);
        when(repository.search("STORE", "1", 23L, "带看停滞或房源维护缺口", "store-business-review",
                List.of("store", "listing"), 0, 21)).thenReturn(List.of(
                new KnowledgeRepository.KnowledgeSearchRow("case-1", 1, hash, "摘要", "SIMULATED", now)));
        var published = new KnowledgeCaseRecord("case-1", "STORE", "2", 23L, "标题", "问题", "场景",
                List.of(), KnowledgeState.PUBLISHED, 1, 1, "SIMULATED", 0, now, now);
        when(repository.findCase("case-1", false)).thenReturn(published);
        when(repository.findRevision("case-1", 1)).thenReturn(new KnowledgeRevisionRecord(1, "case-1", 1,
                hash, "摘要", null, "SIMULATED", "UNVERIFIED", "b".repeat(64), "rules-v1", "2", now));
        var binding = new SourceBinding("101", "b".repeat(64), "c".repeat(64), 1, 1, 1, "SIMULATED");
        when(repository.findSources("case-1", 1)).thenReturn(List.of(new KnowledgeSourceRecord(binding,
                "d".repeat(64), 0, 3)));
        when(interview.check("101", binding)).thenReturn(SourceState.CURRENT);

        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new DomainParallelProperties();
        properties.setListingBaseUrl("http://listing.test");
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var adapter = new DomainParallelHttpReadAdapter(builder, properties, (body, audience, operation, method, path) -> "proof",
                mapper, false, service);
        server.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"contractVersion\":\"domain-parallel-v1\",\"items\":[]}}", MediaType.APPLICATION_JSON));
        try (var ignored = VerifiedKnowledgeContext.open(List.of("business.review.read.store", "knowledge.case.read.store"), Map.of("STORE", 23L))) {
            var dataset = adapter.read("domain-parallel-v1", 23L, "business.review.read.store");
            assertThat(dataset.effectiveMode()).isEqualTo(DataMode.SIMULATED);
            assertThat(dataset.facts()).singleElement().satisfies(fact -> {
                assertThat(fact.source().revision()).isEqualTo("r1");
                assertThat(fact.source().contentHash()).isEqualTo(hash);
            });
            assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isTrue();
            try (var otherStore = VerifiedKnowledgeContext.open(List.of("business.review.read.store", "knowledge.case.read.store"),
                    Map.of("STORE", 24L))) {
                assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isFalse();
            }
            when(interview.check("101", binding)).thenReturn(SourceState.NOT_ACCESSIBLE);
            assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isFalse();
            when(interview.check("101", binding)).thenReturn(SourceState.CURRENT);
            when(repository.findCase("case-1", false)).thenReturn(null);
            assertThat(adapter.sourcesCurrent(dataset, "business.review.read.store")).isFalse();
        }
        server.verify();

        server.reset();
        server.expect(requestTo("http://listing.test/listings/business/v1/search"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"contractVersion\":\"domain-parallel-v1\",\"items\":[]}}", MediaType.APPLICATION_JSON));
        try (var ignored = VerifiedKnowledgeContext.open(List.of("business.review.read.store"), Map.of("STORE", 23L))) {
            assertThatThrownBy(() -> adapter.read("domain-parallel-v1", 23L, "business.review.read.store"))
                    .isInstanceOfSatisfying(ReviewDependencyException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SOURCE_ACCESS_DENIED"));
        }
        server.verify();
    }
}
