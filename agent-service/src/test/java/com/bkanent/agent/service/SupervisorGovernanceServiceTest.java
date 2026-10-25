package com.bkanent.agent.service;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.mapper.AgentGovernanceOverrideMapper;
import com.bkanent.agent.model.distributed.SupervisorGovernanceView;
import com.bkanent.agent.stream.SessionEventAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorGovernanceServiceTest {

    private final AgentGovernanceOverrideMapper overrideMapper = mock(AgentGovernanceOverrideMapper.class);
    private final SessionEventAuditService auditService = mock(SessionEventAuditService.class);
    private final DomainCatalog catalog = mock(DomainCatalog.class);
    private final com.bkanent.agent.security.AgentPrincipalContext principal = mock(com.bkanent.agent.security.AgentPrincipalContext.class);
    private final com.bkanent.common.rpc.AuthPermissionRpcService auth = mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
    private final AgentPermissionService permissions = new AgentPermissionService(auth, mock(PermissionAuditService.class));
    private final SupervisorGovernanceService service = new SupervisorGovernanceService(
            new DistributedAgentProperties(), mock(SupervisorRateLimiter.class), auditService,
            overrideMapper, catalog, new ObjectMapper(), permissions, principal);

    @org.junit.jupiter.api.BeforeEach
    void authenticatedActor() { when(principal.userId()).thenReturn("2"); }

    @Test
    void loginAndReadPermissionCannotAuthorizeAnyGovernanceWrite() {
        when(auth.hasPermission(2L, "agent.governance.read")).thenReturn(true);
        for (Runnable write : List.<Runnable>of(
                () -> service.overrideRateLimit(new com.bkanent.agent.model.distributed.GovernanceRateLimitOverrideRequest("supervisor.tasks", 1)),
                () -> service.clearRateLimitOverride(null),
                () -> service.overrideGrayRelease(new com.bkanent.agent.model.distributed.GovernanceGrayReleaseOverrideRequest(true, "version", true)),
                service::clearGrayReleaseOverride)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(write::run).hasMessageContaining("permission denied");
        }
        org.mockito.Mockito.verifyNoInteractions(overrideMapper, auditService, catalog);
        org.mockito.Mockito.verify(auth, org.mockito.Mockito.times(4)).hasPermission(2L, "agent.governance.manage");
    }

    @Test
    void managePermissionCannotAuthorizeGovernanceRead() {
        when(auth.hasPermission(2L, "agent.governance.manage")).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(service::viewGovernance).hasMessageContaining("permission denied");
        org.mockito.Mockito.verifyNoInteractions(overrideMapper, auditService, catalog);
    }

    @Test
    void authorizedWriteRecordsTrustedActorAndDoesNotInventPermissionRevision() {
        when(auth.hasPermission(2L, "agent.governance.manage")).thenReturn(true);
        when(overrideMapper.selectList(any())).thenReturn(List.of());
        when(auditService.summarize()).thenReturn(Map.of());
        when(catalog.snapshot()).thenReturn(new DomainCatalog.CatalogSnapshot(List.of(), List.of(), DomainCatalog.SOURCE_REGISTRY));
        service.overrideRateLimit(new com.bkanent.agent.model.distributed.GovernanceRateLimitOverrideRequest("supervisor.tasks", 1));
        var capture = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
        org.mockito.Mockito.verify(auditService).recordRequired(capture.capture());
        assertThat(capture.getValue().metadata()).containsEntry("actorId", "2")
                .containsEntry("authorizationVersion", "legacy-rpc-unversioned");
        org.mockito.Mockito.verify(overrideMapper).insert(any(com.bkanent.agent.entity.AgentGovernanceOverrideEntity.class));
        org.mockito.Mockito.verify(auth, org.mockito.Mockito.never()).hasPermission(2L, "agent.governance.read");
    }

    @Test
    void governanceViewMustExposeDomainCatalogVocabularySource() {
        when(auth.hasPermission(2L, "agent.governance.read")).thenReturn(true);
        when(overrideMapper.selectList(any())).thenReturn(List.of());
        when(overrideMapper.selectOne(any())).thenReturn(null);
        when(auditService.summarize()).thenReturn(Map.of());
        when(catalog.snapshot()).thenReturn(new DomainCatalog.CatalogSnapshot(
                List.of("compare", "listing"),
                List.of(),
                DomainCatalog.SOURCE_REGISTRY));

        SupervisorGovernanceView view = service.viewGovernance();

        assertThat(view.domainCatalog()).containsEntry("vocabularySource", DomainCatalog.SOURCE_REGISTRY);
        assertThat(view.domainCatalog().get("domains")).isEqualTo(List.of("compare", "listing"));
        assertThat(view.domainCatalog()).doesNotContainKey("branchCapacity");
    }
}
