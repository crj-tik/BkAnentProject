package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagProtectedRequestFactoryTest {
    @Test
    void deadlineCancelsActualRequestAndDoesNotRestartAtExecution() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        AtomicReference<HttpUriRequestBase> captured = new AtomicReference<>();
        when(transport.executeOpen(nullable(HttpHost.class), any(ClassicHttpRequest.class), any(HttpContext.class)))
                .thenAnswer(call -> {
                    HttpUriRequestBase request = call.getArgument(1);
                    captured.set(request);
                    long stop = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    while (!request.isCancelled() && System.nanoTime() < stop) {
                        Thread.sleep(2);
                    }
                    throw new java.io.IOException("mock cancellation");
                });
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var factory = new RagProtectedRequestFactory(transport, new RagEndpointPolicy(), scheduler);
            org.springframework.http.client.ClientHttpRequest request;
            try (var scope = RagHttpDeadline.open(Duration.ofMillis(40))) {
                request = factory.createRequest(URI.create("https://public.example/path"), HttpMethod.GET);
            }
            RagException ex = assertThrows(RagException.class, request::execute);
            assertEquals(RagErrorCode.TIMEOUT, ex.code());
            assertTrue(captured.get().isCancelled());
        } finally { scheduler.shutdownNow(); }
    }

    @Test
    void redirectsAreRejectedWithoutASecondMockTransportCall() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        BasicClassicHttpResponse redirect = new BasicClassicHttpResponse(302);
        redirect.addHeader("Location", "http://127.0.0.1/secret");
        when(transport.executeOpen(nullable(HttpHost.class), any(ClassicHttpRequest.class), any(HttpContext.class)))
                .thenReturn(redirect);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RestClient client = RestClient.builder()
                    .requestFactory(new RagProtectedRequestFactory(transport, new RagEndpointPolicy(), scheduler))
                    .defaultStatusHandler(status -> status.is3xxRedirection(), (request, response) -> {
                        throw RagEndpointPolicy.denied();
                    }).build();
            assertThrows(RagException.class, () -> client.get().uri("https://public.example").retrieve().body(String.class));
            verify(transport, times(1)).executeOpen(any(), any(), any());
        } finally { scheduler.shutdownNow(); }
    }

    @Test
    void scopeAppliesThroughBodyReadAndResponseCloseReleasesTimer() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new ByteArrayEntity(new byte[]{1, 2}, ContentType.APPLICATION_OCTET_STREAM));
        when(transport.executeOpen(nullable(HttpHost.class), any(ClassicHttpRequest.class), any(HttpContext.class)))
                .thenReturn(response);
        var scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            var factory = new RagProtectedRequestFactory(transport, new RagEndpointPolicy(), scheduler);
            // Leave enough setup budget for the real Spring/HC request factory to initialize on a
            // cold test JVM; the assertion still waits past this absolute deadline below.
            try (var scope = RagHttpDeadline.open(Duration.ofSeconds(2))) {
                var guarded = factory.createRequest(URI.create("https://public.example"), HttpMethod.GET).execute();
                Thread.sleep(2100);
                assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class, () -> guarded.getBody().read()).code());
                guarded.close();
            }
            assertEquals(0, scheduler.getQueue().size());
        } finally { scheduler.shutdownNow(); }
    }

    @Test
    void openAiStarterActuallyConsumesProtectedPrimaryBuilderAndCanCloseWithoutNetwork() {
        // Only a Mock HttpClient is used: this test executes the REAL OpenAI model/HTTP protocol.
        AtomicInteger httpCalls = new AtomicInteger();
        HttpClient transport = mock(HttpClient.class);
        try {
            when(transport.executeOpen(nullable(HttpHost.class), any(ClassicHttpRequest.class), any(HttpContext.class)))
                    .thenAnswer(call -> {
                        httpCalls.incrementAndGet();
                        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
                        response.addHeader("Content-Type", "application/json");
                        response.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(
                                "{\"object\":\"list\",\"model\":\"fixture\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}",
                                ContentType.APPLICATION_JSON));
                        return response;
                    });
        } catch (Exception ex) { throw new AssertionError(ex); }
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            new ApplicationContextRunner().withUserConfiguration(ProtectedBuilderFixture.class)
                    .withBean("fixtureTransport", HttpClient.class, () -> transport)
                    .withBean("fixtureScheduler", java.util.concurrent.ScheduledExecutorService.class, () -> scheduler)
                    .withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class,
                            org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration.class))
                    .withPropertyValues("spring.ai.openai.api-key=fixture-not-a-secret", "spring.ai.openai.base-url=https://public.example")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var model = context.getBean(org.springframework.ai.openai.OpenAiEmbeddingModel.class);
                        assertEquals(2, model.embed("fixture").length);
                        assertEquals(1, httpCalls.get());
                    });
        } finally { scheduler.shutdownNow(); }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ProtectedBuilderFixture {
        @org.springframework.context.annotation.Bean("ragProtectedRestClientBuilder")
        @org.springframework.context.annotation.Primary
        RestClient.Builder builder(HttpClient client, java.util.concurrent.ScheduledExecutorService scheduler) {
            return RestClient.builder().requestFactory(new RagProtectedRequestFactory(client, new RagEndpointPolicy(), scheduler));
        }
    }
}
