package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.listing.source.ListingBusinessApiException;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.bkanent.listing.source.ListingAccessScope;
import com.bkanent.listing.source.ListingSourceAdapter;
import com.bkanent.listing.source.ListingSourcePage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ListingBusinessSourceServiceTest {
    @Test
    void publishesVersionedEmptyAndNonEmptyResponsesOnlyWithCurrentOperation() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        var snapshot = new com.bkanent.common.listing.ListingSourceSnapshot(new ListingRef("fixture", "listing-001"),
                "fixture-v1", "a".repeat(64), java.time.Instant.parse("2026-10-01T00:00:00Z"),
                ListingDataMode.SIMULATED, "fixture-v1", new com.bkanent.common.listing.ListingSnapshotFacts(
                "模拟房源", "90", null, Map.of(), List.of()));
        when(source.search(any(), any())).thenReturn(new ListingSourcePage(List.of(snapshot), null),
                new ListingSourcePage(List.of(), null));
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 77L))) {
            var found = service.search(new ListingBusinessSearchRequest("domain-parallel-v1", "模拟", null, 100));
            var empty = service.search(new ListingBusinessSearchRequest("domain-parallel-v1", "不存在", null, 20));
            assertEquals("domain-parallel-v1", found.contractVersion());
            assertEquals(ListingDataMode.SIMULATED, found.items().get(0).dataMode());
            assertTrue(empty.items().isEmpty());
        }
    }

    @Test
    void rejectsUnknownContractVersionsAndMissingAuthorizationBeforeSourceAccess() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);

        var unsupported = assertThrows(ListingBusinessApiException.class,
                () -> service.search(new ListingBusinessSearchRequest("future-contract", null, null, 20)));
        assertEquals("UNSUPPORTED_CONTRACT_VERSION", unsupported.code());
        AuthzException missing = assertThrows(AuthzException.class,
                () -> service.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 20)));
        assertEquals(AuthzErrorCode.AUTHENTICATION_REQUIRED, missing.code());
        verifyNoInteractions(source);
    }

    @Test
    void rejectsDisabledExpiredAndStaleSignedAuthResponsesBeforeSourceAccess() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);
        var permissions = List.of("listing.read");
        var identities = Map.of("BROKER", 77L);

        assertThrows(AuthzException.class, () -> ListingAuthzScopeFixture.openDisabled(
                permissions, permissions, identities));
        assertThrows(AuthzException.class, () -> ListingAuthzScopeFixture.openExpired(
                permissions, permissions, identities));
        assertThrows(AuthzException.class, () -> ListingAuthzScopeFixture.openStaleSnapshot(
                permissions, permissions, identities));
        assertThrows(AuthzException.class, () -> service.search(
                new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10)));
        verifyNoInteractions(source);
    }

    @Test
    void verifiesCurrentScopeForEverySourceCheckAndPreservesStaleStatus() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);
        var ref = new ListingRef("fixture", "listing-001");
        when(source.check(any(), any())).thenReturn(List.of(new ListingSnapshotCheckItem(
                ref, "old-revision", ListingSourceStatus.STALE)));

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 77L))) {
            var response = service.check(new ListingSnapshotCheckRequest("domain-parallel-v1", List.of(
                    new ListingSourceVersionRef(ref, "old-revision"))));
            assertEquals(ListingSourceStatus.STALE, response.items().get(0).status());
        }
    }

    @Test
    void rechecksCurrentPermissionAfterSourceReturns() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);
        AuthzRequestScope.Scope[] revokedScope = new AuthzRequestScope.Scope[1];
        when(source.search(any(), any())).thenAnswer(invocation -> {
            revokedScope[0] = ListingAuthzScopeFixture.open(false, List.of(), List.of(), Map.of("BROKER", 77L));
            return new ListingSourcePage(List.of(), null);
        });

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 77L))) {
            try {
                AuthzException revoked = assertThrows(AuthzException.class, () -> service.search(
                        new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10)));
                assertEquals(AuthzErrorCode.PERMISSION_DENIED, revoked.code());
                verify(source).search(any(), any());
            } finally {
                if (revokedScope[0] != null) revokedScope[0].close();
            }
        }
    }

    @Test
    void batchReadMapsStaleVersionsToConflictAndRejectsMismatchedSnapshotIdentity() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);
        var requested = new ListingRef("provider-a", "A-101");
        var ref = new ListingSourceVersionRef(requested, "old-revision");
        when(source.batchRead(any(), any())).thenReturn(List.of(
                new com.bkanent.common.listing.ListingSnapshotBatchReadItem(
                        requested, ListingSourceStatus.STALE, null)));

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 77L))) {
            var stale = assertThrows(ListingBusinessApiException.class, () -> service.batchRead(
                    new com.bkanent.common.listing.ListingSnapshotBatchReadRequest("domain-parallel-v1", List.of(ref))));
            assertEquals("SOURCE_VERSION_CONFLICT", stale.code());

            var wrongSnapshot = new com.bkanent.common.listing.ListingSourceSnapshot(
                    new ListingRef("provider-a", "A-OTHER"), "rev-1", "a".repeat(64),
                    java.time.Instant.parse("2026-10-01T00:00:00Z"), ListingDataMode.UNKNOWN, "provider-a-v1",
                    new com.bkanent.common.listing.ListingSnapshotFacts("另一个房源", null, null, Map.of(), List.of()));
            when(source.batchRead(any(), any())).thenReturn(List.of(new com.bkanent.common.listing.ListingSnapshotBatchReadItem(
                    requested, ListingSourceStatus.CURRENT, wrongSnapshot)));
            var mismatch = assertThrows(ListingBusinessApiException.class, () -> service.batchRead(
                    new com.bkanent.common.listing.ListingSnapshotBatchReadRequest("domain-parallel-v1", List.of(ref))));
            assertEquals("DEPENDENCY_UNAVAILABLE", mismatch.code());
        }
    }

    @Test
    void businessWideReadRequiresItsExactPermissionAndUsesTheSignedScope() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        when(source.search(any(), any())).thenReturn(new ListingSourcePage(List.of(), null));
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(true,
                List.of("listing.read.all"), List.of("listing.read.all"), Map.of())) {
            service.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10));
        }
        verify(source).search(any(), argThat(ListingAccessScope::businessWideRead));
    }

    @Test
    void ownListingReadRequiresSignedBrokerIdentityAndExactReadPermission() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);

        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of())) {
            AuthzException missingBroker = assertThrows(AuthzException.class, () -> service.search(
                    new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10)));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, missingBroker.code());
        }
        try (AuthzRequestScope.Scope ignored = ListingAuthzScopeFixture.open(false,
                List.of(), List.of(), Map.of("BROKER", 77L))) {
            AuthzException missingPermission = assertThrows(AuthzException.class, () -> service.search(
                    new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10)));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, missingPermission.code());
        }
        verifyNoInteractions(source);
    }

    @Test
    void rejectsDuplicateReferencesAndInvalidLimits() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        ListingBusinessSourceService service = new ListingBusinessSourceService(source);
        assertThrows(ListingBusinessApiException.class,
                () -> service.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 101)));
        var ref = new ListingRef("fixture", "listing-001");
        assertThrows(ListingBusinessApiException.class,
                () -> service.check(new ListingSnapshotCheckRequest("domain-parallel-v1", List.of(
                        new ListingSourceVersionRef(ref, "r1"), new ListingSourceVersionRef(ref, "r2")))));
        verifyNoInteractions(source);
    }
}
