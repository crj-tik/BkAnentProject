package com.bkanent.marketing.tool;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.service.MarketingAgentService;
import com.bkanent.marketing.service.MarketingAssetService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketingToolsTest {

    private final MarketingAssetService assetService = Mockito.mock(MarketingAssetService.class);
    private final MarketingAgentService agentService = Mockito.mock(MarketingAgentService.class);
    private final ListingMasterRpcService listingRpcService = Mockito.mock(ListingMasterRpcService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MarketingAgentService> agentServiceProvider = Mockito.mock(ObjectProvider.class);
    private final MarketingAgentProperties properties = new MarketingAgentProperties();

    private final MarketingTools tools = new MarketingTools(
            assetService, agentServiceProvider, properties, listingRpcService);

    @Test
    void explicitCopywritingKeepsLegacyPathWithManualSource() {
        when(assetService.createContent(any())).thenReturn(null);

        tools.createMarketingContent(5L, "DOUYIN", "标题", "TEXT", "已有文案", null, null);

        verify(assetService).createContent(Mockito.argThat(request ->
                "manual".equals(request.source()) && "已有文案".equals(request.copywriting())));
        verifyNoInteractions(agentServiceProvider, listingRpcService);
    }

    @Test
    void blankCopywritingTriggersGenerationWithLlmSource() {
        when(listingRpcService.getListingById(5L)).thenReturn(listing());
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        when(agentService.generateCopy(anyString(), eq("DOUYIN"))).thenReturn("LLM 生成的文案正文");
        when(assetService.createContent(any())).thenReturn(null);

        tools.createMarketingContent(5L, "DOUYIN", "标题", "TEXT", "", null, null);

        verify(agentService).generateCopy(Mockito.contains("望江府"), eq("DOUYIN"));
        verify(assetService).createContent(Mockito.argThat(request ->
                "llm".equals(request.source()) && "LLM 生成的文案正文".equals(request.copywriting())));
    }

    @Test
    void generationFailurePropagatesAndNothingIsSaved() {
        when(listingRpcService.getListingById(5L)).thenReturn(listing());
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        when(agentService.generateCopy(anyString(), anyString())).thenThrow(new IllegalStateException("LLM down"));

        assertThatThrownBy(() -> tools.createMarketingContent(5L, "DOUYIN", "标题", "TEXT", "", null, null))
                .isInstanceOf(IllegalStateException.class);
        verify(assetService, Mockito.never()).createContent(any());
    }

    @Test
    void missingListingRejectsGeneration() {
        when(listingRpcService.getListingById(404L)).thenReturn(null);

        assertThatThrownBy(() -> tools.createMarketingContent(404L, "DOUYIN", "标题", "TEXT", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("404");
        verify(assetService, Mockito.never()).createContent(any());
    }

    @Test
    void generationSwitchOffRejectsBlankCopy() {
        properties.setLlmGenerationEnabled(false);

        assertThatThrownBy(() -> tools.createMarketingContent(5L, "DOUYIN", "标题", "TEXT", "", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未开启");
        verify(assetService, Mockito.never()).createContent(any());
    }

    private ListingDTO listing() {
        return new ListingDTO(5L, "望江府 3 室 2 厅", "滨江路 88 号", "3室2厅",
                new BigDecimal("120"), new BigDecimal("860"), "ON_SALE",
                "中层", "精装", "重点学区", "地铁 2 号线", "已核验");
    }
}
