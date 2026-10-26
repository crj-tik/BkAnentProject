package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignConfirmRequest;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignTargetRequest;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.service.MarketingAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CampaignLifecycleIntegrationTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private MarketingAssetService marketingAssets;
    private AuthPermissionRpcService auth;
    private CampaignRuntimeProperties properties;
    private CampaignJdbcRepository repository;
    private CampaignLifecycleService campaigns;
    private MockHttpServletRequest request;
    private HttpServer listingFixture;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:campaign_flow_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Path migration = repoPath("sql/migrations/20261026_marketing_campaign_lifecycle.sql");
        new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE marketing_content(id BIGINT PRIMARY KEY,status VARCHAR(32),publish_message VARCHAR(1024),external_publish_id VARCHAR(128),publish_time TIMESTAMP)");
        jdbc.update("INSERT INTO marketing_campaign_account(platform,external_account_ref,permission_code,revision_no,data_mode,active,default_account) VALUES('DOUYIN','fixture-account','marketing.channel.douyin.publish',1,'SIMULATED',1,1)");
        jdbc.update("INSERT INTO marketing_content(id,status) VALUES(5,'DRAFT')");

        marketingAssets = mock(MarketingAssetService.class);
        MarketingContentEntity content = new MarketingContentEntity();
        content.setId(5L); content.setListingId(50L); content.setPlatform("DOUYIN");
        content.setTitle("Fixture content"); content.setContentType("TEXT"); content.setCopywriting("approved fixture copy");
        content.setAssetUrls(""); content.setVersionNo(1); content.setAuditStatus("APPROVED"); content.setStatus("DRAFT");
        when(marketingAssets.getById(5L)).thenReturn(content);

        auth = mock(AuthPermissionRpcService.class);
        when(auth.resolvePrincipal("test-token")).thenReturn(new AuthenticatedPrincipal(42L));
        when(auth.hasPermission(eq(42L), anyString())).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<AuthPermissionRpcService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auth);
        CampaignActorResolver actorResolver = new CampaignActorResolver(provider);
        CampaignAuthorizationService authorization = new CampaignAuthorizationService(provider);
        properties = new CampaignRuntimeProperties();
        properties.setMode(CampaignRuntimeProperties.Mode.SIMULATED);
        listingFixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listingFixture.createContext("/listings/business/v1/snapshots/batch-read", exchange -> {
            byte[] response = ("{\"success\":true,\"code\":\"0\",\"message\":\"OK\",\"data\":{\"contractVersion\":\"domain-parallel-v1\",\"items\":[{" +
                    "\"listingRef\":{\"sourceSystem\":\"listing-master-service\",\"sourceId\":\"50\"}," +
                    "\"revision\":\"revision-1\",\"contentHash\":\"" + "a".repeat(64) + "\",\"observedAt\":\"2026-01-01T00:00:00Z\"," +
                    "\"dataMode\":\"REAL\",\"datasetId\":\"listing-fixture-v1\",\"facts\":{\"title\":\"Fixture Listing\"}}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        listingFixture.start();
        properties.setListingSnapshotBaseUrl("http://127.0.0.1:" + listingFixture.getAddress().getPort());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        repository = new CampaignJdbcRepository(jdbc, mapper);
        CampaignListingSnapshotClient listings = new CampaignListingSnapshotClient(properties, mapper);
        campaigns = new CampaignLifecycleService(repository, actorResolver, authorization, properties, listings,
                marketingAssets, mapper, List.of(new SimulatedCampaignPlatformProvider()));

        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void cleanRequest() { RequestContextHolder.resetRequestAttributes(); if (listingFixture != null) listingFixture.stop(0); }

    @Test
    void prepareConfirmDispatchReconcileEffectAndRoiKeepStableSimulatedIdentity() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        assertEquals(CampaignDataMode.SIMULATED, prepared.dataMode());
        assertEquals(64, prepared.snapshotHash().length());
        assertEquals(prepared.createdAt(), campaigns.getPlan(prepared.planId()).createdAt());
        assertEquals(prepared.createdAt().plusSeconds(properties.getPlanTtlSeconds()), prepared.expiresAt());

        var accepted = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("confirm-1", 1, prepared.snapshotHash()));
        assertEquals("ACCEPTED", accepted.status());
        assertEquals(1, accepted.attempts().size());
        String attemptId = accepted.attempts().get(0).attemptId();
        var repeated = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("confirm-1", 1, prepared.snapshotHash()));
        assertEquals(accepted.receiptId(), repeated.receiptId());
        assertTrue(campaigns.dispatchOne());

        var published = campaigns.findReceipt(prepared.planId(), "confirm-1");
        assertEquals("SUCCESS", published.status());
        assertEquals(attemptId, published.attempts().get(0).attemptId());
        assertEquals("sim-" + attemptId, published.attempts().get(0).externalPublishId());

        var effect1 = campaigns.readEffect(attemptId, true);
        var effect2 = campaigns.readEffect(attemptId, true);
        assertEquals(CampaignDataMode.SIMULATED, effect1.dataMode());
        assertEquals(effect1.sourceEventId(), effect2.sourceEventId());
        assertNull(effect1.attributableRevenue());
        assertNull(effect1.roi());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_effect_observation", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_effect_revision", Integer.class));

        var corrected = new CampaignEffectObservation("SIMULATED_FIXTURE", effect1.sourceEventId(), 2,
                effect1.windowStart(), effect1.windowEnd(), effect1.observedAt(), effect1.exposureCount() + 2,
                effect1.clickCount(), effect1.privateMessageCount(), effect1.leadCount(), null,
                java.math.BigDecimal.TEN, "CNY", null, "SIMULATED fixture correction");
        var revision = repository.saveEffect(repository.findAttempt(attemptId), 5L, 50L, corrected);
        assertEquals(2, revision.sourceVersion());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_effect_revision", Integer.class));
        assertEquals(2L, repository.saveEffect(repository.findAttempt(attemptId), 5L, 50L,
                new CampaignEffectObservation("SIMULATED_FIXTURE", effect1.sourceEventId(), 1,
                        effect1.windowStart(), effect1.windowEnd(), effect1.observedAt(), effect1.exposureCount(),
                        effect1.clickCount(), effect1.privateMessageCount(), effect1.leadCount(), null,
                        java.math.BigDecimal.TEN, "CNY", null, "SIMULATED fixture: revenue attribution is unavailable"))
                .sourceVersion());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_effect_observation", Integer.class));
        assertNull(campaigns.roiReport(null).get(0).roi());
        assertNull(campaigns.roiReport(null).get(0).totalAttributableRevenue());
    }

    @Test
    void realModeWithoutProviderFailsBeforeCreatingReceiptOrOutbox() {
        properties.setMode(CampaignRuntimeProperties.Mode.REAL);
        jdbc.update("UPDATE marketing_campaign_account SET data_mode='REAL' WHERE id=1");
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        var failure = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(prepared.planId(),
                new CampaignConfirmRequest("confirm-real", 1, prepared.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PROVIDER_NOT_CONFIGURED, failure.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_outbox", Integer.class));
    }

    @Test
    void incompleteEffectObservationIsReturnedAsUnknownAndNotStored() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        var accepted = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("incomplete-effect", 1, prepared.snapshotHash()));
        String attemptId = accepted.attempts().get(0).attemptId();
        CampaignPlatformProvider incompleteProvider = new CampaignPlatformProvider() {
            @Override public boolean simulated() { return true; }
            @Override public boolean supports(String platform) { return true; }
            @Override public CampaignProviderResult publish(CampaignAttemptData attempt, CampaignTargetData target) {
                return new CampaignProviderResult("SUCCESS", "sim-" + attempt.attemptId(), "fixture", CampaignDataMode.SIMULATED);
            }
            @Override public CampaignProviderResult reconcile(CampaignAttemptData attempt) {
                return publish(attempt, null);
            }
            @Override public CampaignEffectObservation readEffect(CampaignAttemptData attempt) {
                var start = java.time.Instant.parse("2026-01-01T00:00:00Z");
                return new CampaignEffectObservation("SIMULATED_FIXTURE", "missing-observed-at", 1,
                        start, start.plusSeconds(86400), null, 10L, null, null, null,
                        null, null, null, null, "source observation time is unavailable");
            }
        };
        ReflectionTestUtils.setField(campaigns, "providers", List.of(incompleteProvider));

        var unknown = campaigns.readEffect(attemptId, true);

        assertEquals(CampaignDataMode.UNKNOWN, unknown.dataMode());
        assertEquals("effect source returned an incomplete observation", unknown.unavailableReason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_effect_observation", Integer.class));
    }

    @Test
    void changedAccountRevisionInvalidatesPreparedPlanAndRepeatedRequestBodyCannotChange() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        jdbc.update("UPDATE marketing_campaign_account SET revision_no=2 WHERE id=1");
        var failure = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(prepared.planId(),
                new CampaignConfirmRequest("confirm-account-change", 1, prepared.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PLAN_VERSION_CONFLICT, failure.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
    }

    @Test
    void contentChangeAndExpiredPlanRequireFreshPreparation() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        MarketingContentEntity content = marketingAssets.getById(5L);
        content.setCopywriting("changed after approval");
        var changed = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(prepared.planId(),
                new CampaignConfirmRequest("content-changed", 1, prepared.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PLAN_VERSION_CONFLICT, changed.code());

        content.setCopywriting("approved fixture copy");
        jdbc.update("UPDATE marketing_campaign_plan SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE plan_id=?", prepared.planId());
        var expired = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(prepared.planId(),
                new CampaignConfirmRequest("expired-confirm", 1, prepared.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.CONFIRMATION_EXPIRED, expired.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
    }

    @Test
    void partialSuccessAndUnknownReconciliationKeepPerTargetAttemptIds() {
        jdbc.update("INSERT INTO marketing_campaign_account(platform,external_account_ref,permission_code,revision_no,data_mode,active,default_account) VALUES('DOUYIN','fixture-account-2','marketing.channel.douyin.publish',1,'SIMULATED',1,0)");
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(), List.of(
                new CampaignTargetRequest("douyin", 1L, Map.of()),
                new CampaignTargetRequest("douyin", 2L, Map.of("fixtureOutcome", "FAILED"))), null));
        var accepted = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("partial-request", 1, prepared.snapshotHash()));
        String successId = accepted.attempts().get(0).attemptId();
        String failedId = accepted.attempts().get(1).attemptId();
        assertTrue(campaigns.dispatchOne());
        assertTrue(campaigns.dispatchOne());
        var result = campaigns.findReceipt(prepared.planId(), "partial-request");
        assertEquals("PARTIAL_SUCCESS", result.status());
        assertEquals(List.of(successId, failedId), result.attempts().stream().map(a -> a.attemptId()).toList());
        assertEquals(CampaignDataMode.SIMULATED, campaigns.readEffect(successId, true).dataMode());

        var unknownPlan = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of("fixtureOutcome", "UNKNOWN"))), null));
        var unknownReceipt = campaigns.confirm(unknownPlan.planId(),
                new CampaignConfirmRequest("unknown-request", 1, unknownPlan.snapshotHash()));
        String originalAttemptId = unknownReceipt.attempts().get(0).attemptId();
        assertTrue(campaigns.dispatchOne());
        assertEquals("OUTCOME_UNKNOWN", campaigns.findReceipt(unknownPlan.planId(), "unknown-request").status());
        var reconciled = campaigns.reconcile(originalAttemptId);
        assertEquals("SUCCESS", reconciled.status());
        assertEquals(originalAttemptId, reconciled.attempts().get(0).attemptId());
        assertEquals(CampaignDataMode.SIMULATED, campaigns.readEffect(originalAttemptId, true).dataMode());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
    }

    @Test
    void deniedScopeAndRequestIdReuseWithDifferentBodyAreRejected() {
        when(auth.hasPermission(42L, "marketing.campaign.prepare")).thenReturn(false);
        var denied = assertThrows(CampaignDomainException.class, () -> campaigns.prepare(new CampaignPlanPrepareRequest(5L,
                List.of(), List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null)));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PERMISSION_DENIED, denied.code());
        when(auth.hasPermission(42L, "marketing.campaign.prepare")).thenReturn(true);

        when(auth.hasPermission(42L, "marketing.channel.douyin.publish")).thenReturn(false);
        var deniedTarget = assertThrows(CampaignDomainException.class, () -> campaigns.prepare(new CampaignPlanPrepareRequest(5L,
                List.of(), List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null)));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PERMISSION_DENIED, deniedTarget.code());
        when(auth.hasPermission(42L, "marketing.channel.douyin.publish")).thenReturn(true);

        var preparedBeforeRevocation = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        when(auth.hasPermission(42L, "marketing.channel.douyin.publish")).thenReturn(false);
        var revoked = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(preparedBeforeRevocation.planId(),
                new CampaignConfirmRequest("revoked-target", 1, preparedBeforeRevocation.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.PERMISSION_DENIED, revoked.code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
        when(auth.hasPermission(42L, "marketing.channel.douyin.publish")).thenReturn(true);

        var first = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        campaigns.confirm(first.planId(), new CampaignConfirmRequest("same-key", 1, first.snapshotHash()));
        var second = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of("different", "body"))), null));
        var reused = assertThrows(CampaignDomainException.class, () -> campaigns.confirm(second.planId(),
                new CampaignConfirmRequest("same-key", 1, second.snapshotHash())));
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.REQUEST_ID_CONFLICT, reused.code());
    }

    @Test
    void crashedDispatcherLeaseBecomesUnknownAndLateSuccessCannotOverwriteReconciliation() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        var accepted = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("crash-request", 1, prepared.snapshotHash()));
        String attemptId = accepted.attempts().get(0).attemptId();
        CampaignAttemptData claimed = repository.claimPending("crashed-lease", 30);
        assertEquals("DISPATCHING", claimed.status());
        jdbc.update("UPDATE marketing_campaign_outbox SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE attempt_id=?", attemptId);
        repository.recoverExpiredDispatches();
        assertEquals("OUTCOME_UNKNOWN", repository.findAttempt(attemptId).status());
        assertFalse(repository.finishAttempt(claimed, "crashed-lease", "SUCCESS", "late-real-id", "late callback", CampaignDataMode.SIMULATED));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt WHERE external_publish_id='late-real-id'", Integer.class));
        var reconciled = campaigns.reconcile(attemptId);
        assertEquals("SUCCESS", reconciled.status());
        assertEquals(attemptId, reconciled.attempts().get(0).attemptId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign_publish_attempt", Integer.class));
    }

    @Test
    void realRoiNeedsAttributableRevenueAndSameCurrencyEvidence() {
        var prepared = campaigns.prepare(new CampaignPlanPrepareRequest(5L, List.of(),
                List.of(new CampaignTargetRequest("douyin", 1L, Map.of())), null));
        var accepted = campaigns.confirm(prepared.planId(), new CampaignConfirmRequest("roi-request", 1, prepared.snapshotHash()));
        assertTrue(campaigns.dispatchOne());
        CampaignAttemptData stored = repository.findAttempt(accepted.attempts().get(0).attemptId());
        CampaignAttemptData realAttempt = new CampaignAttemptData(stored.attemptId(), stored.receiptId(), stored.planId(),
                stored.targetIndex(), stored.platform(), stored.accountId(), stored.status(), CampaignDataMode.REAL,
                stored.externalPublishId(), stored.message(), stored.attemptVersion(), stored.updatedAt());
        var start = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var observation = new CampaignEffectObservation("REAL_CHANNEL", "roi-event", 1, start, start.plusSeconds(86400),
                start.plusSeconds(86400), 100L, 20L, 3L, 10L, null, new java.math.BigDecimal("20.00"),
                "CNY", "roi-v1", null);
        var costOnly = repository.saveEffect(realAttempt, 5L, 50L, observation);
        assertNull(costOnly.attributableRevenue());
        assertNull(costOnly.roi());
        var report = campaigns.roiReport("DOUYIN").stream().filter(row -> row.dataMode() == CampaignDataMode.REAL).findFirst().orElseThrow();
        assertEquals(new java.math.BigDecimal("2.0000"), report.leadCost());
        assertNull(report.roi());
        assertNull(report.totalAttributableRevenue());

        var noCurrency = new CampaignEffectObservation("REAL_CHANNEL", "roi-event", 2, start, start.plusSeconds(86400),
                start.plusSeconds(86400), 100L, 20L, 3L, 10L, null,
                new java.math.BigDecimal("20.00"), null, null, "currency is unavailable");
        repository.saveEffect(realAttempt, 5L, 50L, noCurrency);
        report = campaigns.roiReport("DOUYIN").stream().filter(row -> row.dataMode() == CampaignDataMode.REAL).findFirst().orElseThrow();
        assertNull(report.leadCost());
        assertNull(report.roi());

        var mixedMode = new CampaignEffectObservation("REAL_CHANNEL", "roi-event", 3, start, start.plusSeconds(86400),
                start.plusSeconds(86400), 100L, 20L, 3L, 10L, new java.math.BigDecimal("120.00"),
                new java.math.BigDecimal("20.00"), "CNY", "roi-v1", null);
        repository.saveEffect(realAttempt, 5L, 50L, mixedMode);
        jdbc.update("UPDATE marketing_campaign_effect_observation SET data_mode='MIXED' WHERE source_event_id='roi-event'");
        report = campaigns.roiReport("DOUYIN").stream().filter(row -> row.dataMode() == CampaignDataMode.MIXED).findFirst().orElseThrow();
        assertNull(report.roi());
        assertNull(report.totalAttributableRevenue());

        var corrected = new CampaignEffectObservation("REAL_CHANNEL", "roi-event", 4, start, start.plusSeconds(86400),
                start.plusSeconds(86400), 100L, 20L, 3L, 10L, new java.math.BigDecimal("120.00"),
                new java.math.BigDecimal("20.00"), "CNY", "roi-v1", null);
        repository.saveEffect(realAttempt, 5L, 50L, corrected);
        report = campaigns.roiReport("DOUYIN").stream().filter(row -> row.dataMode() == CampaignDataMode.REAL).findFirst().orElseThrow();
        assertEquals(new java.math.BigDecimal("5.000000"), report.roi());
        assertEquals("CNY", report.currency());
        assertEquals("roi-v1", report.roiDefinitionVersion());
    }

    private Path repoPath(String relative) {
        Path path = Path.of(relative);
        if (Files.exists(path)) return path;
        Path parent = Path.of("..", relative);
        if (Files.exists(parent)) return parent;
        throw new IllegalStateException("Cannot locate repository file: " + relative);
    }
}
