package com.bkanent.contract.service;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.contract.client.ContractEsignClientDispatcher;
import com.bkanent.contract.config.ContractIntegrationProperties;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.model.ContractUpsertRequest;
import com.bkanent.contract.service.impl.ContractManagementServiceImpl;
import com.bkanent.contract.validator.ContractStatusFlowValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContractAuthorizationBoundaryTest {
    private final ContractDomainService contracts = mock(ContractDomainService.class);
    private final ContractTemplateService templates = mock(ContractTemplateService.class);
    private final ContractAttachmentService attachments = mock(ContractAttachmentService.class);
    private final ContractEsignClientDispatcher esign = mock(ContractEsignClientDispatcher.class);
    private final com.bkanent.contract.ocr.ContractOcrExtractorDispatcher ocr =
            mock(com.bkanent.contract.ocr.ContractOcrExtractorDispatcher.class);
    private final ContractManagementServiceImpl service = new ContractManagementServiceImpl(
            contracts, templates, attachments, esign, ocr, new ContractStatusFlowValidator(),
            new ContractIntegrationProperties());

    @Test
    void missingUserContextIsRejectedBeforeContractReadOrWrite() {
        AuthzException failure = assertThrows(AuthzException.class, () -> service.getContractDetail(12L));
        assertEquals(AuthzErrorCode.AUTHENTICATION_REQUIRED, failure.code());
        verifyNoInteractions(contracts, attachments);
    }

    @Test
    void requestBrokerIdCannotReplaceTheTrustedAuthMapping() throws Exception {
        try (var ignored = McpAuthorizationScope.open(verified("contract.create.own", Map.of("BROKER", 73L)))) {
            var request = new ContractUpsertRequest(null, "C-1", "title", "SALE", "DRAFT", null,
                    88L, 12L, "customer", "party-a", "party-b", BigDecimal.ONE, null, null);
            AuthzException failure = assertThrows(AuthzException.class, () -> service.saveContract(request));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, failure.code());
        }
        verifyNoInteractions(contracts);
    }

    @Test
    void ownContractReadUsesTheMappedBrokerInTheDatabasePredicate() throws Exception {
        ContractEntity contract = new ContractEntity();
        contract.setId(12L);
        contract.setBrokerId(73L);
        contract.setStatus("DRAFT");
        org.mockito.Mockito.when(contracts.getContractForBroker(12L, 73L)).thenReturn(contract);
        org.mockito.Mockito.when(attachments.listByContractId(12L)).thenReturn(List.of());

        try (var ignored = McpAuthorizationScope.open(verified("contract.read.own", Map.of("BROKER", 73L)))) {
            org.junit.jupiter.api.Assertions.assertEquals(12L, service.getContractDetail(12L).id());
        }
        org.mockito.Mockito.verify(contracts).getContractForBroker(12L, 73L);
        org.mockito.Mockito.verify(contracts, org.mockito.Mockito.never()).getById(12L);
    }

    private McpAuthorizationContract.Verified verified(String operation, Map<String, Long> identities) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "0".repeat(64);
        var context = new AuthorizationContext("123", 1, 0, hash, List.of(operation),
                "agent-service", "req", "run", "session", hash, hash, nonce,
                now - 1, now + 40, now + 50, identities, "Ed25519", "auth-key", null);
        var freshness = new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, "Ed25519", "auth-key", null);
        var binding = new DelegationBinding("agent-service", "contract-service", "123",
                List.of(operation), "run", null, "req", hash, hash, nonce, now, now + 30);
        return new McpAuthorizationContract.Verified("123", "agent-service", "contract-service",
                operation, "req", "contract-tool", context, freshness, binding);
    }
}
