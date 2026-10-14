package com.bkanent.agent.memory;

import com.bkanent.agent.config.MemoryServiceProperties;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpMemoryStoreClientAuthenticationTest {
    @Test void realUserBearerAndOwnerAreSentAndAuthorityRecheckedWithoutServiceFallback() {
        var credentials = mock(AgentRagCredentialLeaseStore.class);
        when(credentials.currentUserId()).thenReturn("7");
        when(credentials.credentials("7")).thenReturn(new RagCredentialContext("user-token"));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new MemoryServiceProperties(); properties.setBaseUrl("http://memory.test");
        var client = new HttpMemoryStoreClient(builder, properties, credentials);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("sessionId=session")))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(header("X-Memory-Owner", "7"))
                .andRespond(withSuccess("{\"code\":\"0\",\"data\":null}", MediaType.APPLICATION_JSON));
        assertThat(client.getSessionMemory("session")).isEmpty();
        verify(credentials, times(2)).credentials("7"); server.verify();
    }
    @Test void missingOrRevokedUserStopsBeforeMemoryNetworkRequest() {
        var credentials = mock(AgentRagCredentialLeaseStore.class);
        when(credentials.currentUserId()).thenThrow(new RagException(RagErrorCode.AUTHENTICATION_FAILED));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new MemoryServiceProperties(); properties.setBaseUrl("http://memory.test");
        var client = new HttpMemoryStoreClient(builder, properties, credentials);
        assertThatThrownBy(() -> client.getSessionMemory("session")).isInstanceOf(RagException.class);
        server.verify();
    }
}
