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
    private final SupervisorGovernanceService service = new SupervisorGovernanceService(
            new DistributedAgentProperties(), mock(SupervisorRateLimiter.class), auditService,
            overrideMapper, catalog, new ObjectMapper());

    @Test
    void governanceViewMustExposeDomainCatalogVocabularySource() {
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
