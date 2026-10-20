package com.bkanent.common.memory;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline contract tests only: no Spring context, RPC/provider or service requests. */
class MemoryContractTest {
    private static final Instant AT = Instant.parse("2026-10-16T00:00:00Z");
    private static final MemoryContextKey CONTEXT = new MemoryContextKey("owner-1", "marketing", "copy-agent", "conversation-1");
    private static final MemoryAssociation ASSOCIATION = new MemoryAssociation("matter-1", "run-1", "a2a-1",
            new MemoryAssociation.DomainSessionRef("marketing", "domain-session-1"));
    private final Fastjson2ConstructorSerialization serialization = new Fastjson2ConstructorSerialization();

    @Test void resultAnchorsRoundTripAndParticipateInSummaryHashWithoutChangingLegacyDigest() throws Exception {
        var original = summary();
        String legacyHash = MemoryCanonicalHash.contentHash(java.util.Arrays.asList(original.summaryId(), original.contextKey(), original.association(),
                original.version().revision(), original.version().schemaVersion(), original.expectedConversationRevision(), original.runVersion(),
                original.coveredRange(), original.toolGroupRanges(), original.items(), original.entities(), original.dependencies()));
        assertThat(MemoryCanonicalHash.summaryHash(original)).isEqualTo(legacyHash);
        var anchor = new MemoryToolResultAnchor(original.dependencies().get(0).source(), "call", "search", MemoryToolResponse.ExecutionState.PARTIAL);
        var extended = new MemorySummary(original.summaryId(), CONTEXT, ASSOCIATION, original.version(), original.expectedConversationRevision(), original.runVersion(),
                original.coveredRange(), original.toolGroupRanges(), original.items(), original.entities(), List.of(anchor), original.dependencies());
        assertThat(spi(extended, MemorySummary.class)).isEqualTo(extended);
        assertThat(javaRoundTrip(extended)).isEqualTo(extended);
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsBytes(extended), MemorySummary.class)).isEqualTo(extended);
        assertThat(MemoryCanonicalHash.summaryHash(extended)).isNotEqualTo(legacyHash);
        var oldJson = mapper.valueToTree(original); ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson).remove("toolResults");
        assertThat(mapper.treeToValue(oldJson, MemorySummary.class)).isEqualTo(original);
        assertThatThrownBy(() -> new MemorySummary(original.summaryId(), CONTEXT, ASSOCIATION, original.version(), original.expectedConversationRevision(), original.runVersion(),
                original.coveredRange(), original.toolGroupRanges(), original.items(), original.entities(), List.of(anchor, anchor), original.dependencies())).isInstanceOf(MemoryException.class);
    }

    @Test void fourIdentitySegmentsRemainIndependent() throws Exception {
        assertThat(CONTEXT).isNotEqualTo(new MemoryContextKey("owner-2", "marketing", "copy-agent", "conversation-1"));
        assertThat(CONTEXT).isNotEqualTo(new MemoryContextKey("owner-1", "interview", "copy-agent", "conversation-1"));
        assertThat(CONTEXT).isNotEqualTo(new MemoryContextKey("owner-1", "marketing", "other-agent", "conversation-1"));
        assertThat(CONTEXT).isNotEqualTo(new MemoryContextKey("owner-1", "marketing", "copy-agent", "conversation-2"));
        assertThat(new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(CONTEXT), MemoryContextKey.class)).isEqualTo(CONTEXT);
        assertThat(javaRoundTrip(CONTEXT)).isEqualTo(CONTEXT);
    }

    @Test void identityRejectsMissingSegmentsControlCharactersAndAddresses() {
        for (String invalid : new String[]{"", " ", "owner\n", "http://example.invalid", "x@y", "x".repeat(257)}) {
            assertThatThrownBy(() -> new MemoryContextKey(invalid, "domain", "agent", "conversation"))
                    .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        }
        assertThatThrownBy(() -> new MemoryContextKey(null, "domain", "agent", "conversation"))
                .isInstanceOf(MemoryException.class);
    }

    @Test void runTaskMatterAndDomainSessionDoNotReplaceConversationIdentity() throws Exception {
        assertThat(ASSOCIATION.matterId()).isEqualTo("matter-1");
        assertThat(ASSOCIATION.runId()).isEqualTo("run-1");
        assertThat(ASSOCIATION.a2aTaskId()).isEqualTo("a2a-1");
        assertThat(ASSOCIATION.domainSessionRef().sessionId()).isEqualTo("domain-session-1");
        assertThat(CONTEXT.conversationId()).isEqualTo("conversation-1");
        assertThat(spi(ASSOCIATION, MemoryAssociation.class)).isEqualTo(ASSOCIATION);
        assertThat(new MemoryAssociation(null, null, null, null).runId()).isNull();
        assertThat(new MemoryRunVersion("run-1", 7).checkpointRevision()).isEqualTo(7);
        assertThat(new MemoryConversationVersion(0, 0, "policy-v2").lastSequence()).isZero();
    }

    @Test void privatePreferenceScopeCannotCollideAcrossAgentsOrEscapedSegments() throws Exception {
        MemoryScope one = MemoryScope.privateScope("marketing", "copy-agent");
        MemoryScope two = MemoryScope.privateScope("marketing", "other-agent");
        assertThat(new PreferenceBlockKey("owner-1", "owner-1", one, "copy_length"))
                .isNotEqualTo(new PreferenceBlockKey("owner-1", "owner-1", two, "copy_length"));
        assertThat(one.canonicalScopeId()).isNotEqualTo(two.canonicalScopeId());
        assertThat(MemoryScope.privateScope("a:b", "c").canonicalScopeId())
                .isNotEqualTo(MemoryScope.privateScope("a", "b:c").canonicalScopeId());
        assertThat(spi(one, MemoryScope.class)).isEqualTo(one);
        assertThat(new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "group-1"))
                .isEqualTo(new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "group-1"));
        assertThatThrownBy(() -> new MemoryScope(MemoryScope.Kind.DOMAIN_PRIVATE, "marketing", null, null))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryScope(MemoryScope.Kind.TASK_SHARED, "marketing", "agent", "group-1"))
                .isInstanceOf(MemoryException.class);
    }

    @Test void jsonPreservesNestedFactsNullArraysNumbersAndEmptyObjects() throws Exception {
        MemoryJsonValue value = data();
        assertThat(spi(value, MemoryJsonValue.class)).isEqualTo(value);
        assertThat(javaRoundTrip(value)).isEqualTo(value);
        assertThat(new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(value), MemoryJsonValue.class)).isEqualTo(value);
        assertThat(value.properties().get("facts").elements().get(0).properties().get("price").scalar()).isEqualTo("123.4500");
        assertThat(spi(MemoryJsonValue.object(Map.of()), MemoryJsonValue.class)).isEqualTo(MemoryJsonValue.object(Map.of()));
        assertThat(spi(MemoryJsonValue.array(List.of()), MemoryJsonValue.class)).isEqualTo(MemoryJsonValue.array(List.of()));
    }

    @Test void jsonCollectionsAreDefensivelyCopiedAtEveryLevel() {
        Map<String, MemoryJsonValue> properties = new HashMap<>();
        properties.put("value", MemoryJsonValue.string("before"));
        MemoryJsonValue object = MemoryJsonValue.object(properties);
        properties.put("value", MemoryJsonValue.string("after"));
        List<MemoryJsonValue> items = new ArrayList<>(List.of(object));
        MemoryJsonValue array = MemoryJsonValue.array(items);
        items.clear();
        assertThat(array.elements()).containsExactly(object);
        assertThat(object.properties().get("value").scalar()).isEqualTo("before");
        assertThatThrownBy(() -> object.properties().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> array.elements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void jsonRejectsMalformedShapeNonJsonNumbersAndNullEntries() {
        for (String invalid : List.of("NaN", "Infinity", "01", "+1", "1.", " 1", "1".repeat(129))) {
            assertThatThrownBy(() -> MemoryJsonValue.number(invalid)).isInstanceOf(MemoryException.class);
        }
        assertThatThrownBy(() -> new MemoryJsonValue(MemoryJsonValue.Type.STRING, "x", List.of(), null))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryJsonValue(MemoryJsonValue.Type.BOOLEAN, "TRUE", null, null))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> MemoryJsonValue.string(new String(new char[]{(char) 0xD800})))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> MemoryJsonValue.array(java.util.Arrays.asList((MemoryJsonValue) null)))
                .isInstanceOf(MemoryException.class);
        Map<String, MemoryJsonValue> invalid = new HashMap<>();
        invalid.put("field", null);
        assertThatThrownBy(() -> MemoryJsonValue.object(invalid)).isInstanceOf(MemoryException.class);
    }

    @Test void jsonTreeHasDepthNodeAndAggregateTextBounds() {
        MemoryJsonValue value = MemoryJsonValue.nil();
        for (int i = 1; i < 32; i++) value = MemoryJsonValue.array(List.of(value));
        MemoryJsonValue deepest = value;
        assertThatThrownBy(() -> MemoryJsonValue.array(List.of(deepest))).isInstanceOf(MemoryException.class);
        MemoryJsonValue broad = MemoryJsonValue.array(java.util.Collections.nCopies(500, MemoryJsonValue.nil()));
        assertThatThrownBy(() -> MemoryJsonValue.array(java.util.Collections.nCopies(9, broad))).isInstanceOf(MemoryException.class);
        MemoryJsonValue large = MemoryJsonValue.string("x".repeat(32768));
        assertThatThrownBy(() -> MemoryJsonValue.array(java.util.Collections.nCopies(5, large))).isInstanceOf(MemoryException.class);
    }

    @Test void completeParallelToolGroupPreservesAllCallsResponsesAndFacts() throws Exception {
        MemoryToolGroup group = group();
        assertThat(group.assistant().toolCalls()).hasSize(2);
        assertThat(group.responses()).hasSize(2);
        assertThat(group.responses().get(0).toolResponses().get(0).responseData()).isEqualTo(data());
        assertThat(group.responses().get(1).toolResponses().get(0).state()).isEqualTo(MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN);
        assertThat(group.range()).isEqualTo(new MemoryCoveredRange(1, 40));
        assertThat(spi(group, MemoryToolGroup.class)).isEqualTo(group);
        assertThat(javaRoundTrip(group)).isEqualTo(group);
    }

    @Test void toolGroupRejectsMissingDuplicateForeignAndMismatchedResponses() {
        MemoryToolGroup group = group();
        assertThatThrownBy(() -> new MemoryToolGroup(group.assistant(), List.of(group.responses().get(0))))
                .isInstanceOf(MemoryException.class).hasMessage("INCOMPLETE_TOOL_GROUP (TOOL_GROUP)");
        assertThatThrownBy(() -> new MemoryToolGroup(group.assistant(), List.of(group.responses().get(0), group.responses().get(0))))
                .isInstanceOf(MemoryException.class);
        MemoryToolResponse foreign = response("foreign", "search", MemoryToolResponse.ExecutionState.SUCCEEDED);
        assertThatThrownBy(() -> new MemoryToolGroup(group.assistant(), List.of(message("foreign-response", 41, MemoryMessage.Role.TOOL, null, List.of(), List.of(foreign)))))
                .isInstanceOf(MemoryException.class);
        MemoryToolResponse wrongName = response("call-1", "other", MemoryToolResponse.ExecutionState.SUCCEEDED);
        assertThatThrownBy(() -> new MemoryToolGroup(group.assistant(), List.of(message("wrong-name", 41, MemoryMessage.Role.TOOL, null, List.of(), List.of(wrongName)))))
                .isInstanceOf(MemoryException.class);
    }

    @Test void messageShapeRejectsRoleMisuseSourceMismatchAndDuplicateCalls() {
        MemoryToolCall call = call("call-1", "search");
        assertThatThrownBy(() -> message("message", 1, MemoryMessage.Role.USER, "hi", List.of(call), List.of()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> message("message", 1, MemoryMessage.Role.ASSISTANT, null, List.of(call, call), List.of()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> message("message", 1, MemoryMessage.Role.USER, "", List.of(), List.of()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryMessage("message", CONTEXT, 2, ASSOCIATION, MemoryMessage.Role.USER, "hi", List.of(), List.of(), source("message", 1)))
                .isInstanceOf(MemoryException.class);
    }

    @Test void toolCollectionsCannotBeChangedThroughCallerOrReturnedReferences() {
        List<MemoryToolCall> calls = new ArrayList<>(List.of(call("call-1", "search")));
        MemoryMessage assistant = message("assistant", 1, MemoryMessage.Role.ASSISTANT, null, calls, List.of());
        calls.clear();
        assertThat(assistant.toolCalls()).hasSize(1);
        assertThatThrownBy(() -> assistant.toolCalls().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> group().responses().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void largeToolDataUsesExplicitFullVersionedSourceRatherThanSilentTruncation() throws Exception {
        MemorySourceRef full = new MemorySourceRef(MemorySourceRef.Kind.DOMAIN_RECORD, CONTEXT, "record-1", "event-1", null, version(1));
        MemorySourceDependency dependency = new MemorySourceDependency(full, null);
        MemoryToolResponse referenced = new MemoryToolResponse("call-1", "search", MemoryToolResponse.ExecutionState.PARTIAL,
                "brief", MemoryToolResponse.DataForm.REFERENCED_COMPLETE, MemoryJsonValue.object(Map.of()), full, List.of(dependency));
        assertThat(spi(referenced, MemoryToolResponse.class)).isEqualTo(referenced);
        assertThatThrownBy(() -> new MemoryToolResponse("call-1", "search", MemoryToolResponse.ExecutionState.SUCCEEDED,
                "brief", MemoryToolResponse.DataForm.INLINE_COMPLETE, null, null, List.of())).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryToolResponse("call-1", "search", MemoryToolResponse.ExecutionState.SUCCEEDED,
                "brief", MemoryToolResponse.DataForm.REFERENCED_COMPLETE, null, full, List.of())).isInstanceOf(MemoryException.class);
    }

    @Test void summaryPreservesStructureCoverageAndGrantDependencies() throws Exception {
        MemorySummary summary = summary();
        assertThat(summary.items()).extracting(MemorySummary.Item::kind)
                .contains(MemorySummary.ItemKind.NEGATION, MemorySummary.ItemKind.EXCEPTION, MemorySummary.ItemKind.UNRESOLVED);
        assertThat(summary.dependencies().get(0).grant()).isEqualTo(new MemoryGrantRef("grant-1", 3));
        assertThat(summary.expectedConversationRevision()).isEqualTo(5);
        assertThat(summary.runVersion().checkpointRevision()).isEqualTo(9);
        assertThat(spi(summary, MemorySummary.class)).isEqualTo(summary);
        assertThatThrownBy(() -> summary.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> summary.dependencies().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void summaryRejectsInvalidRangeUnlistedEvidenceEmptyItemsAndWrongRun() {
        MemorySummary value = summary();
        assertThatThrownBy(() -> new MemoryCoveredRange(2, 1)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemorySummary("summary", CONTEXT, ASSOCIATION, version(1), 5, null,
                new MemoryCoveredRange(1, 10), List.of(new MemoryCoveredRange(1, 40)), value.items(), List.of(), value.dependencies()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemorySummary("summary", CONTEXT, ASSOCIATION, version(1), 5, null,
                value.coveredRange(), List.of(), List.of(), List.of(), value.dependencies())).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemorySummary("summary", CONTEXT, ASSOCIATION, version(1), 5,
                new MemoryRunVersion("other-run", 9), value.coveredRange(), List.of(), value.items(), List.of(), value.dependencies()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemorySummary("summary", CONTEXT, ASSOCIATION, version(1), 5, null,
                value.coveredRange(), List.of(), value.items(), List.of(), List.of(new MemorySourceDependency(source("other", 2), null))))
                .isInstanceOf(MemoryException.class);
    }

    @Test void historyPagesPinBlockRevisionAuthorizationViewAndOrderedKeyset() throws Exception {
        PreferenceHistoryCursor start = new PreferenceHistoryCursor("block-1", 4, 2, 0);
        PreferenceHistoryCursor next = new PreferenceHistoryCursor("block-1", 4, 2, 2);
        PreferenceHistoryPage first = new PreferenceHistoryPage(start, List.of(transition(1, 1), transition(2, 2)), 1L, 2L, true, next, PreferenceHistoryPage.Completeness.PARTIAL);
        PreferenceHistoryPage last = new PreferenceHistoryPage(next, List.of(transition(3, 4)), 3L, 3L, false, null, PreferenceHistoryPage.Completeness.COMPLETE);
        assertThat(spi(first, PreferenceHistoryPage.class)).isEqualTo(first);
        assertThat(spi(last, PreferenceHistoryPage.class)).isEqualTo(last);
        assertThatThrownBy(() -> first.transitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(last.cursor().revision()).isEqualTo(first.cursor().revision());
        assertThat(last.hasMore()).isFalse();
    }

    @Test void historyCannotSilentlyMixVersionsScopesOrDeclareTruncatedPagesComplete() {
        PreferenceHistoryCursor start = new PreferenceHistoryCursor("block-1", 4, 2, 0);
        for (PreferenceHistoryCursor invalid : List.of(new PreferenceHistoryCursor("block-2", 4, 2, 1),
                new PreferenceHistoryCursor("block-1", 5, 2, 1), new PreferenceHistoryCursor("block-1", 4, 3, 1),
                new PreferenceHistoryCursor("block-1", 4, 2, 2))) {
            assertThatThrownBy(() -> new PreferenceHistoryPage(start, List.of(transition(1, 1)), 1L, 1L, true, invalid,
                    PreferenceHistoryPage.Completeness.PARTIAL)).isInstanceOf(MemoryException.class);
        }
        assertThatThrownBy(() -> new PreferenceHistoryPage(start, List.of(transition(2, 1), transition(1, 1)), 2L, 1L, false, null,
                PreferenceHistoryPage.Completeness.COMPLETE)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new PreferenceHistoryPage(start, List.of(transition(1, 5)), 1L, 1L, false, null,
                PreferenceHistoryPage.Completeness.COMPLETE)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new PreferenceHistoryPage(start, List.of(transition(1, 1)), 1L, 1L, true,
                new PreferenceHistoryCursor("block-1", 4, 2, 1), PreferenceHistoryPage.Completeness.COMPLETE)).isInstanceOf(MemoryException.class);
        assertThat(new PreferenceHistoryPage(start, List.of(), null, null, false, null, PreferenceHistoryPage.Completeness.UNAVAILABLE)
                .completeness()).isEqualTo(PreferenceHistoryPage.Completeness.UNAVAILABLE);
    }

    @Test void preferenceBlockCurrentStateIsSeparateFromCompleteEvolution() throws Exception {
        PreferenceCurrentState current = new PreferenceCurrentState(PreferenceCurrentState.Status.EFFECTIVE,
                MemoryJsonValue.string("short"), List.of("transition-1"), List.of("transition-2"), List.of(), .8, .5, AT,
                List.of(new MemorySourceDependency(source("input", 1), null)));
        PreferenceBlock block = new PreferenceBlock("block-1", new PreferenceBlockKey("owner-1", "owner-1",
                MemoryScope.privateScope("marketing", "copy-agent"), "copy_length"), version(4), MemoryState.ACTIVE, current,
                new PreferenceHistoryCursor("block-1", 4, 2, 0));
        assertThat(spi(block, PreferenceBlock.class)).isEqualTo(block);
        assertThatThrownBy(() -> new PreferenceBlock("block-1", block.key(), version(4), MemoryState.ACTIVE, current,
                new PreferenceHistoryCursor("block-1", 5, 2, 0))).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> current.transitionIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void temporaryExceptionAndCorrectionRequireExplicitShapeAndEvidence() throws Exception {
        MemorySourceDependency dependency = new MemorySourceDependency(source("input", 1), null);
        PreferenceTransition exception = new PreferenceTransition("exception", 2, 2, PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.TEMPORARY_EXCEPTION, PreferenceTransition.Polarity.NEGATIVE,
                MemoryJsonValue.string("long"), "this matter", "matter-1", AT, AT, AT, null, List.of(), .8, "extract-v1", List.of(dependency));
        assertThat(spi(exception, PreferenceTransition.class)).isEqualTo(exception);
        assertThatThrownBy(() -> new PreferenceTransition("correction", 2, 2, PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.LONG_TERM_CORRECTION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string("short"), null, null, AT, AT, null, null, List.of(), .8, "extract-v1", List.of(dependency)))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new PreferenceTransition("exception", 2, 2, PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.TEMPORARY_EXCEPTION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string("short"), null, null, AT, AT, null, null, List.of(), .8, "extract-v1", List.of(dependency)))
                .isInstanceOf(MemoryException.class);
    }

    @Test void experienceDoesNotTreatSelfAssessmentAcceptanceOrUnknownAsVerifiedOutcome() throws Exception {
        MemoryExperience experience = experience();
        assertThat(spi(experience, MemoryExperience.class)).isEqualTo(experience);
        assertThat(experience.evidence().get(0).feedback()).isEqualTo(MemoryExperience.Feedback.NEGATIVE);
        for (MemoryExperience.EvidenceKind kind : List.of(MemoryExperience.EvidenceKind.MODEL_SELF_ASSESSMENT,
                MemoryExperience.EvidenceKind.ACCEPTANCE)) {
            assertThatThrownBy(() -> new MemoryExperience.Evidence("e", kind, MemoryExperience.Outcome.ACCEPTED,
                    MemoryExperience.Feedback.NONE, true, AT, AT, experience.evidence().get(0).sources())).isInstanceOf(MemoryException.class);
        }
        assertThatThrownBy(() -> new MemoryExperience.Evidence("e", MemoryExperience.EvidenceKind.BUSINESS_OUTCOME,
                MemoryExperience.Outcome.UNKNOWN, MemoryExperience.Feedback.NONE, true, AT, AT, experience.evidence().get(0).sources()))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> experience.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void independentScoresRetainAsOfAndNeverModifyEvidenceDates() throws Exception {
        MemoryExperience experience = experience();
        MemorySelectionScore first = new MemorySelectionScore(.9, .8, .7, .6, AT, "score-v1");
        MemorySelectionScore later = new MemorySelectionScore(.5, .8, .7, .3, AT.plusSeconds(3600), "score-v1");
        assertThat(spi(first, MemorySelectionScore.class)).isEqualTo(first);
        assertThat(later.confidence()).isEqualTo(first.confidence());
        assertThat(experience.lastValidatedAt()).isEqualTo(AT);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1}) {
            assertThatThrownBy(() -> new MemorySelectionScore(invalid, .8, .7, .6, AT, "score-v1"))
                    .isInstanceOf(MemoryException.class);
        }
    }

    @Test void grantsDeclareNamedParticipantsProjectionPurposeSourceRevisionAndExpiry() throws Exception {
        MemoryShareGrant grant = grant();
        assertThat(spi(grant, MemoryShareGrant.class)).isEqualTo(grant);
        assertThat(grant.projections()).containsExactly(MemoryShareGrant.Projection.DURABLE_EXPORT);
        assertThatThrownBy(() -> grant.recipients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MemoryShareGrant(grant.grant(), CONTEXT, MemoryScope.privateScope("marketing", "copy-agent"),
                "matter-1", "reuse", grant.recipients(), grant.projections(), grant.sources(), AT, AT.plusSeconds(60), MemoryShareGrant.State.ACTIVE))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryShareGrant(grant.grant(), CONTEXT, grant.scope(), "matter-1", "reuse", grant.recipients(),
                grant.projections(), grant.sources(), AT, AT, MemoryShareGrant.State.ACTIVE)).isInstanceOf(MemoryException.class);
    }

    @Test void exportPendingCommittedAndIndexReadinessCannotBeConflated() throws Exception {
        MemorySharedExport pending = export(MemorySharedExport.State.PENDING, null, MemoryIndexState.NOT_REQUESTED);
        MemoryExportRef ref = new MemoryExportRef("export-1", CONTEXT, version(1));
        MemorySharedExport committed = export(MemorySharedExport.State.COMMITTED, ref, MemoryIndexState.INDEX_PENDING);
        assertThat(pending.committedRef()).isNull();
        assertThat(spi(pending, MemorySharedExport.class)).isEqualTo(pending);
        assertThat(spi(committed, MemorySharedExport.class)).isEqualTo(committed);
        assertThatThrownBy(() -> export(MemorySharedExport.State.PENDING, ref, MemoryIndexState.NOT_REQUESTED)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> export(MemorySharedExport.State.PENDING, null, MemoryIndexState.INDEX_READY)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> export(MemorySharedExport.State.COMMITTED, null, MemoryIndexState.INDEX_PENDING)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> export(MemorySharedExport.State.COMMITTED, new MemoryExportRef("export-1", CONTEXT, version(2)), MemoryIndexState.INDEX_PENDING))
                .isInstanceOf(MemoryException.class);
    }

    @Test void closedMatterRequiresEvidenceButDoesNotBecomeBusinessState() throws Exception {
        MemorySourceDependency dependency = new MemorySourceDependency(source("confirmation", 1), null);
        MemoryMatter closed = new MemoryMatter("matter-1", CONTEXT, version(1), "write copy", MemoryMatter.State.CLOSED,
                new MemoryMatter.ClosureEvidence(MemoryMatter.ClosureKind.USER_CONFIRMATION, List.of(dependency)), List.of(dependency));
        assertThat(spi(closed, MemoryMatter.class)).isEqualTo(closed);
        assertThatThrownBy(() -> new MemoryMatter("matter-1", CONTEXT, version(1), "write copy", MemoryMatter.State.CLOSED, null, List.of()))
                .isInstanceOf(MemoryException.class);
    }

    @Test void safeErrorsHaveOnlyEnumsNoArbitraryMessageCauseSuppressedOrStackData() throws Exception {
        for (MemoryErrorCode code : MemoryErrorCode.values()) {
            MemoryException failure = new MemoryException(code, MemoryException.Field.IDENTITY);
            failure.addSuppressed(new IllegalStateException("untrusted provider text"));
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("untrusted", "method", "payload", 1)});
            MemoryException restored = (MemoryException) javaRoundTrip(failure);
            assertThat(restored.code()).isEqualTo(code);
            assertThat(restored.field()).isEqualTo(MemoryException.Field.IDENTITY);
            assertThat(restored.getMessage()).isEqualTo(code.name() + " (IDENTITY)");
            assertThat(restored.getCause()).isNull();
            assertThat(restored.getSuppressed()).isEmpty();
            assertThat(restored.getStackTrace()).isEmpty();
            assertThatThrownBy(() -> failure.initCause(new IllegalStateException("provider"))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(MemoryException.class.getConstructors()).allSatisfy(constructor ->
                assertThat(List.of(constructor.getParameterTypes())).doesNotContain(String.class, Throwable.class));
    }

    @Test void sourceRevisionHashAndCollectionBoundsAreExplicit() {
        assertThatThrownBy(() -> new MemoryRevision(0, "a".repeat(64), "memory-v2")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryRevision(1, "not-a-hash", "memory-v2")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, CONTEXT, "message", "event", null, version(1)))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryConversationVersion(-1, 0, "policy-v2")).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryCoveredRange(0, 1)).isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> MemoryJsonValue.array(java.util.Collections.nCopies(501, MemoryJsonValue.nil())))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new PreferenceHistoryCursor("block-1", 0, 1, 0)).isInstanceOf(MemoryException.class);
    }

    @Test void ordinaryDtosContainNoObjectFrameworkMessageOrCredentialComponents() {
        for (Class<?> type : List.of(MemoryContextKey.class, MemoryAssociation.class, MemorySourceRef.class,
                MemoryRevision.class, MemoryMessage.class, MemoryToolCall.class, MemoryToolResponse.class,
                MemoryJsonValue.class, MemorySummary.class, PreferenceBlock.class, PreferenceBlockKey.class,
                PreferenceTransition.class, PreferenceHistoryCursor.class, PreferenceHistoryPage.class,
                MemoryExperience.class, MemoryShareGrant.class, MemorySharedExport.class, MemoryExportRef.class)) {
            assertThat(Serializable.class.isAssignableFrom(type)).isTrue();
            for (var component : type.getRecordComponents()) {
                assertThat(component.getType()).isNotEqualTo(Object.class);
                assertThat(component.getName()).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("beacon")
                        .doesNotContainIgnoringCase("url").doesNotContainIgnoringCase("host").doesNotContainIgnoringCase("collection");
                assertThat(component.getGenericType().getTypeName()).doesNotContain("org.springframework.ai", "com.alibaba.cloud.ai", "java.lang.Object");
            }
        }
    }

    private <T> T spi(T value, Class<T> type) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(null, bytes);
        output.writeObject(value);
        output.flushBuffer();
        return serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray())).readObject(type);
    }

    private Object javaRoundTrip(Serializable value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return input.readObject(); }
    }

    private MemoryRevision version(long revision) { return new MemoryRevision(revision, "a".repeat(64), "memory-v2"); }
    private MemorySourceRef source(String id, long sequence) {
        return new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, CONTEXT, id, "event-" + id, sequence, version(1));
    }
    private MemoryJsonValue data() {
        return MemoryJsonValue.object(Map.of("facts", MemoryJsonValue.array(List.of(MemoryJsonValue.object(Map.of(
                "id", MemoryJsonValue.string("listing-1"), "price", MemoryJsonValue.number("123.4500"),
                "active", MemoryJsonValue.bool(true), "optional", MemoryJsonValue.nil())))), "empty", MemoryJsonValue.object(Map.of())));
    }
    private MemoryToolCall call(String id, String name) {
        return new MemoryToolCall(id, "local:" + name, name, MemoryJsonValue.object(Map.of("query", MemoryJsonValue.string("home"))));
    }
    private MemoryToolResponse response(String id, String name, MemoryToolResponse.ExecutionState state) {
        return new MemoryToolResponse(id, name, state, "brief", MemoryToolResponse.DataForm.INLINE_COMPLETE, data(), null, List.of());
    }
    private MemoryMessage message(String id, long sequence, MemoryMessage.Role role, String text,
                                  List<MemoryToolCall> calls, List<MemoryToolResponse> responses) {
        return new MemoryMessage(id, CONTEXT, sequence, ASSOCIATION, role, text, calls, responses, source(id, sequence));
    }
    private MemoryToolGroup group() {
        return new MemoryToolGroup(message("assistant", 1, MemoryMessage.Role.ASSISTANT, null,
                List.of(call("call-1", "search"), call("call-2", "read")), List.of()),
                List.of(message("response-1", 2, MemoryMessage.Role.TOOL, null, List.of(), List.of(response("call-1", "search", MemoryToolResponse.ExecutionState.SUCCEEDED))),
                        message("response-2", 40, MemoryMessage.Role.TOOL, null, List.of(), List.of(response("call-2", "read", MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN)))));
    }
    private MemorySummary summary() {
        MemorySourceDependency dependency = new MemorySourceDependency(source("input", 1), new MemoryGrantRef("grant-1", 3));
        return new MemorySummary("summary-1", CONTEXT, ASSOCIATION, version(1), 5, new MemoryRunVersion("run-1", 9),
                new MemoryCoveredRange(1, 40), List.of(new MemoryCoveredRange(1, 40)),
                List.of(new MemorySummary.Item(MemorySummary.ItemKind.NEGATION, "not a permanent default", List.of(dependency)),
                        new MemorySummary.Item(MemorySummary.ItemKind.EXCEPTION, "this matter only", List.of(dependency)),
                        new MemorySummary.Item(MemorySummary.ItemKind.UNRESOLVED, "outcome unknown", List.of(dependency))),
                List.of(new MemoryEntityAnchor("entity-1", CONTEXT, "matter-1", version(1), List.of("earlier home"), "listing fact", List.of(dependency))),
                List.of(dependency));
    }
    private PreferenceTransition transition(long ordinal, long revision) {
        return new PreferenceTransition("transition-" + ordinal, ordinal, revision, PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.OBSERVATION, PreferenceTransition.Polarity.AFFIRMATIVE, MemoryJsonValue.string("short"),
                null, null, AT, AT, AT, null, List.of(), .8, "extract-v1", List.of(new MemorySourceDependency(source("input-" + ordinal, 1), null)));
    }
    private MemoryExperience experience() {
        MemoryExperience.Evidence evidence = new MemoryExperience.Evidence("feedback-1", MemoryExperience.EvidenceKind.USER_FEEDBACK,
                MemoryExperience.Outcome.UNKNOWN, MemoryExperience.Feedback.NEGATIVE, true, AT, AT,
                List.of(new MemorySourceDependency(source("feedback", 1), null)));
        return new MemoryExperience("experience-1", CONTEXT, ASSOCIATION, version(2), "copywriting", "short copy requested", "avoid long copy",
                MemoryState.ACTIVE, "attributable feedback", 1L, .8, .7, AT, AT, "policy-v2", List.of(evidence));
    }
    private MemoryShareGrant grant() {
        return new MemoryShareGrant(new MemoryGrantRef("grant-1", 3), CONTEXT,
                new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "group-1"), "matter-1", "reuse",
                List.of(new MemoryShareGrant.Participant("supervisor", "supervisor-agent", "supervisor-conversation")), List.of(MemoryShareGrant.Projection.DURABLE_EXPORT),
                List.of(source("result", 40)), AT, AT.plusSeconds(3600), MemoryShareGrant.State.ACTIVE);
    }
    private MemorySharedExport export(MemorySharedExport.State state, MemoryExportRef ref, MemoryIndexState indexState) {
        return new MemorySharedExport("source-export-1", CONTEXT, ASSOCIATION, "invocation-1", version(1), new MemoryGrantRef("grant-1", 3),
                "reuse", "brief", data(), List.of(new MemorySourceDependency(source("result", 40), new MemoryGrantRef("grant-1", 3))), state, ref, indexState);
    }
}
