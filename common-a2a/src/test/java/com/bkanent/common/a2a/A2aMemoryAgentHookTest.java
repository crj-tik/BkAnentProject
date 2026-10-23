package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.bkanent.common.agent.DurableMemoryExportDirective;
import com.bkanent.common.memory.*;
import com.bkanent.common.rag.A2aBeaconBinding;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Instant;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class A2aMemoryAgentHookTest {
    private static final String PRIVATE_KEY_FILE = createPrivateKeyFile();

    @Test
    void publishesOnlyTheExplicitSafeFieldsFromTheArchivedFinalAssistantTurn() throws Exception {
        var fixture = new MemoryFixture(List.of(), new MemoryConversationVersion(0, 0, "policy-v1"));
        AtomicReference<MemoryShareGrant> createdGrant = new AtomicReference<>();
        AtomicReference<MemorySharedExportSubmitRequest> submittedExport = new AtomicReference<>();
        AtomicReference<MemoryRequestProof> grantProof = new AtomicReference<>();
        when(fixture.rpc.manageShareGrant(any(), any())).thenAnswer(call -> {
            MemoryShareGrantManagementRequest request = call.getArgument(0);
            grantProof.set(call.getArgument(1));
            createdGrant.set(request.grant());
            return new MemoryShareGrantManagementResponse(request.grant(), true);
        });
        when(fixture.rpc.submitSharedExport(any(), any())).thenAnswer(call -> {
            MemorySharedExportSubmitRequest request = call.getArgument(0);
            submittedExport.set(request);
            MemoryRevision version = new MemoryRevision(1, "b".repeat(64), MemorySharedExportSubmitRequest.SCHEMA_VERSION);
            return new MemorySharedExportStatusResponse(request.sourceExportId(), version,
                    MemorySharedExport.State.PENDING, null, MemoryIndexState.NOT_REQUESTED);
        });
        when(fixture.rpc.commitSharedExport(any(), any())).thenAnswer(call -> {
            MemorySharedExportCommitRequest request = call.getArgument(0);
            MemoryRevision version = new MemoryRevision(request.contentRevision(), request.contentHash(),
                    MemorySharedExportSubmitRequest.SCHEMA_VERSION);
            MemoryExportRef ref = new MemoryExportRef(request.sourceExportId(), request.contextKey(), version);
            return new MemorySharedExportStatusResponse(request.sourceExportId(), version,
                    MemorySharedExport.State.COMMITTED, ref, MemoryIndexState.INDEX_PENDING);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("listing-master-service", "listing", "listing-agent", () -> fixture.rpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), enabledProperties(),
                mock(ChatModel.class), new ObjectMapper(), executor);
        String taskId = "listing-task-shared-export";
        var directive = exportDirective();
        var invocation = hook.invocation(taskId, "listing-context-shared-export", directive);
        var lease = lease(taskId, "7", "listing-master-service");
        register(lease);
        var config = config(taskId, invocation);
        try {
            hook.beforeAgent(state(List.of(new SystemMessage("listing rules"),
                    new UserMessage("核对并整理房源结果"))), config).join();
            hook.afterAgent(state(List.of(new SystemMessage("listing rules"),
                    new UserMessage("核对并整理房源结果"), new AssistantMessage("房源结果已核实"))), config).join();

            A2aOutput output = new A2aOutput(A2aOutputPolicy.JSON_MODE,
                    Map.of("result", "可正常带看", "status", "ACTIVE", "privateDetail", "must not export"),
                    "{}", "房源已核实", List.of());
            MemoryExportReceipt receipt = hook.publishExport(invocation, output);

            assertThat(receipt.exportState()).isEqualTo("COMMITTED");
            assertThat(receipt.memoryRefs()).hasSize(1);
            assertThat(createdGrant.get().projections()).containsExactly(
                    MemoryShareGrant.Projection.COLLABORATION, MemoryShareGrant.Projection.DURABLE_EXPORT);
            assertThat(fixture.delivered.get().messages()).singleElement().satisfies(message -> {
                assertThat(message.role()).isEqualTo(MemoryMessage.Role.ASSISTANT);
                assertThat(createdGrant.get().sources()).containsExactly(message.source());
            });
            assertThat(submittedExport.get().payload().properties().keySet()).containsExactlyInAnyOrder("result", "status");
            assertThat(submittedExport.get().brief()).isEqualTo("房源已核实");
            assertThat(grantProof.get().actor()).isEqualTo(MemoryRequestProof.Actor.USER);
            assertThat(createdGrant.get().sourceContext().ownerId()).isEqualTo("7");
        } finally {
            hook.finish(invocation);
            discard(lease);
            executor.shutdownNow();
        }
    }

    @Test
    void actualReactAgentLoadsAuthorizedHistoryAndArchivesTheCurrentTurnUnderTrustedIdentity() throws Exception {
        MemoryV2RpcService rpc = mock(MemoryV2RpcService.class);
        AtomicReference<MemoryContextKey> observedContext = new AtomicReference<>();
        AtomicReference<MemoryDeliveryRequest> delivered = new AtomicReference<>();
        AtomicReference<MemoryRequestProof> permitProof = new AtomicReference<>();
        String taskId = "marketing-task-1";
        var oldVersion = new MemoryConversationVersion(1, 1, "policy-v1");
        AtomicReference<MemoryConversationVersion> currentVersion = new AtomicReference<>(oldVersion);
        when(rpc.manageWriter(any(), any())).thenAnswer(invocation -> {
            MemoryWriterRequest request = invocation.getArgument(0);
            observedContext.set(request.contextKey());
            return new MemoryWriterResponse(new MemoryWriteAuthority(request.runId(), 4),
                    System.currentTimeMillis() + 60_000, currentVersion.get());
        });
        when(rpc.readWorkingSet(any(), any())).thenAnswer(invocation -> {
            MemoryWorkingSetRequest request = invocation.getArgument(0);
            observedContext.set(request.contextKey());
            return new MemoryWorkingSetResponse(request.contextKey(), currentVersion.get(),
                    List.of(oldMessage(request.contextKey())), List.of(), List.of());
        });
        when(rpc.checkBoundary(any(), any())).thenAnswer(invocation -> {
            MemoryBoundaryRequest request = invocation.getArgument(0);
            observedContext.set(request.contextKey());
            return new MemoryBoundaryResponse(request.adoptedVersion(), true);
        });
        when(rpc.requestReceivePermit(any(), any())).thenAnswer(invocation -> {
            MemoryReceivePermitRequest request = invocation.getArgument(0);
            permitProof.set(invocation.getArgument(1));
            observedContext.set(request.contextKey());
            return new MemoryReceivePermitResponse("permit-a2a-1", request.events().stream()
                    .map(event -> new MemoryReceivePermitResponse.EventAcceptance(event.eventId(), true, null)).toList());
        });
        when(rpc.deliverMessages(any(), any())).thenAnswer(invocation -> {
            MemoryDeliveryRequest request = invocation.getArgument(0);
            observedContext.set(request.contextKey());
            delivered.set(request);
            long last = request.messages().get(request.messages().size() - 1).sequence();
            var updated = new MemoryConversationVersion(currentVersion.get().revision() + 1, last, "policy-v1");
            currentVersion.set(updated);
            return new MemoryDeliveryResponse(request.permitId(), updated,
                    request.messages().stream().map(message -> new MemoryDeliveryResponse.DeliveryResult(message.sequence(), true, null)).toList());
        });

        A2aMemoryProperties properties = enabledProperties();
        MemoryWorkingSetCache cache = new MemoryWorkingSetCache(16, 1024 * 1024, 60_000, Clock.systemUTC());
        ExecutorService summaryExecutor = Executors.newSingleThreadExecutor();
        A2aMemoryAgentHook hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent",
                () -> rpc, cache, properties, mock(ChatModel.class), new ObjectMapper(), summaryExecutor);
        var invocation = hook.invocation(taskId, "private-context-id");
        var lease = lease(taskId, "7", "marketing-content-service");
        register(lease);

        AtomicReference<Prompt> received = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            received.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("本轮答复"))));
        });
        var agent = ReactAgent.builder().name("fixture-marketing-agent").model(model).enableLogging(false).hooks(hook).build();
        var config = RunnableConfig.builder().threadId(taskId)
                .addMetadata(A2aMemoryAgentHook.INVOCATION_METADATA_KEY, invocation)
                .addMetadata("ownerId", "999999").build();

        try {
            agent.call(List.of(new SystemMessage("固定系统规则"), new UserMessage("继续昨天的方案")), config);
            assertThat(received.get().getContents()).contains("之前确认的预算是100万", "继续昨天的方案");
            assertThat(observedContext.get()).isNotNull();
            assertThat(observedContext.get().ownerId()).isEqualTo("7");
            assertThat(observedContext.get().domainId()).isEqualTo("marketing");
            assertThat(observedContext.get().agentId()).isEqualTo("marketing-agent");
            assertThat(observedContext.get().conversationId()).startsWith("a2a-context-").doesNotContain("private-context-id");
            assertThat(permitProof.get().actor()).isEqualTo(MemoryRequestProof.Actor.USER);
            assertThat(delivered.get()).isNotNull();
            assertThat(delivered.get().messages()).extracting(MemoryMessage::role)
                    .containsExactly(MemoryMessage.Role.USER, MemoryMessage.Role.ASSISTANT);
            assertThat(delivered.get().messages()).extracting(MemoryMessage::text)
                    .containsExactly("继续昨天的方案", "本轮答复");
            assertThat(invocation.memoryStatus()).isEqualTo("APPLIED");
            assertThat(invocation.archiveStatus()).isEqualTo("RECORDED");
            assertThat(new ObjectMapper().writeValueAsString(delivered.get()))
                    .doesNotContain("BEGIN PRIVATE KEY", "privateKey", "signingKey", "opaque-a2a-test-token",
                            "beacon", "A2aLease", "RagCredentialContext");
            verify(rpc, times(1)).readWorkingSet(any(), any());
            verify(rpc, times(1)).deliverMessages(any(), any());
            hook.finish(invocation);
            discard(lease);

            String nextTaskId = "marketing-task-2";
            var nextLease = lease(nextTaskId, "7", "marketing-content-service");
            register(nextLease);
            var nextInvocation = hook.invocation(nextTaskId, "private-context-id");
            var nextConfig = RunnableConfig.builder().threadId(nextTaskId)
                    .addMetadata(A2aMemoryAgentHook.INVOCATION_METADATA_KEY, nextInvocation).build();
            try {
                agent.call(List.of(new SystemMessage("固定系统规则"), new UserMessage("沿用刚才的方案继续")), nextConfig);
                assertThat(received.get().getContents()).contains("之前确认的预算是100万", "本轮答复", "沿用刚才的方案继续");
                assertThat(nextInvocation.memoryStatus()).isEqualTo("APPLIED");
                assertThat(nextInvocation.archiveStatus()).isEqualTo("RECORDED");
                verify(rpc, times(1)).readWorkingSet(any(), any());
                verify(rpc, times(2)).deliverMessages(any(), any());
            } finally {
                hook.finish(nextInvocation);
                discard(nextLease);
            }
        } finally {
            summaryExecutor.shutdownNow();
        }
    }

    @Test
    void readDisabledDeliveryOnlyTurnDrainsAfterDeliverySwitchIsClosed() throws Exception {
        var fixture = new MemoryFixture(List.of(), new MemoryConversationVersion(1, 1, "policy-v1"));
        doAnswer(call -> {
            MemoryWorkingSetRequest request = call.getArgument(0);
            return new MemoryWorkingSetResponse(request.contextKey(), fixture.version.get(),
                    List.of(oldMessage(request.contextKey())), List.of(), List.of());
        }).when(fixture.rpc).readWorkingSet(any(), any());
        var properties = enabledProperties();
        properties.setReadEnabled(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> fixture.rpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), properties,
                mock(ChatModel.class), new ObjectMapper(), executor);
        String taskId = "marketing-task-delivery-only";
        var invocation = hook.invocation(taskId, "delivery-only-context");
        var lease = lease(taskId, "7", "marketing-content-service");
        register(lease);
        var config = config(taskId, invocation);
        try {
            var start = List.<Message>of(new SystemMessage("marketing rules"), new UserMessage("当前请求"));
            assertThat(hook.beforeAgent(state(start), config).join()).isEmpty();
            assertThat(invocation.memoryStatus()).isEqualTo("DELIVERY_ONLY");
            verify(fixture.rpc, never()).readWorkingSet(any(), any());
            properties.setDeliveryEnabled(false); // close new delivery admission after this task was accepted
            hook.afterAgent(state(List.of(new SystemMessage("marketing rules"), new UserMessage("当前请求"),
                    new AssistantMessage("本轮结果"))), config).join();

            assertThat(invocation.archiveStatus()).isEqualTo("RECORDED");
            assertThat(fixture.delivered.get().messages()).extracting(MemoryMessage::role)
                    .containsExactly(MemoryMessage.Role.USER, MemoryMessage.Role.ASSISTANT);
            assertThat(fixture.delivered.get().messages()).extracting(MemoryMessage::text)
                    .containsExactly("当前请求", "本轮结果");
            verify(fixture.rpc, times(1)).requestReceivePermit(any(), any());
            verify(fixture.rpc, times(1)).deliverMessages(any(), any());
            verify(fixture.rpc, times(1)).readWorkingSet(any(), any());
        } finally {
            hook.finish(invocation);
            discard(lease);
            executor.shutdownNow();
        }
    }

    @Test
    void listingHookColdLoadsAuthorizedHistoryAndArchivesSourceHashedToolEvidence() throws Exception {
        var fixture = new MemoryFixture(List.of(), new MemoryConversationVersion(2, 1, "policy-v1"));
        doAnswer(call -> {
            MemoryWorkingSetRequest request = call.getArgument(0);
            return new MemoryWorkingSetResponse(request.contextKey(), fixture.version.get(),
                    List.of(oldMessage(request.contextKey())), List.of(), List.of());
        }).when(fixture.rpc).readWorkingSet(any(), any());
        ExecutorService summaryExecutor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("listing-master-service", "listing", "listing-agent", () -> fixture.rpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), enabledProperties(),
                mock(ChatModel.class), new ObjectMapper(), summaryExecutor);
        String taskId = "listing-task-source-proof";
        var lease = lease(taskId, "7", "listing-master-service");
        register(lease);
        var invocation = hook.invocation(taskId, "listing-context-source-proof");
        try {
            var config = config(taskId, invocation);
            var update = hook.beforeAgent(state(List.of(new SystemMessage("listing rules"), new UserMessage("继续核对这个房源"))), config).join();
            @SuppressWarnings("unchecked")
            List<Message> assembled = (List<Message>) ((ReplaceAllWith<?>) update.get("messages")).newValues();
            assertThat(assembled).extracting(Message::getText)
                    .contains("之前确认的预算是100万", "继续核对这个房源");
            assertThat(invocation.contextKey().ownerId()).isEqualTo("7");
            assertThat(invocation.contextKey().domainId()).isEqualTo("listing");
            assertThat(invocation.contextKey().agentId()).isEqualTo("listing-agent");

            List<Message> completedTurn = new ArrayList<>(assembled);
            completedTurn.add(AssistantMessage.builder().content("调用只读房源工具")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("listing-read-1", "function", "getListingDetail",
                            "{\"listingId\":42}"))).build());
            completedTurn.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                    "listing-read-1", "getListingDetail", "{\"listingId\":42,\"status\":\"ACTIVE\",\"source\":\"listing_info\"}"))).build());
            completedTurn.add(new AssistantMessage("房源仍处于有效挂牌状态"));
            hook.afterAgent(state(completedTurn), config).join();

            assertThat(invocation.memoryStatus()).isEqualTo("APPLIED");
            assertThat(invocation.archiveStatus()).isEqualTo("RECORDED");
            MemoryDeliveryRequest delivery = fixture.delivered.get();
            assertThat(delivery).isNotNull();
            assertThat(delivery.messages()).extracting(MemoryMessage::role)
                    .containsExactly(MemoryMessage.Role.USER, MemoryMessage.Role.ASSISTANT,
                            MemoryMessage.Role.TOOL, MemoryMessage.Role.ASSISTANT);
            MemoryMessage toolEvidence = delivery.messages().get(2);
            assertThat(toolEvidence.toolResponses()).singleElement().satisfies(response -> {
                assertThat(response.toolName()).isEqualTo("getListingDetail");
                assertThat(new ObjectMapper().writeValueAsString(response.responseData()))
                        .contains("listing_info", "42", "ACTIVE");
            });
            assertThat(toolEvidence.source().kind()).isEqualTo(MemorySourceRef.Kind.MESSAGE);
            assertThat(toolEvidence.source().version().contentHash())
                    .isEqualTo(MemoryCanonicalHash.messageHash(toolEvidence));
            verify(fixture.rpc, times(1)).readWorkingSet(any(), any());
        } finally {
            hook.finish(invocation);
            discard(lease);
            summaryExecutor.shutdownNow();
        }
    }

    @Test
    void noMatchIsReportedSeparatelyAndMissingHistoricalReferentsAreNotGuessed() throws Exception {
        var fixture = new MemoryFixture(List.of(), new MemoryConversationVersion(0, 0, "policy-v1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> fixture.rpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), enabledProperties(),
                mock(ChatModel.class), new ObjectMapper(), executor);
        String taskId = "marketing-task-no-match";
        var lease = lease(taskId, "7", "marketing-content-service");
        register(lease);
        var invocation = hook.invocation(taskId, "context-no-match");
        var config = config(taskId, invocation);
        try {
            Map<String, Object> update = hook.beforeAgent(state(List.of(new SystemMessage("system"),
                    new UserMessage("沿用之前确认的方案继续"))), config).join();
            @SuppressWarnings("unchecked")
            List<Message> assembled = (List<Message>) ((ReplaceAllWith<?>) update.get("messages")).newValues();
            assertThat(invocation.memoryStatus()).isEqualTo("NO_MATCH");
            assertThat(assembled).anySatisfy(message -> assertThat(message.getText())
                    .contains("没有找到可验证的历史记忆", "向用户澄清", "不得根据缺失的历史内容猜测"));

            List<Message> completedTurn = new ArrayList<>(assembled);
            completedTurn.add(new AssistantMessage("请补充之前确认的方案"));
            hook.afterAgent(state(completedTurn), config).join();
            assertThat(fixture.delivered.get().messages()).extracting(MemoryMessage::role)
                    .containsExactly(MemoryMessage.Role.USER, MemoryMessage.Role.ASSISTANT);
            assertThat(fixture.delivered.get().messages()).extracting(MemoryMessage::text)
                    .contains("沿用之前确认的方案继续", "请补充之前确认的方案");
        } finally {
            hook.finish(invocation);
            discard(lease);
            executor.shutdownNow();
        }
    }

    @Test
    void memoryUnavailableIsReportedAndAddsNoHistoryWhileAuthorizationDenialFailsClosed() throws Exception {
        MemoryV2RpcService unavailable = mock(MemoryV2RpcService.class);
        when(unavailable.manageWriter(any(), any())).thenThrow(new IllegalStateException("fixture unavailable"));
        var props = enabledProperties();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> unavailable,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), props,
                mock(ChatModel.class), new ObjectMapper(), executor);
        String taskId = "marketing-task-unavailable";
        var lease = lease(taskId, "7", "marketing-content-service");
        register(lease);
        var invocation = hook.invocation(taskId, "context-unavailable");
        var state = state(List.of(new SystemMessage("system"), new UserMessage("hello")));
        var config = config(taskId, invocation);
        try {
            Map<String, Object> update = hook.beforeAgent(state, config).join();
            assertThat(update).containsKey("messages");
            assertThat(invocation.memoryStatus()).isEqualTo("MEMORY_UNAVAILABLE");
            @SuppressWarnings("unchecked") List<Message> degraded = (List<Message>) ((ReplaceAllWith<?>) update.get("messages")).newValues();
            assertThat(degraded).anySatisfy(message -> assertThat(message.getText())
                    .contains("历史记忆不可用", "向用户询问补充", "不要猜测历史事实"));
        } finally {
            hook.finish(invocation);
            discard(lease);
            executor.shutdownNow();
        }

        MemoryV2RpcService forbidden = mock(MemoryV2RpcService.class);
        when(forbidden.manageWriter(any(), any())).thenThrow(new MemoryException(MemoryErrorCode.FORBIDDEN));
        ExecutorService deniedExecutor = Executors.newSingleThreadExecutor();
        var deniedHook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> forbidden,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), props,
                mock(ChatModel.class), new ObjectMapper(), deniedExecutor);
        String deniedTask = "marketing-task-denied";
        var deniedLease = lease(deniedTask, "7", "marketing-content-service");
        register(deniedLease);
        try {
            assertThatThrownBy(() -> deniedHook.beforeAgent(state(List.of(new SystemMessage("system"), new UserMessage("hello"))),
                    config(deniedTask, deniedHook.invocation(deniedTask, "context-denied"))).join())
                    .hasRootCauseInstanceOf(MemoryException.class);
        } finally {
            discard(deniedLease);
            deniedExecutor.shutdownNow();
        }
    }

    @Test
    void missingA2aLeaseFailsClosedBeforeTheAgentCanUseMemory() {
        var properties = enabledProperties();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent",
                () -> mock(MemoryV2RpcService.class), new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()),
                properties, mock(ChatModel.class), new ObjectMapper(), executor);
        String taskId = "marketing-task-without-lease";
        var invocation = hook.invocation(taskId, "private-context-without-lease");
        try {
            assertThatThrownBy(() -> hook.beforeAgent(state(List.of(new SystemMessage("system"), new UserMessage("hello"))),
                    config(taskId, invocation)).join())
                    .hasRootCauseInstanceOf(MemoryException.class)
                    .hasMessageContaining(MemoryErrorCode.AUTHENTICATION_FAILED.name());
            assertThat(invocation.memoryStatus()).isEqualTo("NOT_APPLIED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interviewLoadsAuthorizedSummaryAndKeepsSameA2aContextPrivateAcrossDomains() throws Exception {
        MemoryV2RpcService interviewRpc = mock(MemoryV2RpcService.class);
        AtomicReference<MemoryContextKey> interviewContext = new AtomicReference<>();
        AtomicReference<MemorySourceDependency> interviewSummaryDependency = new AtomicReference<>();
        AtomicReference<MemoryBoundaryRequest> interviewBoundary = new AtomicReference<>();
        var interviewVersion = new MemoryConversationVersion(2, 1, "policy-v1");
        when(interviewRpc.manageWriter(any(), any())).thenAnswer(call -> {
            MemoryWriterRequest request = call.getArgument(0);
            interviewContext.set(request.contextKey());
            return new MemoryWriterResponse(new MemoryWriteAuthority(request.runId(), 1),
                    System.currentTimeMillis() + 60_000, interviewVersion);
        });
        when(interviewRpc.readWorkingSet(any(), any())).thenAnswer(call -> {
            MemoryWorkingSetRequest request = call.getArgument(0);
            interviewContext.set(request.contextKey());
            var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, request.contextKey(), "interview-source",
                    "interview-event", 1L, new MemoryRevision(1, "0".repeat(64), "a2a.message.v2"));
            var dependency = new MemorySourceDependency(source, null);
            interviewSummaryDependency.set(dependency);
            var association = new MemoryAssociation(null, "interview-run", "interview-task", null);
            var item = new MemorySummary.Item(MemorySummary.ItemKind.FACT, "已确认本次访谈聚焦职业转型", List.of(dependency));
            var summary = new MemorySummary("interview-summary", request.contextKey(), association,
                    new MemoryRevision(1, "1".repeat(64), "summary.v2.1"), 1, null,
                    new MemoryCoveredRange(1, 1), List.of(), List.of(item), List.of(), List.of(dependency));
            return new MemoryWorkingSetResponse(request.contextKey(), interviewVersion, List.of(), List.of(summary), List.of());
        });
        when(interviewRpc.checkBoundary(any(), any())).thenAnswer(call -> {
            MemoryBoundaryRequest request = call.getArgument(0);
            interviewBoundary.set(request);
            interviewContext.set(request.contextKey());
            return new MemoryBoundaryResponse(interviewVersion, true);
        });

        ExecutorService interviewExecutor = Executors.newSingleThreadExecutor();
        var interviewHook = new A2aMemoryAgentHook("interview-service", "interview", "interview-agent", () -> interviewRpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), enabledProperties(),
                mock(ChatModel.class), new ObjectMapper(), interviewExecutor);
        String interviewTask = "interview-task-private";
        var interviewLease = lease(interviewTask, "7", "interview-service");
        register(interviewLease);
        var interviewInvocation = interviewHook.invocation(interviewTask, "same-opaque-context");
        try {
            Map<String, Object> update = interviewHook.beforeAgent(
                    state(List.of(new SystemMessage("interview system"), new UserMessage("继续准备问题"))),
                    config(interviewTask, interviewInvocation)).join();
            @SuppressWarnings("unchecked") List<Message> assembled = (List<Message>) ((ReplaceAllWith<?>) update.get("messages")).newValues();
            assertThat(assembled).anySatisfy(message -> assertThat(message.getText()).contains("已确认本次访谈聚焦职业转型"));
            assertThat(interviewInvocation.contextKey().domainId()).isEqualTo("interview");
            assertThat(interviewInvocation.contextKey().agentId()).isEqualTo("interview-agent");
            assertThat(interviewInvocation.contextKey().ownerId()).isEqualTo("7");
            assertThat(interviewContext.get()).isEqualTo(interviewInvocation.contextKey());
            assertThat(interviewBoundary.get().dependencies()).contains(interviewSummaryDependency.get());
            verify(interviewRpc, atLeastOnce()).checkBoundary(any(), any());
        } finally {
            interviewHook.finish(interviewInvocation);
            discard(interviewLease);
            interviewExecutor.shutdownNow();
        }

        MemoryV2RpcService marketingRpc = mock(MemoryV2RpcService.class);
        AtomicReference<MemoryContextKey> marketingContext = new AtomicReference<>();
        when(marketingRpc.manageWriter(any(), any())).thenAnswer(call -> {
            MemoryWriterRequest request = call.getArgument(0);
            marketingContext.set(request.contextKey());
            return new MemoryWriterResponse(new MemoryWriteAuthority(request.runId(), 1),
                    System.currentTimeMillis() + 60_000, new MemoryConversationVersion(1, 0, "policy-v1"));
        });
        when(marketingRpc.readWorkingSet(any(), any())).thenAnswer(call -> {
            MemoryWorkingSetRequest request = call.getArgument(0);
            marketingContext.set(request.contextKey());
            return new MemoryWorkingSetResponse(request.contextKey(), new MemoryConversationVersion(1, 0, "policy-v1"),
                    List.of(), List.of(), List.of());
        });
        when(marketingRpc.checkBoundary(any(), any())).thenAnswer(call -> {
            MemoryBoundaryRequest request = call.getArgument(0);
            marketingContext.set(request.contextKey());
            return new MemoryBoundaryResponse(request.adoptedVersion(), true);
        });
        ExecutorService marketingExecutor = Executors.newSingleThreadExecutor();
        var marketingHook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> marketingRpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), enabledProperties(),
                mock(ChatModel.class), new ObjectMapper(), marketingExecutor);
        String marketingTask = "marketing-task-private";
        var marketingLease = lease(marketingTask, "7", "marketing-content-service");
        register(marketingLease);
        var marketingInvocation = marketingHook.invocation(marketingTask, "same-opaque-context");
        try {
            marketingHook.beforeAgent(state(List.of(new SystemMessage("marketing system"), new UserMessage("继续文案"))),
                    config(marketingTask, marketingInvocation)).join();
            assertThat(marketingContext.get().ownerId()).isEqualTo("7");
            assertThat(marketingContext.get().domainId()).isEqualTo("marketing");
            assertThat(marketingContext.get().agentId()).isEqualTo("marketing-agent");
            assertThat(marketingContext.get().conversationId()).isEqualTo(interviewContext.get().conversationId());
            assertThat(marketingContext.get()).isNotEqualTo(interviewContext.get());
        } finally {
            marketingHook.finish(marketingInvocation);
            discard(marketingLease);
            marketingExecutor.shutdownNow();
        }
    }

    @Test
    void compressionCommitsOnlyACompletePrefixBeforeOutcomeUnknownToolGroups() throws Exception {
        var initial = protectedHistory();
        MemoryFixture fixture = new MemoryFixture(initial, new MemoryConversationVersion(4, 4, "policy-v1"));
        var properties = enabledProperties();
        properties.setCompressionEnabled(true);
        properties.setSoftTokens(1);
        properties.setRecentMessages(1);
        properties.validate();
        ExecutorService summaryExecutor = Executors.newSingleThreadExecutor();
        ChatModel summaryModel = mock(ChatModel.class);
        when(summaryModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"items\":[{\"kind\":\"GOAL\",\"text\":\"用户需要新房源文案\",\"evidence\":[0]}]}")))));
        var hook = new A2aMemoryAgentHook("marketing-content-service", "marketing", "marketing-agent", () -> fixture.rpc,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), properties,
                summaryModel, new ObjectMapper(), summaryExecutor);
        String taskId = "marketing-task-compaction";
        var lease = lease(taskId, "7", "marketing-content-service");
        register(lease);
        var invocation = hook.invocation(taskId, "context-compaction");
        try {
            var state = state(List.of(new SystemMessage("system"), new UserMessage("继续文案")));
            Map<String, Object> update = hook.beforeAgent(state, config(taskId, invocation)).join();
            assertThat(update).containsKey("messages");
            assertThat(fixture.summary.get()).isNotNull();
            assertThat(fixture.summary.get().coveredRange()).isEqualTo(new MemoryCoveredRange(1, 1));
            assertThat(invocation.compactionStatus()).isEqualTo("APPLIED");
            List<Message> assembled = ((ReplaceAllWith<Message>) update.get("messages")).newValues();
            assertThat(assembled).anySatisfy(message -> assertThat(message.getText()).contains("用户需要新房源文案"));
            assertThat(assembled).anySatisfy(message -> assertThat(message).isInstanceOf(AssistantMessage.class)
                    .satisfies(value -> assertThat(((AssistantMessage) value).getToolCalls()).extracting(AssistantMessage.ToolCall::id).contains("call-unknown")));
            assertThat(assembled).anySatisfy(message -> assertThat(message).isInstanceOf(ToolResponseMessage.class)
                    .satisfies(value -> assertThat(((ToolResponseMessage) value).getResponses().get(0).responseData())
                            .contains("OUTCOME_UNKNOWN", "businessSuccessVerified")));
            assertThat(invocation.session().snapshot().summaries()).extracting(summary -> summary.coveredRange())
                    .containsExactly(new MemoryCoveredRange(1, 1));
            assertThat(invocation.session().snapshot().messages()).extracting(MemoryMessage::sequence).containsExactly(2L, 3L, 4L);
        } finally {
            hook.finish(invocation);
            discard(lease);
            summaryExecutor.shutdownNow();
        }
    }

    private MemoryMessage oldMessage(MemoryContextKey context) {
        String event = "fixture-old-event";
        String id = "fixture-old-message";
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, id, event, 1L,
                new MemoryRevision(1, "0".repeat(64), "a2a.message.v2"));
        return new MemoryMessage(id, context, 1, new MemoryAssociation(null, "fixture-run", "fixture-task", null),
                MemoryMessage.Role.USER, "之前确认的预算是100万", List.of(), List.of(), source);
    }

    private List<MemoryMessage> protectedHistory() {
        var context = new MemoryContextKey("7", "marketing", "marketing-agent", "a2a-context-fixture");
        var association = new MemoryAssociation(null, "fixture-run", "fixture-task",
                new MemoryAssociation.DomainSessionRef("marketing", context.conversationId()));
        return List.of(
                message(context, association, 1, "fixture-user-1", MemoryMessage.Role.USER,
                        "请为这个房源准备营销文案", List.of(), List.of()),
                message(context, association, 2, "fixture-assistant-call", MemoryMessage.Role.ASSISTANT,
                        "正在读取房源资料", List.of(new MemoryToolCall("call-unknown", "a2a-tool:listingLookup", "listingLookup",
                                MemoryJsonValue.object(Map.of("listingId", MemoryJsonValue.string("L-1"))))), List.of()),
                message(context, association, 3, "fixture-tool-result", MemoryMessage.Role.TOOL, null, List.of(),
                        List.of(new MemoryToolResponse("call-unknown", "listingLookup", MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN,
                                null, MemoryToolResponse.DataForm.INLINE_COMPLETE,
                                MemoryJsonValue.string("{\"status\":\"OUTCOME_UNKNOWN\"}"), null, List.of()))),
                message(context, association, 4, "fixture-user-tail", MemoryMessage.Role.USER, "继续文案", List.of(), List.of()));
    }

    private MemoryMessage message(MemoryContextKey context, MemoryAssociation association, long sequence, String id,
                                  MemoryMessage.Role role, String text, List<MemoryToolCall> calls,
                                  List<MemoryToolResponse> responses) {
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, id, "event-" + id, sequence,
                new MemoryRevision(1, "0".repeat(64), "a2a.message.v2"));
        return new MemoryMessage(id, context, sequence, association, role, text, calls, responses, source);
    }

    private static final class MemoryFixture {
        private final MemoryV2RpcService rpc = mock(MemoryV2RpcService.class);
        private final AtomicReference<MemoryConversationVersion> version;
        private final AtomicReference<MemorySummary> summary = new AtomicReference<>();
        private final AtomicReference<MemoryDeliveryRequest> delivered = new AtomicReference<>();

        private MemoryFixture(List<MemoryMessage> messages, MemoryConversationVersion initialVersion) throws Exception {
            version = new AtomicReference<>(initialVersion);
            when(rpc.manageWriter(any(), any())).thenAnswer(invocation -> {
                MemoryWriterRequest request = invocation.getArgument(0);
                return new MemoryWriterResponse(new MemoryWriteAuthority(request.runId(), 4),
                        System.currentTimeMillis() + 60_000, version.get());
            });
            when(rpc.readWorkingSet(any(), any())).thenAnswer(invocation -> {
                MemoryWorkingSetRequest request = invocation.getArgument(0);
                var value = new MemoryWorkingSetResponse(request.contextKey(), version.get(), messages, List.of(), List.of());
                return value;
            });
            when(rpc.checkBoundary(any(), any())).thenAnswer(invocation -> {
                MemoryBoundaryRequest request = invocation.getArgument(0);
                return new MemoryBoundaryResponse(request.adoptedVersion(), true);
            });
            when(rpc.submitSummary(any(), any())).thenAnswer(invocation -> {
                MemorySummaryRequest request = invocation.getArgument(0);
                summary.set(request.summary());
                var updated = new MemoryConversationVersion(version.get().revision() + 1,
                        version.get().lastSequence(), version.get().policyVersion());
                version.set(updated);
                return new MemorySummaryResponse(request.summary().summaryId(), true, null, updated);
            });
            when(rpc.requestReceivePermit(any(), any())).thenAnswer(invocation -> {
                MemoryReceivePermitRequest request = invocation.getArgument(0);
                return new MemoryReceivePermitResponse("permit-a2a-compaction", request.events().stream()
                        .map(event -> new MemoryReceivePermitResponse.EventAcceptance(event.eventId(), true, null)).toList());
            });
            when(rpc.deliverMessages(any(), any())).thenAnswer(invocation -> {
                MemoryDeliveryRequest request = invocation.getArgument(0);
                delivered.set(request);
                long last = request.messages().get(request.messages().size() - 1).sequence();
                var updated = new MemoryConversationVersion(version.get().revision() + 1, last, version.get().policyVersion());
                version.set(updated);
                return new MemoryDeliveryResponse(request.permitId(), updated, request.messages().stream()
                        .map(message -> new MemoryDeliveryResponse.DeliveryResult(message.sequence(), true, null)).toList());
            });
        }
    }

    private A2aMemoryProperties enabledProperties() {
        var properties = new A2aMemoryProperties();
        properties.setEnabled(true);
        properties.setKeyId("test-domain-memory");
        properties.setPrivateKeyFile(PRIVATE_KEY_FILE);
        properties.validate();
        return properties;
    }

    private static String createPrivateKeyFile() {
        try {
            var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            var path = Files.createTempFile("a2a-memory-test-identity-", ".pem");
            Files.writeString(path, pem);
            path.toFile().deleteOnExit();
            return path.toString();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private DurableMemoryExportDirective exportDirective() {
        Instant now = Instant.now();
        return new DurableMemoryExportDirective("grant-a2a-1",
                new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-shared-1"), null,
                "listing-review", List.of(new MemoryShareGrant.Participant(
                        "supervisor", "supervisor-agent", "supervisor-conversation")),
                List.of(MemoryShareGrant.Projection.COLLABORATION, MemoryShareGrant.Projection.DURABLE_EXPORT),
                List.of("result", "status"), true, now.minusSeconds(60), now.plusSeconds(3600));
    }

    private A2aCredentialContext.Lease lease(String taskId, String actorId, String audience) {
        long expiry = Instant.now().getEpochSecond() + 60;
        var binding = new A2aBeaconBinding(1, "request-" + taskId, "agent-service", audience,
                actorId, "message/send", "0".repeat(64), taskId, expiry);
        return new A2aCredentialContext.Lease(binding,
                RagCredentialContext.beacon("opaque-a2a-test-token-" + taskId), expiry, () -> { });
    }

    private void register(A2aCredentialContext.Lease lease) {
        try (var ignored = A2aCredentialContext.open(lease)) { A2aCredentialContext.registerTask(lease.binding.taskId()); }
    }

    private void discard(A2aCredentialContext.Lease lease) {
        try (var ignored = A2aCredentialContext.open(lease)) { A2aCredentialContext.discardTask(lease.binding.taskId()); }
    }

    private OverAllState state(List<Message> messages) {
        return new OverAllState(Map.of("messages", new ArrayList<>(messages))).registerKeyAndStrategy("messages", new AppendStrategy());
    }

    private RunnableConfig config(String taskId, A2aMemoryInvocation invocation) {
        return RunnableConfig.builder().threadId(taskId).addMetadata(A2aMemoryAgentHook.INVOCATION_METADATA_KEY, invocation).build();
    }
}
