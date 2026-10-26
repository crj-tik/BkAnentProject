package com.bkanent.business.service.impl;

import com.bkanent.business.config.BusinessRankingProperties;
import com.bkanent.business.config.BusinessRedisProperties;
import com.bkanent.business.converter.BusinessConverter;
import com.bkanent.business.service.EmployeeDailyWorkloadService;
import com.bkanent.business.service.KpiStatService;
import com.bkanent.business.service.ListingTurnoverStatService;
import com.bkanent.business.service.StoreDashboardSnapshotService;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessAuthorizationTest {
    private static final String KPI_READ = "business.kpi.read.all";
    private KpiStatService kpiStatService;
    private BusinessAnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        kpiStatService = mock(KpiStatService.class);
        service = new BusinessAnalyticsServiceImpl(kpiStatService,
                mock(EmployeeDailyWorkloadService.class), mock(ListingTurnoverStatService.class),
                mock(StoreDashboardSnapshotService.class), mock(BusinessConverter.class),
                new BusinessRankingProperties(), new BusinessRedisProperties(), Optional.empty());
    }

    @Test
    void unauthenticatedRpcOrRestCallFailsBeforeReadingKpis() {
        assertThatThrownBy(() -> service.listMonthlyKpis("2026-10"))
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(kpiStatService);
    }

    @Test
    void verifiedExactMcpOperationCanReadKpisWithoutAuthRpc() throws Exception {
        when(kpiStatService.listByMonth("2026-10")).thenReturn(List.of());
        try (var ignored = McpAuthorizationScope.open(verified(KPI_READ, List.of(KPI_READ)))) {
            assertThat(service.listMonthlyKpis("2026-10")).isEmpty();
        }
        verify(kpiStatService).listByMonth("2026-10");
    }

    @Test
    void anotherSignedOperationCannotReadKpis() throws Exception {
        String other = "business.workload.read.all";
        try (var ignored = McpAuthorizationScope.open(verified(other, List.of(other)))) {
            assertThatThrownBy(() -> service.listMonthlyKpis("2026-10"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(kpiStatService);
    }

    private static McpAuthorizationContract.Verified verified(String operation, List<String> permissions) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext("1", 1, 1, hash, permissions,
                "business-service", "req-1", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 20, now + 21, Map.of(), "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("1", 1, 1, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 10, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "business-service", "1",
                List.of(operation), "run-1", null, "req-1", hash, hash, nonce, now - 1, now + 10);
        return new McpAuthorizationContract.Verified("1", "agent-service", "business-service",
                operation, "req-1", "queryMonthlyKpis", context, freshness, binding);
    }
}
