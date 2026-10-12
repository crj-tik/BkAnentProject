package com.bkanent.rag.security.http;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport extension only: Spring AI retains its existing serialization and embedding protocol. */
final class RagProtectedRequestFactory extends HttpComponentsClientHttpRequestFactory {
    private final RagEndpointPolicy policy;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Thread, HttpUriRequestBase> constructing = new ConcurrentHashMap<>();

    RagProtectedRequestFactory(HttpClient client, RagEndpointPolicy policy, ScheduledExecutorService scheduler) {
        super(client);
        this.policy = policy;
        this.scheduler = scheduler;
    }

    @Override
    protected ClassicHttpRequest createHttpUriRequest(HttpMethod method, URI uri) {
        HttpUriRequestBase request = new HttpUriRequestBase(method.name(), uri);
        constructing.put(Thread.currentThread(), request);
        return request;
    }

    @Override
    protected HttpContext createHttpContext(HttpMethod method, URI uri) {
        HttpClientContext context = HttpClientContext.create();
        long deadline = RagHttpDeadline.capture(Duration.ofSeconds(8));
        long remaining = RagHttpDeadline.remainingNanos(deadline);
        context.setAttribute(RagConnectionOperator.DEADLINE, deadline);
        context.setRequestConfig(RequestConfig.custom()
                .setRedirectsEnabled(false).setHardCancellationEnabled(true)
                .setConnectTimeout(Timeout.ofNanoseconds(Math.min(remaining, TimeUnit.SECONDS.toNanos(5))))
                .setConnectionRequestTimeout(Timeout.ofNanoseconds(Math.min(remaining, TimeUnit.SECONDS.toNanos(5))))
                .setResponseTimeout(Timeout.ofNanoseconds(Math.min(remaining, TimeUnit.SECONDS.toNanos(8))))
                .build());
        return context;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        policy.validateUrl(uri);
        long deadline = RagHttpDeadline.capture(Duration.ofSeconds(8));
        ClientHttpRequest delegate;
        HttpUriRequestBase cancellable;
        try (RagHttpDeadline ignored = RagHttpDeadline.until(deadline)) {
            delegate = super.createRequest(uri, method);
            cancellable = constructing.get(Thread.currentThread());
        } finally {
            constructing.remove(Thread.currentThread());
        }
        if (cancellable == null) {
            throw new IllegalStateException("RAG_HTTP_CANCELLATION_UNAVAILABLE");
        }
        return new ClientHttpRequest() {
            public HttpMethod getMethod() { return delegate.getMethod(); }
            public URI getURI() { return delegate.getURI(); }
            public HttpHeaders getHeaders() { return delegate.getHeaders(); }
            public OutputStream getBody() throws IOException { return delegate.getBody(); }
            public java.util.Map<String, Object> getAttributes() { return delegate.getAttributes(); }

            public ClientHttpResponse execute() throws IOException {
                AtomicBoolean expired = new AtomicBoolean();
                ScheduledFuture<?> timer = scheduler.schedule(() -> {
                    expired.set(true);
                    cancellable.cancel();
                }, RagHttpDeadline.remainingNanos(deadline), TimeUnit.NANOSECONDS);
                try (RagHttpDeadline ignored = RagHttpDeadline.until(deadline)) {
                    return guard(delegate.execute(), timer, expired, deadline);
                } catch (IOException | RuntimeException ex) {
                    timer.cancel(false);
                    if (expired.get() || System.nanoTime() >= deadline) {
                        throw RagHttpDeadline.timeout();
                    }
                    throw ex;
                }
            }
        };
    }

    private ClientHttpResponse guard(ClientHttpResponse response, ScheduledFuture<?> timer,
                                     AtomicBoolean expired, long deadline) {
        return new ClientHttpResponse() {
            private final AtomicBoolean closed = new AtomicBoolean();
            private void finish() {
                if (closed.compareAndSet(false, true)) {
                    closeResponse(response, timer);
                }
            }
            private void check() {
                if (expired.get() || System.nanoTime() >= deadline) {
                    throw RagHttpDeadline.timeout();
                }
            }
            public HttpStatusCode getStatusCode() throws IOException { check(); return response.getStatusCode(); }
            public String getStatusText() throws IOException { check(); return response.getStatusText(); }
            public HttpHeaders getHeaders() { check(); return response.getHeaders(); }
            public InputStream getBody() throws IOException {
                check();
                return new FilterInputStream(response.getBody()) {
                    public int read() throws IOException {
                        check();
                        try { int value = super.read(); check(); return value; }
                        catch (IOException ex) { check(); throw ex; }
                    }
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        check();
                        try { int value = super.read(bytes, offset, length); check(); return value; }
                        catch (IOException ex) { check(); throw ex; }
                    }
                    public void close() throws IOException { finish(); }
                };
            }
            public void close() { finish(); }
        };
    }

    private void closeResponse(ClientHttpResponse response, ScheduledFuture<?> timer) {
        try {
            response.close(); // keep hard-cancellation active while HC consumes/releases the entity
        } finally {
            timer.cancel(false);
        }
    }
}
