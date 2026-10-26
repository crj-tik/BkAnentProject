package com.bkanent.compare.service.impl;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.authz.*;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.compare.config.CompareAgentProperties;
import com.bkanent.compare.service.CompareListingReadClient;
import com.bkanent.compare.service.CompareReportCacheService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Base64;
import java.security.KeyPairGenerator;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CompareAnalysisServiceImplTest {

    private final CompareReportCacheService cacheService = Mockito.mock(CompareReportCacheService.class);
    private final CompareListingReadClient listingReadClient = Mockito.mock(CompareListingReadClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> chatModelProvider = Mockito.mock(ObjectProvider.class);
    private final ChatModel chatModel = Mockito.mock(ChatModel.class);
    private final CompareAgentProperties properties = new CompareAgentProperties();

    private CompareAnalysisServiceImpl service() {
        return new CompareAnalysisServiceImpl(cacheService, listingReadClient, chatModelProvider, properties);
    }

    @Test
    void llmConclusionUsedWhenAvailable() {
        mockCacheMiss();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("LLM 对比结论：A 房源性价比更高。"));

        String conclusion = authorized(() -> service().generateCompareReport(List.of(1L, 2L), true).aiConclusion());

        assertThat(conclusion).isEqualTo("LLM 对比结论：A 房源性价比更高。");
    }

    @Test
    void llmFailureFallsBackToTemplate() {
        mockCacheMiss();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("LLM down"));

        String conclusion = authorized(() -> service().generateCompareReport(List.of(1L, 2L), true).aiConclusion());

        assertThat(conclusion).contains("共对比 2 套房源").contains("总价更低的房源");
    }

    @Test
    void switchOffUsesTemplate() {
        properties.setAiConclusionEnabled(false);
        mockCacheMiss();

        String conclusion = authorized(() -> service().generateCompareReport(List.of(1L, 2L), true).aiConclusion());

        assertThat(conclusion).contains("共对比 2 套房源");
        Mockito.verifyNoInteractions(chatModelProvider);
    }

    @Test
    void insufficientMetricsShortCircuitsWithoutLlm() {
        mockCacheMiss();
        when(listingReadClient.read(List.of(9L))).thenReturn(List.of(listingWithoutMetrics()));

        String conclusion = authorized(() -> service().generateCompareReport(List.of(9L), true).aiConclusion());

        assertThat(conclusion).contains("对比指标不足");
        Mockito.verifyNoInteractions(chatModelProvider);
    }

    @Test
    void comparisonWithoutVerifiedUserContextIsRejectedBeforeListingRead() {
        assertThatThrownBy(() -> service().generateCompareReport(List.of(1L), false))
                .isInstanceOf(AuthzException.class);
        Mockito.verifyNoInteractions(listingReadClient);
    }

    private void mockCacheMiss() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(listingReadClient.read(List.of(1L, 2L))).thenReturn(List.of(
                listing(1L, "阳光花园", new BigDecimal("300"), new BigDecimal("500")),
                listing(2L, "翠湖天地", new BigDecimal("260"), new BigDecimal("620"))));
        when(cacheService.findByCacheKey(any(), any())).thenReturn(Optional.empty());
        when(cacheService.save(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(3));
    }

    private ListingDTO listing(Long id, String title, BigDecimal area, BigDecimal totalPrice) {
        return new ListingDTO(id, title, "地址 " + id, "3室2厅", area, totalPrice,
                "ON_SALE", "中层", "精装", "重点学区", "地铁 2 号线", "已核验");
    }

    private ListingDTO listingWithoutMetrics() {
        return new ListingDTO(9L, "无指标房源", "地址 9", "2室1厅", null, null,
                "ON_SALE", null, null, null, null, "已核验");
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String authorized(java.util.function.Supplier<String> action) {
        try (var ignored = openAuthorization()) { return action.get(); }
    }

    private TestScope openAuthorization() {
        try {
            long now = Instant.now().getEpochSecond();
            long expires = now + 120;
            long freshnessExpires = now + 30;
            long bindingExpires = now + 20;
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            String hash = "a".repeat(64);
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var context = AuthzSignature.sign(new AuthorizationContext("73", 1, 0, hash,
                    List.of("compare.report.create", "listing.read"), "agent-service", "entry-request",
                    "run-73", "session-73", hash, hash, nonce, now, expires, expires,
                    Map.of(), AuthzContract.ALGORITHM, "auth-test", null), key.getPrivate());
            var freshness = AuthzSignature.sign(new AuthzFreshness("73", 1, 0, AuthorizationStatus.ACTIVE,
                    hash, now, freshnessExpires, nonce, AuthzContract.ALGORITHM, "auth-test", null), key.getPrivate());
            var binding = new DelegationBinding("agent-service", "compare-engine-service", "73",
                    List.of("compare.report.create"), "run-73", "task-73", "request-73", hash, hash,
                    nonce, now, bindingExpires);
            var verified = new McpAuthorizationContract.Verified("73", "agent-service",
                    "compare-engine-service", "compare.report.create", "request-73", "compareListings",
                    context, freshness, binding);
            AutoCloseable scope = McpAuthorizationScope.open(verified);
            return () -> {
                try { scope.close(); }
                catch (Exception closeFailure) { throw new IllegalStateException(closeFailure); }
            };
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @FunctionalInterface
    private interface TestScope extends AutoCloseable { @Override void close(); }
}
