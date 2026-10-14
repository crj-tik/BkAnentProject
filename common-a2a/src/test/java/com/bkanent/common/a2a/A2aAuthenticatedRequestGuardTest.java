package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import io.a2a.util.Utils;
import io.a2a.spec.*;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.PushNotificationSender;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler;
import io.a2a.server.requesthandlers.JSONRPCHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class A2aAuthenticatedRequestGuardTest {
    private final A2aBeaconAuthentication auth = mock(A2aBeaconAuthentication.class);
    private final A2aAuthenticatedRequestGuard guard = new A2aAuthenticatedRequestGuard(auth);
    private final AtomicBoolean called = new AtomicBoolean();
    private final String taskId = "guard-test-" + java.util.UUID.randomUUID();
    A2aAuthenticatedRequestGuardTest() {
        when(auth.service()).thenReturn("listing-master-service");
        when(auth.resolve(any(), any())).thenReturn(new AuthRagPrincipal(7L, "BROKER", true, true, Instant.now().getEpochSecond() + 300));
    }
    private String body(String method) {
        String params = method.startsWith("message/") ? "{\"message\":{\"taskId\":\"" + taskId
                + "\",\"contextId\":\"context\"}}" : "{\"id\":\"" + taskId + "\"}";
        return "{\"jsonrpc\":\"2.0\",\"id\":\"rpc\",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }
    private ServerRequest.Headers headers(String body, String actor) throws Exception {
        var node = Utils.OBJECT_MAPPER.readTree(body);
        var binding = new A2aBeaconBinding(1, "rpc", "agent-service", "listing-master-service", actor,
                node.path("method").asText(), BeaconA2aHttpClient.bodyHash(body), taskId);
        var headers = mock(ServerRequest.Headers.class);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.OBJECT_MAPPER.writeValueAsBytes(binding));
        when(headers.firstHeader(BeaconA2aHttpClient.BEACON_HEADER)).thenReturn("opaque");
        when(headers.firstHeader(BeaconA2aHttpClient.BINDING_HEADER)).thenReturn(encoded);
        when(headers.header(BeaconA2aHttpClient.BEACON_HEADER)).thenReturn(List.of("opaque"));
        when(headers.header(BeaconA2aHttpClient.BINDING_HEADER)).thenReturn(List.of(encoded));
        return headers;
    }
    @Test void sendGetCancelResubscribeUseOriginalOwnerAndGuardBeforeOfficialDispatch() throws Exception {
        String send = body("message/send");
        assertThat(guard.handle(send, headers(send, "7"), () -> { called.set(true); return A2aCredentialContext.currentActorId(); })).isEqualTo("7");
        assertThat(called.get()).isTrue(); assertThat(A2aCredentialContext.snapshot()).isNull();
        for (String method : List.of("tasks/get", "tasks/cancel", "tasks/resubscribe")) {
            String request = body(method);
            assertThat(guard.handle(request, headers(request, "7"), () -> "ok")).isEqualTo("ok");
            called.set(false);
            assertThatThrownBy(() -> guard.handle(request, headers(request, "8"), () -> { called.set(true); return "bad"; })).isInstanceOf(RagException.class);
            assertThat(called.get()).isFalse();
        }
    }
    @Test void missingOwnershipCredentialsOrChangedExactBodyCannotReachTaskStore() throws Exception {
        String request = body("tasks/get");
        assertThatThrownBy(() -> guard.handle(request, headers(request, "7"), () -> { called.set(true); return null; })).isInstanceOf(RagException.class);
        String send = body("message/send"); var headers = headers(send, "7");
        assertThatThrownBy(() -> guard.handle(send + " ", headers, () -> { called.set(true); return null; })).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> guard.handle(send, mock(ServerRequest.Headers.class), () -> { called.set(true); return null; })).isInstanceOf(RagException.class);
        assertThat(called.get()).isFalse();
    }
    private String sdkBody(String method, PushNotificationConfig push) throws Exception {
        var message = new Message.Builder().role(Message.Role.USER).messageId("m")
                .taskId(taskId).contextId("context").parts(List.of(new TextPart("query"))).build();
        var params = new MessageSendParams(message,
                new MessageSendConfiguration(List.of("text"), null, push, false), new HashMap<>());
        return Utils.OBJECT_MAPPER.writeValueAsString("message/stream".equals(method)
                ? new SendStreamingMessageRequest("rpc", params) : new SendMessageRequest("rpc", params));
    }
    private AgentCard streamingCard() {
        return new AgentCard.Builder().name("listing-agent").description("listing test fixture")
                .url("https://listing.example/a2a").version("1").protocolVersion("0.2.5")
                .capabilities(new AgentCapabilities(true, false, true, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text"))
                .skills(List.of()).build();
    }
    @Test void actualSdkPushNotificationFieldIsRejectedBeforeOfficialStoreQueueOrSender() throws Exception {
        var tasks = spy(new InMemoryTaskStore());
        var queues = spy(new InMemoryQueueManager());
        var pushConfigs = spy(new InMemoryPushNotificationConfigStore());
        var sender = mock(PushNotificationSender.class);
        var executor = mock(AgentExecutor.class);
        var handler = new DefaultRequestHandler(executor, tasks, queues, pushConfigs, sender, Runnable::run);
        var official = spy(new JsonRpcA2aRequestHandler(new JSONRPCHandler(streamingCard(), handler)));
        for (String method : List.of("message/send", "message/stream")) {
            String request = sdkBody(method, new PushNotificationConfig("https://push.example/callback", null, null, "push"));
            assertThat(Utils.OBJECT_MAPPER.readTree(request).path("params").path("configuration")
                    .path("pushNotification").path("url").asText()).isEqualTo("https://push.example/callback");
            var requestHeaders = headers(request, "7");
            assertThatThrownBy(() -> guard.handle(request, requestHeaders, () -> official.onHandler(request, requestHeaders)))
                    .isInstanceOfSatisfying(RagException.class, failure -> assertThat(failure.code()).isEqualTo(RagErrorCode.FORBIDDEN));
        }
        verifyNoInteractions(official, tasks, queues, pushConfigs, sender, executor);
    }
    @Test void sameOwnerConcurrentSendIsDeniedBeforeRealOfficialHandlerTouchesStoreOrQueue() throws Exception {
        String request = sdkBody("message/stream", null);
        var requestHeaders = headers(request, "7");
        var tasks = spy(new InMemoryTaskStore());
        var queues = spy(new InMemoryQueueManager());
        var pushConfigs = spy(new InMemoryPushNotificationConfigStore());
        var sender = mock(PushNotificationSender.class);
        var executor = mock(AgentExecutor.class);
        var provider = new A2aContextExecutorProvider();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var consumed = new CountDownLatch(1);
        var streamFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> subscription = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("producer did not release");
            new io.a2a.server.tasks.TaskUpdater(invocation.getArgument(0), invocation.getArgument(1)).complete();
            return null;
        }).when(executor).execute(any(), any());
        var handler = new DefaultRequestHandler(executor, tasks, queues, pushConfigs, sender, provider.getA2aServerExecutor());
        var official = spy(new JsonRpcA2aRequestHandler(new JSONRPCHandler(streamingCard(), handler)));
        try {
            Flux<?> result = (Flux<?>) guard.handle(request, requestHeaders, () -> official.onHandler(request, requestHeaders));
            subscription.set(result.subscribe(value -> { }, failure -> {
                streamFailure.set(failure);
                consumed.countDown();
            }, consumed::countDown));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            clearInvocations(official, tasks, queues, pushConfigs, sender, executor);
            for (String method : List.of("message/send", "message/stream")) {
                String duplicate = sdkBody(method, null);
                var duplicateHeaders = headers(duplicate, "7");
                assertThatThrownBy(() -> guard.handle(duplicate, duplicateHeaders,
                        () -> official.onHandler(duplicate, duplicateHeaders)))
                        .isInstanceOfSatisfying(RagException.class, failure -> assertThat(failure.code()).isEqualTo(RagErrorCode.FORBIDDEN));
            }
            verifyNoInteractions(official, tasks, queues, pushConfigs, sender, executor);
        } finally {
            release.countDown();
            try {
                assertThat(consumed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(streamFailure.get()).isNull();
                provider.getA2aServerExecutor().shutdown();
                assertThat(provider.getA2aServerExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> A2aCredentialContext.openTask(taskId)).isInstanceOf(RagException.class);
            } finally {
                provider.close();
                if (subscription.get() != null) subscription.get().dispose();
            }
        }
    }
    @Test void failedForwardReleasesReservationButNeverTransfersTaskOwnership() throws Exception {
        String request = body("message/send");
        var requestHeaders = headers(request, "7");
        assertThatThrownBy(() -> guard.handle(request, requestHeaders, () -> { throw new IllegalStateException("forward failed"); }))
                .isInstanceOf(RagException.class);
        assertThat(guard.handle(request, requestHeaders, () -> "retry")).isEqualTo("retry");
        called.set(false);
        var foreignHeaders = headers(request, "8");
        assertThatThrownBy(() -> guard.handle(request, foreignHeaders, () -> { called.set(true); return "bad"; }))
                .isInstanceOf(RagException.class);
        assertThat(called.get()).isFalse();
    }
    @Test void rejectedSdkDispatchReleasesOnlyItsOwnReservation() throws Exception {
        String request = body("message/send");
        var requestHeaders = headers(request, "7");
        var provider = new A2aContextExecutorProvider();
        provider.close();
        assertThatThrownBy(() -> guard.handle(request, requestHeaders, () -> {
            provider.getA2aServerExecutor().execute(() -> { throw new AssertionError("rejected command ran"); });
            return "unreachable";
        })).isInstanceOf(RagException.class);
        assertThat(guard.handle(request, requestHeaders, () -> "retry after rejected dispatch")).isEqualTo("retry after rejected dispatch");
    }
    @Test void reservationSurvivesUntilBothForwardAndCapturedCommandFinish() throws Exception {
        String request = body("message/send");
        var requestHeaders = headers(request, "7");
        var provider = new A2aContextExecutorProvider();
        try {
            guard.handle(request, requestHeaders, () -> {
                try {
                    Future<?> command = provider.getA2aServerExecutor().submit(() -> {
                        A2aCredentialContext.registerTask(taskId);
                        A2aCredentialContext.discardTask(taskId);
                    });
                    command.get(5, TimeUnit.SECONDS);
                    provider.getA2aServerExecutor().shutdown();
                    assertThat(provider.getA2aServerExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> guard.handle(request, requestHeaders, () -> "too early"))
                            .isInstanceOf(RagException.class);
                    return "finished";
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            assertThat(guard.handle(request, requestHeaders, () -> "reauthenticated retry")).isEqualTo("reauthenticated retry");
        } finally { provider.close(); }
    }
    @Test void streamingRechecksAuthorityBeforeAnyOutgoingItem() throws Exception {
        String send = body("message/stream");
        var result = (Flux<?>) guard.handle(send, headers(send, "7"), () -> Flux.just("private-content"));
        when(auth.resolve(any(), any())).thenThrow(new RagException(RagErrorCode.AUTHENTICATION_FAILED));
        assertThatThrownBy(result::blockLast).isInstanceOf(RagException.class);
    }
}
