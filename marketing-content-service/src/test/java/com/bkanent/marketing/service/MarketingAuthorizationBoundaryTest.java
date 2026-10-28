package com.bkanent.marketing.service;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import com.bkanent.marketing.converter.MarketingContentConverter;
import com.bkanent.marketing.service.impl.MarketingAssetServiceImpl;
import com.bkanent.marketing.mcp.MarketingMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketingAuthorizationBoundaryTest {
    private final MarketingContentSearchService search = mock(MarketingContentSearchService.class);
    private final MarketingAssetServiceImpl assets = new MarketingAssetServiceImpl(new MarketingContentConverter(), search);

    @Test
    void brokerContentReadRequiresVerifiedOwnerContextBeforeQuery() {
        AuthzException failure = assertThrows(AuthzException.class, () -> assets.listByListingId(8L));
        assertEquals(AuthzErrorCode.AUTHENTICATION_REQUIRED, failure.code());
        verifyNoInteractions(search);
    }

    @Test
    void draftCreationRejectsBeforeMediaSideEffectsWithoutUserContext() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MediaWorkerRpcService> media = mock(ObjectProvider.class);
        MediaWorkerRpcService mediaService = mock(MediaWorkerRpcService.class);
        org.mockito.Mockito.when(media.getIfAvailable()).thenReturn(mediaService);
        MarketingMcpTools tools = new MarketingMcpTools(assets, media, mock(CampaignLifecycleService.class), new ObjectMapper());
        clearInvocations(media, mediaService);

        AuthzException failure = assertThrows(AuthzException.class,
                () -> tools.createMarketingContentDraft(8L, "douyin", "copy"));

        assertEquals(AuthzErrorCode.AUTHENTICATION_REQUIRED, failure.code());
        verifyNoInteractions(mediaService, search);
    }
}
