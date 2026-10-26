package com.bkanent.settlement.service.impl;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.settlement.config.SettlementBatchProperties;
import com.bkanent.settlement.config.SettlementAutoGenerateProperties;
import com.bkanent.settlement.rpc.SettlementRpcServiceImpl;
import com.bkanent.settlement.service.SettlementManagementService;
import com.bkanent.settlement.service.impl.SettlementAutoGenerateJob;
import com.bkanent.settlement.service.SettlementDomainService;
import com.bkanent.settlement.service.SettlementMonthlySummaryService;
import com.bkanent.settlement.service.SettlementPaymentRecordService;
import com.bkanent.settlement.service.SettlementPayoutBatchService;
import com.bkanent.settlement.service.SettlementRuleService;
import com.bkanent.settlement.service.SettlementRuleTierService;
import com.bkanent.settlement.service.SettlementSplitRecordService;
import com.bkanent.settlement.model.SettlementCalculateRequest;
import com.bkanent.settlement.controller.SettlementController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementAuthorizationTest {
    private SettlementDomainService domainService;
    private SettlementSplitRecordService splitRecordService;
    private SettlementMonthlySummaryService monthlySummaryService;
    private SettlementRuleService ruleService;
    private SettlementRuleTierService ruleTierService;
    private SettlementPayoutBatchService payoutBatchService;
    private SettlementPaymentRecordService paymentRecordService;
    private SettlementManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        domainService = mock(SettlementDomainService.class);
        splitRecordService = mock(SettlementSplitRecordService.class);
        monthlySummaryService = mock(SettlementMonthlySummaryService.class);
        ruleService = mock(SettlementRuleService.class);
        ruleTierService = mock(SettlementRuleTierService.class);
        payoutBatchService = mock(SettlementPayoutBatchService.class);
        paymentRecordService = mock(SettlementPaymentRecordService.class);
        service = new SettlementManagementServiceImpl(domainService, splitRecordService,
                monthlySummaryService, ruleService, ruleTierService, payoutBatchService,
                paymentRecordService, new SettlementBatchProperties());
    }

    @Test
    void missingContextRejectsSettlementReadsBeforeDatabaseAccess() {
        assertDenied(AuthzErrorCode.AUTHENTICATION_REQUIRED,
                () -> service.listRules(null));
        verifyNoInteractions(ruleService);

        SettlementRpcServiceImpl rpc = new SettlementRpcServiceImpl(domainService);
        assertDenied(AuthzErrorCode.AUTHENTICATION_REQUIRED,
                () -> rpc.queryMonthlyCommission(81L, "2026-10"));
        verifyNoInteractions(domainService);
    }

    @Test
    void calculationRequiresSeparateOperationsAndStillRejectsUnmappedEmployeeContractPair() throws Exception {
        String calculate = "settlement.calculate.all";
        List<String> permissions = List.of(calculate, "settlement.record.create.all", "settlement.split.calculate.all");
        try (var ignored = McpAuthorizationScope.open(verified(calculate, permissions))) {
            assertDenied(AuthzErrorCode.PERMISSION_DENIED, () -> service.calculateSettlement(
                    new SettlementCalculateRequest(81L, "Employee", null, null, 900L, 400L,
                            "SECOND_HAND", "2026-10", BigDecimal.TEN, null, null, null, null, null)));
        }
        verifyNoInteractions(domainService, splitRecordService, monthlySummaryService, ruleService,
                ruleTierService, payoutBatchService, paymentRecordService);
    }

    @Test
    void aSignedRecordReadStillRequiresTheSeparateSplitReadPermission() throws Exception {
        String recordRead = "settlement.record.read.all";
        when(domainService.getById(1L)).thenReturn(null);
        try (var ignored = McpAuthorizationScope.open(verified(recordRead,
                List.of(recordRead, "settlement.split.read.all")))) {
            service.getSettlementDetail(1L);
        }
        verify(domainService).getById(1L);

        verifyNoInteractions(splitRecordService);
    }

    @Test
    void syntheticPaymentConfirmationAndUnsignedCallbackHaveNoDatabaseSideEffects() throws Exception {
        String confirmation = "settlement.payment.confirm.all";
        try (var ignored = McpAuthorizationScope.open(verified(confirmation, List.of(confirmation)))) {
            assertDenied(AuthzErrorCode.PERMISSION_DENIED, () -> service.markBatchPaid(3L));
        }
        assertDenied(AuthzErrorCode.PERMISSION_DENIED, () -> service.handleBankCallback(null));
        verifyNoInteractions(domainService, payoutBatchService, paymentRecordService);
    }

    @Test
    void legacyCommissionRestReadsNoRecordBeforeAuthorization() {
        SettlementRpcServiceImpl rpc = mock(SettlementRpcServiceImpl.class);
        SettlementController controller = new SettlementController(rpc, domainService, service);
        assertDenied(AuthzErrorCode.AUTHENTICATION_REQUIRED,
                () -> controller.commission(81L, "2026-10"));
        verifyNoInteractions(domainService, rpc);
    }

    @Test
    void scheduledGenerationIsDisabledByDefault() {
        SettlementManagementService management = mock(SettlementManagementService.class);
        SettlementAutoGenerateJob job = new SettlementAutoGenerateJob(management,
                new SettlementAutoGenerateProperties());
        job.autoGenerateSettlement();
        verifyNoInteractions(management);
    }

    private static void assertDenied(AuthzErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(code);
    }

    private static McpAuthorizationContract.Verified verified(String operation, List<String> permissions) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext("1", 1, 1, hash, permissions,
                "settlement-service", "req-1", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 20, now + 21, Map.of(), "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("1", 1, 1, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 10, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "settlement-service", "1",
                List.of(operation), "run-1", null, "req-1", hash, hash, nonce, now - 1, now + 10);
        return new McpAuthorizationContract.Verified("1", "agent-service", "settlement-service",
                operation, "req-1", "settlementTool", context, freshness, binding);
    }
}
