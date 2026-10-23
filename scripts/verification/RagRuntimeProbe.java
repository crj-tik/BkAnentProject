package com.bkanent.verification;

import com.bkanent.common.a2a.A2aBeaconAuthentication;
import com.bkanent.common.a2a.BeaconA2aHttpClient;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import io.a2a.client.A2AClient;
import io.a2a.http.JdkA2AHttpClient;
import io.a2a.spec.*;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Private opt-in verification process. No provider, HTTP endpoint, generic RPC, or physical index writes. */
final class RagRuntimeProbe implements AutoCloseable {
    private static final PrintStream SUMMARY = System.out;
    private static final Set<String> A2A_AUDIENCES = Set.of("listing-master-service", "business-service",
            "marketing-content-service", "compare-engine-service", "contract-service", "settlement-service",
            "notification-service", "media-worker-service", "interview-service");
    private static final Map<String, String> MEMORY_V2_DOMAIN_AGENTS = Map.of(
            "marketing", "marketing-agent",
            "interview", "interview-agent",
            "listing", "listing-agent",
            "trade", "trade-agent",
            "contract", "contract-agent",
            "compare", "compare-agent",
            "settlement", "settlement-agent");
    private static final Set<String> MEMORY_V2_SPACE_IDS = memoryV2SpaceIds();
    private final List<ReferenceConfig<?>> references = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private AuthPermissionRpcService permissions;
    private RagPrincipalRpcService principalRpc;
    private RagCredentialContext credentials;
    private String caller;
    private String keyId;
    private byte[] signingSecret;
    private byte[] responseSecret;
    private String responseKeyId;
    private Long actor;
    private int rpcTimeout;
    private String mode;
    private String operation;

