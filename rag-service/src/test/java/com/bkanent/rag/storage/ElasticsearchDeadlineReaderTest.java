package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ElasticsearchDeadlineReaderTest {
    @Test void actualCancellableIsCancelledAtDeadlineAndLateBodyCompletionIsIgnored() {
        RestClient client = mock(RestClient.class);
        Cancellable operation = mock(Cancellable.class);
        ArgumentCaptor<ResponseListener> listener = ArgumentCaptor.forClass(ResponseListener.class);
        when(client.performRequestAsync(any(Request.class), listener.capture())).thenReturn(operation);
        var reader = new ElasticsearchDeadlineReader(client);
        Request request = new Request("POST", "/managed/_search");
        try {
            long start = System.nanoTime();
            assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class,
                    () -> reader.read(request, Duration.ofMillis(50), 65536)).code());
            assertTrue((System.nanoTime() - start) / 1000000 < 500);
            verify(operation, atLeastOnce()).cancel();
            assertNotNull(request.getOptions().getHttpAsyncResponseConsumerFactory());
            // Completion means body consumption finished; timeout cancelled the underlying request while it waited.
            assertDoesNotThrow(() -> listener.getValue().onSuccess(mock(Response.class)));
            assertDoesNotThrow(() -> listener.getValue().onFailure(new RuntimeException("late")));
        } finally { reader.close(); }
    }
    @Test void successReturnsOnlyCompletedBoundedResponse() {
        RestClient client = mock(RestClient.class);
        Cancellable operation = mock(Cancellable.class);
        Response response = mock(Response.class);
        when(client.performRequestAsync(any(Request.class), any(ResponseListener.class))).thenAnswer(invocation -> {
            invocation.<ResponseListener>getArgument(1).onSuccess(response);
            return operation;
        });
        var reader = new ElasticsearchDeadlineReader(client);
        try {
            assertSame(response, reader.read(new Request("POST", "/managed/_search"), Duration.ofSeconds(1), 65536));
            verify(operation, never()).cancel();
        } finally { reader.close(); }
    }
    @Test void actualElasticsearch401And403AreNotRetryableDependencyFailures() throws Exception {
        for (int status : new int[] {401, 403}) {
            RestClient client = mock(RestClient.class);
            Response response = mock(Response.class);
            when(response.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, status, "denied"));
            ResponseException denied = mock(ResponseException.class);
            when(denied.getResponse()).thenReturn(response);
            when(client.performRequestAsync(any(Request.class), any(ResponseListener.class))).thenAnswer(invocation -> {
                invocation.<ResponseListener>getArgument(1).onFailure(denied);
                return mock(Cancellable.class);
            });
            var reader = new ElasticsearchDeadlineReader(client);
            try {
                assertEquals(status == 401 ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN,
                        assertThrows(RagException.class, () -> reader.read(new Request("POST", "/managed/_search"),
                                Duration.ofSeconds(1), 65536)).code());
            } finally { reader.close(); }
        }
    }
    @Test void transportPolicyFailureIsNotReclassifiedAsRetryable() {
        RestClient client = mock(RestClient.class);
        when(client.performRequestAsync(any(Request.class), any(ResponseListener.class))).thenAnswer(invocation -> {
            invocation.<ResponseListener>getArgument(1).onFailure(new RagException(RagErrorCode.ENDPOINT_POLICY_DENIED));
            return mock(Cancellable.class);
        });
        var reader = new ElasticsearchDeadlineReader(client);
        try {
            assertEquals(RagErrorCode.ENDPOINT_POLICY_DENIED, assertThrows(RagException.class,
                    () -> reader.read(new Request("POST", "/managed/_search"), Duration.ofSeconds(1), 65536)).code());
        } finally { reader.close(); }
    }
}
