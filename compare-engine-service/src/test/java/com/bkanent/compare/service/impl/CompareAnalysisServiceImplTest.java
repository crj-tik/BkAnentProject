package com.bkanent.compare.service.impl;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.compare.config.CompareAgentProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CompareAnalysisServiceImplTest {

    private final CompareReportCacheService cacheService = Mockito.mock(CompareReportCacheService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ListingMasterRpcService> rpcProvider = Mockito.mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> chatModelProvider = Mockito.mock(ObjectProvider.class);
    private final ChatModel chatModel = Mockito.mock(ChatModel.class);
    private final CompareAgentProperties properties = new CompareAgentProperties();

    private CompareAnalysisServiceImpl service() {
        return new CompareAnalysisServiceImpl(cacheService, rpcProvider, chatModelProvider, properties);
    }

    @Test
    void llmConclusionUsedWhenAvailable() {
        mockCacheMiss();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("LLM 对比结论：A 房源性价比更高。"));

        String conclusion = service().generateCompareReport(List.of(1L, 2L), true).aiConclusion();

        assertThat(conclusion).isEqualTo("LLM 对比结论：A 房源性价比更高。");
    }

    @Test
    void llmFailureFallsBackToTemplate() {
        mockCacheMiss();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("LLM down"));

        String conclusion = service().generateCompareReport(List.of(1L, 2L), true).aiConclusion();

        assertThat(conclusion).contains("共对比 2 套房源").contains("总价更低的房源");
    }

    @Test
    void switchOffUsesTemplate() {
        properties.setAiConclusionEnabled(false);
        mockCacheMiss();

        String conclusion = service().generateCompareReport(List.of(1L, 2L), true).aiConclusion();

        assertThat(conclusion).contains("共对比 2 套房源");
        Mockito.verifyNoInteractions(chatModelProvider);
    }

    @Test
    void insufficientMetricsShortCircuitsWithoutLlm() {
        mockCacheMiss();
        ListingMasterRpcService rpc = rpcServiceReturning(listingWithoutMetrics());
        when(rpcProvider.getIfAvailable()).thenReturn(rpc);

        String conclusion = service().generateCompareReport(List.of(9L), true).aiConclusion();

        assertThat(conclusion).contains("对比指标不足");
        Mockito.verifyNoInteractions(chatModelProvider);
    }

    private void mockCacheMiss() {
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        ListingMasterRpcService rpc = rpcServiceReturning(
                listing(1L, "阳光花园", new BigDecimal("300"), new BigDecimal("500")),
                listing(2L, "翠湖天地", new BigDecimal("260"), new BigDecimal("620")));
        when(rpcProvider.getIfAvailable()).thenReturn(rpc);
        when(cacheService.findByCacheKey(any())).thenReturn(Optional.empty());
        when(cacheService.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    private ListingMasterRpcService rpcServiceReturning(ListingDTO... listings) {
        ListingMasterRpcService rpc = Mockito.mock(ListingMasterRpcService.class);
        for (ListingDTO listing : listings) {
            when(rpc.getListingById(listing.id())).thenReturn(listing);
        }
        return rpc;
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
}
