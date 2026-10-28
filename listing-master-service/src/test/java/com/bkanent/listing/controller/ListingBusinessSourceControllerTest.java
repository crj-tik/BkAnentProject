package com.bkanent.listing.controller;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.listing.source.ListingBusinessApiException;
import com.bkanent.listing.source.ListingBusinessSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingBusinessSourceControllerTest {
    @Test
    void exposesSearchEnvelopeAtFrozenProducerRoute() throws Exception {
        ListingBusinessSourceService service = mock(ListingBusinessSourceService.class);
        when(service.search(any(ListingBusinessSearchRequest.class)))
                .thenReturn(new ListingBusinessResponse<>("domain-parallel-v1", List.<ListingSourceSnapshot>of(), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ListingBusinessSourceController(service)).build();

        mvc.perform(post("/listings/business/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractVersion\":\"domain-parallel-v1\",\"keyword\":\"北苑\",\"limit\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contractVersion").value("domain-parallel-v1"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void mapsUnavailableSourceToBounded503Envelope() throws Exception {
        ListingBusinessSourceService service = mock(ListingBusinessSourceService.class);
        when(service.search(any(ListingBusinessSearchRequest.class)))
                .thenThrow(ListingBusinessApiException.dependencyUnavailable());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ListingBusinessSourceController(service)).build();

        mvc.perform(post("/listings/business/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractVersion\":\"domain-parallel-v1\",\"limit\":20}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void batchReadPreservesInaccessibleRefWithoutReturningSnapshot() throws Exception {
        ListingBusinessSourceService service = mock(ListingBusinessSourceService.class);
        ListingRef ref = new ListingRef("provider-a", "A-101");
        when(service.batchRead(any(ListingSnapshotBatchReadRequest.class))).thenReturn(
                new ListingBusinessResponse<>("domain-parallel-v1", List.of(
                        new ListingSnapshotBatchReadItem(ref, ListingSourceStatus.NOT_ACCESSIBLE, null)), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ListingBusinessSourceController(service)).build();

        mvc.perform(post("/listings/business/v1/snapshots/batch-read").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractVersion\":\"domain-parallel-v1\",\"refs\":[{\"listingRef\":{\"sourceSystem\":\"provider-a\",\"sourceId\":\"A-101\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value("domain-parallel-v1"))
                .andExpect(jsonPath("$.data.items[0].listingRef.sourceId").value("A-101"))
                .andExpect(jsonPath("$.data.items[0].status").value("NOT_ACCESSIBLE"))
                .andExpect(jsonPath("$.data.items[0].snapshot").doesNotExist());
    }

    @Test
    void checkReturnsStatusOnlyAndMapsEachFrozenVersionOutcome() throws Exception {
        ListingBusinessSourceService service = mock(ListingBusinessSourceService.class);
        ListingRef ref = new ListingRef("provider-a", "A-101");
        when(service.check(any(ListingSnapshotCheckRequest.class))).thenReturn(
                new ListingBusinessResponse<>("domain-parallel-v1", List.of(
                        new ListingSnapshotCheckItem(ref, "rev-17", ListingSourceStatus.STALE)), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ListingBusinessSourceController(service)).build();

        mvc.perform(post("/listings/business/v1/snapshots/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractVersion\":\"domain-parallel-v1\",\"refs\":[{\"listingRef\":{\"sourceSystem\":\"provider-a\",\"sourceId\":\"A-101\"},\"expectedRevision\":\"rev-17\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("STALE"))
                .andExpect(jsonPath("$.data.items[0].expectedRevision").value("rev-17"))
                .andExpect(jsonPath("$.data.items[0].snapshot").doesNotExist());
        verify(service).check(argThat((ListingSnapshotCheckRequest request) -> request.contractVersion().equals("domain-parallel-v1")
                && request.refs().get(0).expectedRevision().equals("rev-17")));
    }

    @Test
    void mapsAuthorizationFailureToCurrentAuthStatusWithoutLeakingDetails() throws Exception {
        ListingBusinessSourceService service = mock(ListingBusinessSourceService.class);
        when(service.search(any(ListingBusinessSearchRequest.class)))
                .thenThrow(new AuthzException(AuthzErrorCode.PERMISSION_DENIED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ListingBusinessSourceController(service)).build();

        mvc.perform(post("/listings/business/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractVersion\":\"domain-parallel-v1\",\"limit\":20}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.message").value("当前身份无权读取房源"));
    }
}