    public static void main(String[] args) {
        // Third-party logging/error handlers must never print request arguments, provider errors or bodies.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> { });
        int exit = 1;
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "private-probe-deadline");
            thread.setDaemon(true);
            return thread;
        });
        try {
            require(args.length == 0 && "true".equals(env("RAG_PROBE_ENABLED")), RagErrorCode.FORBIDDEN);
            int seconds = integer("RAG_PROBE_DEADLINE_SECONDS", 240, 10, 300);
            watchdog.schedule(() -> {
                SUMMARY.println("probe result=DEADLINE_EXCEEDED remoteOutcome=UNCONFIRMED");
                Runtime.getRuntime().halt(124);
            }, seconds, TimeUnit.SECONDS);
            try (RagRuntimeProbe probe = new RagRuntimeProbe()) {
                probe.run();
            }
            exit = 0;
        } catch (Throwable failure) {
            SUMMARY.println("probe result=FAILED errorCode=" + safeFailure(failure));
            if ("true".equals(env("RAG_PROBE_DEBUG"))) failure.printStackTrace(SUMMARY);
        } finally {
            watchdog.shutdownNow();
        }
        System.exit(exit);
    }

    private void run() throws Exception {
        mode = required("RAG_PROBE_MODE").toUpperCase(Locale.ROOT);
        operation = required("RAG_PROBE_OPERATION").toUpperCase(Locale.ROOT);
        require(Set.of("DOMAIN", "A2A").contains(mode), RagErrorCode.INVALID_ARGUMENT);
        rpcTimeout = integer("RAG_PROBE_RPC_TIMEOUT_MILLIS", 15000, 1000, 60000);
        responseKeyId = required("AUTH_RAG_RPC_RESPONSE_KEY_ID");
        responseSecret = secret("AUTH_RAG_RPC_RESPONSE_SECRET");
        credentials = new RagCredentialContext(secretText("RAG_PROBE_ACCESS_TOKEN"));
        String space = null;
        if ("DOMAIN".equals(mode)) {
            require(Set.of("INSPECT", "PROVISION", "GET_STATUS", "REPLAY").contains(operation), RagErrorCode.INVALID_ARGUMENT);
            space = required("RAG_PROBE_SPACE_ID");
            caller = switch (space) {
                case "listing.knowledge" -> "listing-master-service";
                case "memory.preference", "memory.constraint" -> "memory-service";
                default -> {
                    if (MEMORY_V2_SPACE_IDS.contains(space)) yield "memory-service";
                    throw new RagException(RagErrorCode.FORBIDDEN);
                }
            };
            String prefix = "listing-master-service".equals(caller) ? "LISTING" : "MEMORY";
            keyId = required("RAG_PROBE_DOMAIN_KEY_ID");
            signingSecret = secret(prefix + "_RAG_RPC_SECRET");
            if (Set.of("PROVISION", "REPLAY").contains(operation)) mutationsAllowed();
        } else {
            require(Set.of("SEND", "STREAM", "GET", "RESUBSCRIBE", "CANCEL").contains(operation), RagErrorCode.INVALID_ARGUMENT);
            require(A2A_AUDIENCES.contains(required("RAG_PROBE_A2A_AUDIENCE")), RagErrorCode.FORBIDDEN);
            caller = "agent-service";
            // RAG principal RPC 属于独立 RAG 合约，使用 agent-service 的 RAG RPC 凭据。
            keyId = "agent-current";
            signingSecret = secret("AGENT_RAG_RPC_SECRET");
            if (Set.of("SEND", "STREAM", "CANCEL").contains(operation)) mutationsAllowed();
        }
        bootstrap();
        permissions = reference(AuthPermissionRpcService.class, required("RAG_PROBE_AUTH_DUBBO_URL"));
        principalRpc = reference(RagPrincipalRpcService.class, required("RAG_PROBE_AUTH_DUBBO_URL"));
        AuthenticatedPrincipal authenticated = permissions.resolvePrincipal(credentials.accessToken());
        require(authenticated != null && authenticated.userId() != null && authenticated.userId() > 0,
                RagErrorCode.AUTHENTICATION_FAILED);
        actor = authenticated.userId();
        AuthRagPrincipal principal = currentPrincipal();
        if ("DOMAIN".equals(mode)) {
            require("ADMIN".equals(principal.roleCode()), RagErrorCode.FORBIDDEN);
            domain(space);
        } else {
            a2a(principal);
        }
    }

    private void bootstrap() {
        String authUrl = directUrl(required("RAG_PROBE_AUTH_DUBBO_URL"));
        // Official Dubbo per-interface P2P override also configures the production private @DubboReference field.
        System.setProperty(A2aBeaconRpcService.class.getName(), authUrl);
        context = new AnnotationConfigApplicationContext();
        Map<String, Object> settings = new HashMap<>();
        settings.put("spring.application.name", caller);
        if ("A2A".equals(mode)) {
            settings.put("service.identity.key-id", "agent-service-current");
            settings.put("service.identity.private-key-file", "/run/secrets/service-identity.pem");
            settings.put("a2a.auth.response-key-id", required("AUTH_SERVICE_IDENTITY_KEY_ID"));
            settings.put("a2a.auth.response-public-key", required("AUTH_SERVICE_IDENTITY_PUBLIC_KEY"));
        }
        settings.put("dubbo.application.name", "private-rag-runtime-probe");
        settings.put("dubbo.registry.address", "N/A");
        settings.put("dubbo.consumer.check", false);
        settings.put("dubbo.consumer.retries", 0);
        settings.put("dubbo.consumer.timeout", rpcTimeout);
        settings.put("dubbo.consumer.cluster", "failfast");
        settings.put("dubbo.reference." + A2aBeaconRpcService.class.getName() + ".url", authUrl);
        settings.put("dubbo.application.qos-enable", false);
        settings.put("dubbo.application.register-consumer", false);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-probe-process", settings));
        context.register(ProbeConfiguration.class);
        context.refresh();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableDubbo(scanBasePackages = "com.bkanent.verification.no_providers")
    static class ProbeConfiguration {
        @Bean ApplicationConfig probeApplication() {
            ApplicationConfig config = new ApplicationConfig("private-rag-runtime-probe");
            config.setQosEnable(false);
            return config;
        }
        @Bean RegistryConfig probeRegistry() { return new RegistryConfig("N/A"); }
        @Bean ConsumerConfig probeConsumer(org.springframework.core.env.Environment environment) {
            ConsumerConfig config = new ConsumerConfig();
            config.setCheck(false);
            config.setRetries(0);
            config.setTimeout(environment.getRequiredProperty("dubbo.consumer.timeout", Integer.class));
            config.setCluster("failfast");
            return config;
        }
        @Bean A2aBeaconAuthentication probeBeaconAuthentication(org.springframework.core.env.Environment environment) {
            return new A2aBeaconAuthentication(environment);
        }
    }

    private <T> T reference(Class<T> type, String url) {
        return reference(type, url, "fastjson2");
    }

    private <T> T ragReference(Class<T> type, String url) {
        // RAG providers pin this SPI so record responses with list fields decode safely (KI-113).
        return reference(type, url, "fastjson2ctor");
    }

    private <T> T reference(Class<T> type, String url, String serialization) {
        ReferenceConfig<T> reference = new ReferenceConfig<>();
        reference.setInterface(type);
        reference.setApplication(context.getBean(ApplicationConfig.class));
        reference.setRegistry(context.getBean(RegistryConfig.class));
        reference.setUrl(directUrl(url, serialization));
        reference.setCheck(false);
        reference.setInjvm(false);
        reference.setRetries(0);
        reference.setCluster("failfast");
        reference.setTimeout(rpcTimeout);
        references.add(reference);
        return reference.get();
    }

    private AuthRagPrincipal currentPrincipal() {
        String requestId = UUID.randomUUID().toString();
        String digest = RagCanonicalHash.credentialDigest(credentials);
        RagAuthEnvelope request = envelope(RagOperation.RESOLVE_PRINCIPAL, requestId, digest, "auth-service", true);
        AuthRagPrincipal principal = principalRpc.resolveRagPrincipal(credentials, request);
        require(principal != null && principal.userId() != null && principal.attestation() != null,
                RagErrorCode.AUTHENTICATION_FAILED);
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), caller,
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, requestId, credentials,
                new RagSigningKey(responseKeyId, "auth-service", null,
                        Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), responseSecret), Instant.now());
        require(actor.equals(principal.userId()) && principal.accountActive() && principal.roleActive()
                && Set.of("ADMIN", "BROKER").contains(principal.roleCode())
                && principal.expiresAtEpochSecond() > Instant.now().getEpochSecond()
                && principal.attestation().actorMode() == RagActorMode.USER
                && actor.toString().equals(principal.attestation().actorId()), RagErrorCode.AUTHENTICATION_FAILED);
        return principal;
    }

    private RagAuthEnvelope envelope(RagOperation op, String requestId, String hash, String audience, boolean user) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, audience, caller, op, requestId, hash,
                user ? RagActorMode.USER : RagActorMode.SERVICE, user ? actor.toString() : caller,
                now, now + 60, user ? RagCanonicalHash.credentialDigest(credentials) : null, null), signingSecret);
    }

    private void domain(String space) {
        String requestId = UUID.randomUUID().toString();
        String url = required("RAG_PROBE_RAG_DUBBO_URL");
        if (Set.of("INSPECT", "PROVISION").contains(operation)) {
            RagSpaceAdminRpcService admin = ragReference(RagSpaceAdminRpcService.class, url);
            RagSpaceInspection result;
            if ("INSPECT".equals(operation)) {
                RagInspectRequest request = new RagInspectRequest(1, requestId, space);
                result = inspectTimed(admin, request, envelope(RagOperation.INSPECT, requestId,
                        RagCanonicalHash.requestHash(request), "rag-service", true), credentials, rpcTimeout);
            } else {
                RagProvisionRequest request = new RagProvisionRequest(1, requestId, space,
                        required("RAG_PROBE_SCHEMA_VERSION"), integer("RAG_PROBE_DIMENSION", -1, 1, 65536));
                result = admin.provision(request, envelope(RagOperation.PROVISION, requestId,
                        RagCanonicalHash.requestHash(request), "rag-service", true), credentials);
            }
            require("ADMIN".equals(currentPrincipal().roleCode()), RagErrorCode.FORBIDDEN);
            require(result != null && space.equals(result.spaceId()) && requestId.equals(result.requestId())
                    && caller.equals(result.ownerDomain()), RagErrorCode.INVALID_ARGUMENT);
            SUMMARY.printf("probe mode=DOMAIN operation=%s space=%s ready=%s dimension=%d failedStageCount=%d%n",
                    operation, space, result.ready(), result.dimension(), result.failedStages().size());
            for (RagStageFailure failure : result.failedStages()) {
                SUMMARY.printf("probe capabilityStage=%s errorCode=%s%n", failure.stage(), failure.code());
            }
            require(result.ready(), RagErrorCode.SPACE_NOT_READY);
            return;
        }
        String eventId = env("RAG_PROBE_EVENT_ID");
        String documentId = env("RAG_PROBE_DOCUMENT_ID");
        // The service-only status API cannot enforce a user's preference owner. This private probe narrows it.
        if ("memory.preference".equals(space)) {
            require(eventId == null && documentId != null && documentId.startsWith("preference:"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(actor.toString().getBytes(StandardCharsets.UTF_8))
                    + ":"), RagErrorCode.FORBIDDEN);
        }
        RagIndexStatus result;
        if ("GET_STATUS".equals(operation)) {
            RagStatusRequest request = new RagStatusRequest(1, requestId, space, eventId, documentId);
            result = ragReference(RagIndexRpcService.class, url).getStatus(request,
                    envelope(RagOperation.GET_STATUS, requestId, RagCanonicalHash.requestHash(request), "rag-service", false));
        } else {
            RagReplayRequest request = new RagReplayRequest(1, requestId, space, eventId, documentId);
            result = ragReference(RagSpaceAdminRpcService.class, url).replay(request,
                    envelope(RagOperation.REPLAY, requestId, RagCanonicalHash.requestHash(request), "rag-service", true), credentials);
        }
        require("ADMIN".equals(currentPrincipal().roleCode()), RagErrorCode.FORBIDDEN);
        require(result != null && space.equals(result.spaceId()) && requestId.equals(result.requestId())
                && (documentId == null || documentId.equals(result.documentId()))
                && (eventId == null || eventId.equals(result.eventId())), RagErrorCode.INVALID_ARGUMENT);
        SUMMARY.printf("probe mode=DOMAIN operation=%s space=%s documentHash=%s state=%s revision=%d keywordRevision=%d vectorRevision=%d errorCode=%s%n",
                operation, space, hash(result.documentId()), result.state(), result.revision(),
                result.keyword() == null ? -1 : result.keyword().appliedRevision(),
                result.vector() == null ? -1 : result.vector().appliedRevision(), result.errorCode());
    }

    private static Set<String> memoryV2SpaceIds() {
        Set<String> spaces = new HashSet<>();
        MEMORY_V2_DOMAIN_AGENTS.forEach((domain, agent) -> {
            for (MemoryRagRecordType type : MemoryRagRecordType.values()) {
                spaces.add(MemoryRagSpaceIds.spaceId(type, domain, agent));
            }
        });
        return Set.copyOf(spaces);
    }

    private static RagSpaceInspection inspectTimed(RagSpaceAdminRpcService admin, RagInspectRequest request,
            RagAuthEnvelope envelope, RagCredentialContext credentials, int timeoutMillis) {
        long started = System.nanoTime();
        try {
            RagSpaceInspection result = admin.inspect(request, envelope, credentials);
            inspectTiming(SUMMARY, started, timeoutMillis, null);
            return result;
        } catch (RuntimeException failure) {
            inspectTiming(SUMMARY, started, timeoutMillis, failure);
            throw failure;
        }
    }

    private static void inspectTiming(PrintStream output, long started, int timeoutMillis, Throwable failure) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
        output.printf("probe rpcInterface=RagSpaceAdminRpcService method=inspect protocol=dubbo "
                        + "timeoutConfiguredMillis=%d elapsedMillis=%d outcome=%s errorCode=%s remoteOutcome=%s%n",
                timeoutMillis, elapsedMillis, failure == null ? "RETURNED" : "FAILED",
                failure == null ? "NONE" : safeFailure(failure), failure == null ? "RETURNED" : "UNCONFIRMED");
    }

    private void a2a(AuthRagPrincipal initial) throws Exception {
        String endpoint = httpUrl(required("RAG_PROBE_A2A_URL"));
        String audience = required("RAG_PROBE_A2A_AUDIENCE");
        String taskId = required("RAG_PROBE_TASK_ID");
        String contextId = required("RAG_PROBE_CONTEXT_ID");
        long leaseExpires = Math.min(initial.expiresAtEpochSecond(), Instant.now().getEpochSecond() + 300);
        A2aBeaconAuthentication auth = context.getBean(A2aBeaconAuthentication.class);
        BeaconA2aHttpClient transport = new BeaconA2aHttpClient(new JdkA2AHttpClient(HttpClient.Redirect.NEVER), auth, () -> {
            currentPrincipal();
            require(leaseExpires > Instant.now().getEpochSecond(), RagErrorCode.AUTHENTICATION_EXPIRED);
            return new BeaconA2aHttpClient.Identity(actor.toString(), audience, credentials, leaseExpires);
        });
        // One fixed original endpoint/client/task association, never rediscover or replace accepted work.
        A2AClient client = new A2AClient(transport, endpoint);
        String requestId = UUID.randomUUID().toString();
        if (Set.of("STREAM", "RESUBSCRIBE").contains(operation)) {
            CompletableFuture<Void> finished = new CompletableFuture<>();
            AtomicInteger events = new AtomicInteger();
            AtomicInteger artifacts = new AtomicInteger();
            Consumer<StreamingEventKind> onEvent = event -> {
                if (finished.isDone()) return;
                try {
                    currentPrincipal();
                    events.incrementAndGet();
                    if (event instanceof TaskArtifactUpdateEvent artifact) {
                        association(taskId, contextId, artifact.getTaskId(), artifact.getContextId());
                        artifacts.incrementAndGet();
                    } else if (event instanceof TaskStatusUpdateEvent status) {
                        association(taskId, contextId, status.getTaskId(), status.getContextId());
                        if (status.isFinal()) {
                            summarizeStatus(taskId, status.getStatus().state(), events.get(), artifacts.get());
                            rejectFailedTask(status.getStatus());
                            finished.complete(null);
                        }
                    } else if (event instanceof Task task) {
                        association(taskId, contextId, task.getId(), task.getContextId());
                        if (task.getStatus().state().isFinal()) {
                            summarizeStatus(taskId, task.getStatus().state(), events.get(), artifacts.get());
                            rejectFailedTask(task.getStatus());
                            finished.complete(null);
                        }
                    } else if (event instanceof Message message) {
                        association(taskId, contextId, message.getTaskId(), message.getContextId());
                        summarizeStatus(taskId, TaskState.COMPLETED, events.get(), artifacts.get());
                        finished.complete(null);
                    }
                } catch (Throwable failure) { finished.completeExceptionally(failure); }
            };
            Consumer<JSONRPCError> onError = error -> finished.completeExceptionally(protocolError(error));
            Runnable onFailure = () -> finished.completeExceptionally(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));
            if ("STREAM".equals(operation)) client.sendStreamingMessage(requestId, params(taskId, contextId, true), onEvent, onError, onFailure);
            else client.resubscribeToTask(requestId, new TaskIdParams(taskId), onEvent, onError, onFailure);
            finished.get(integer("RAG_PROBE_DEADLINE_SECONDS", 240, 10, 300), TimeUnit.SECONDS);
            currentPrincipal();
            return;
        }
        Task task;
        if ("SEND".equals(operation)) {
            SendMessageResponse response = client.sendMessage(requestId, params(taskId, contextId, false));
            if (response.getError() != null) throw protocolError(response.getError());
            if (response.getResult() instanceof Message message) {
                currentPrincipal();
                association(taskId, contextId, message.getTaskId(), message.getContextId());
                summarizeStatus(taskId, TaskState.COMPLETED, 1, 0);
                return;
            }
            require(response.getResult() instanceof Task, RagErrorCode.INVALID_ARGUMENT);
            task = (Task) response.getResult();
        } else if ("GET".equals(operation)) {
            GetTaskResponse response = client.getTask(requestId, new TaskQueryParams(taskId));
            if (response.getError() != null) throw protocolError(response.getError());
            task = response.getResult();
        } else {
            CancelTaskResponse response = client.cancelTask(requestId, new TaskIdParams(taskId));
            if (response.getError() != null) throw protocolError(response.getError());
            task = response.getResult();
        }
        currentPrincipal();
        require(task != null && task.getStatus() != null, RagErrorCode.INVALID_ARGUMENT);
        association(taskId, contextId, task.getId(), task.getContextId());
        summarizeStatus(taskId, task.getStatus().state(), 1, task.getArtifacts() == null ? 0 : task.getArtifacts().size());
        rejectFailedTask(task.getStatus());
    }

    private MessageSendParams params(String taskId, String contextId, boolean streaming) {
        String prompt = required("RAG_PROBE_PROMPT");
        require(prompt.length() <= 8192, RagErrorCode.INVALID_ARGUMENT);
        Message message = new Message.Builder().role(Message.Role.USER).messageId(UUID.randomUUID().toString())
                .taskId(taskId).contextId(contextId).parts(List.of(new TextPart(prompt))).build();
        boolean blocking = "true".equals(env("RAG_PROBE_BLOCKING"));
        return new MessageSendParams(message, new MessageSendConfiguration(List.of("text", "application/json"),
                null, null, blocking), streaming ? Map.of("isStreaming", true) : Map.of());
    }

    private void summarizeStatus(String taskId, TaskState state, int events, int artifacts) {
        SUMMARY.printf("probe mode=A2A operation=%s taskHash=%s state=%s eventCount=%d artifactCount=%d%n",
                operation, hash(taskId), state, events, artifacts);
    }
    private static void association(String expectedTask, String expectedContext, String task, String context) {
        require(expectedTask.equals(task) && expectedContext.equals(context), RagErrorCode.FORBIDDEN);
    }
    private static void rejectFailedTask(TaskStatus status) {
        if (Set.of(TaskState.FAILED, TaskState.REJECTED, TaskState.AUTH_REQUIRED).contains(status.state())) {
            Object code = status.message() == null || status.message().getMetadata() == null
                    ? null : status.message().getMetadata().get("errorCode");
            throw errorCode(code);
        }
    }
    private static RagException protocolError(JSONRPCError error) {
        Object code = error != null && error.getData() instanceof Map<?, ?> data ? data.get("errorCode") : null;
        return errorCode(code);
    }
    private static RagException errorCode(Object code) {
        if (code instanceof String text) {
            try { return new RagException(RagErrorCode.valueOf(text)); }
            catch (IllegalArgumentException ignored) { }
        }
        return new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
    }
    private static String safeFailure(Throwable failure) {
        for (int i = 0; failure != null && i < 8; i++, failure = failure.getCause()) {
            if (failure instanceof RagException rag) return rag.code().name();
            if (failure instanceof TimeoutException) return "DEADLINE_EXCEEDED";
        }
        return "PROBE_OR_REMOTE_FAILURE";
    }
    private static String hash(String value) { return value == null ? "NONE" : BeaconA2aHttpClient.bodyHash(value); }
    private static void mutationsAllowed() { require("true".equals(env("RAG_PROBE_ALLOW_MUTATIONS")), RagErrorCode.FORBIDDEN); }
    private static void require(boolean condition, RagErrorCode code) { if (!condition) throw new RagException(code); }
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
    private static String required(String name) {
        String value = env(name);
        require(value != null, RagErrorCode.INVALID_ARGUMENT);
        return value;
    }
    private static String secretText(String name) {
        String value = env(name);
        if (value != null) return value;
        java.io.Console console = System.console();
        require(console != null, RagErrorCode.AUTHENTICATION_FAILED);
        char[] input = console.readPassword("%s: ", name);
        require(input != null && input.length > 0, RagErrorCode.AUTHENTICATION_FAILED);
        try { return new String(input); }
        finally { Arrays.fill(input, '\0'); }
    }
    private static byte[] secret(String name) {
        byte[] value = secretText(name).getBytes(StandardCharsets.UTF_8);
        require(value.length >= 32, RagErrorCode.AUTHENTICATION_FAILED);
        return value;
    }
    private static int integer(String name, int fallback, int minimum, int maximum) {
        String value = env(name);
        int result;
        try { result = value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new RagException(RagErrorCode.INVALID_ARGUMENT); }
        require(result >= minimum && result <= maximum, RagErrorCode.INVALID_ARGUMENT);
        return result;
    }
    private static String directUrl(String value) {
        return directUrl(value, "fastjson2");
    }
    private static String directUrl(String value, String serialization) {
        // Registry-free references lack provider serialization metadata, so pin each interface contract explicitly.
        require(Set.of("fastjson2", "fastjson2ctor").contains(serialization), RagErrorCode.INVALID_ARGUMENT);
        return endpoint(value, Set.of("dubbo"), true) + "?serialization=" + serialization;
    }
    private static String httpUrl(String value) {
        return endpoint(value, Set.of("http", "https"), false);
    }
    private static String endpoint(String value, Set<String> schemes, boolean portRequired) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException failure) { throw new RagException(RagErrorCode.INVALID_ARGUMENT); }
        require(schemes.contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (!portRequired || uri.getPort() > 0), RagErrorCode.INVALID_ARGUMENT);
        return value;
    }
    @Override public void close() {
        for (ReferenceConfig<?> reference : references) {
            try { reference.destroy(); } catch (RuntimeException ignored) { }
        }
        if (context != null) {
            try { context.close(); } catch (RuntimeException ignored) { }
        }
        System.clearProperty(A2aBeaconRpcService.class.getName());
        if (signingSecret != null) Arrays.fill(signingSecret, (byte) 0);
        if (responseSecret != null) Arrays.fill(responseSecret, (byte) 0);
        credentials = null;
    }
}
