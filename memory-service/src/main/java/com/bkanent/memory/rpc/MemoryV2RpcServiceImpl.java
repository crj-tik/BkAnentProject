package com.bkanent.memory.rpc;

import com.bkanent.common.memory.*;
import com.bkanent.memory.service.MemoryArchiveService;
import com.bkanent.memory.service.MemorySharingAccessService;
import com.bkanent.memory.service.MemoryV2RequestSecurity;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Opt-in native Dubbo provider. Unimplemented learning/search capability fails explicitly. */
@DubboService(group = MemoryV2Contract.DUBBO_GROUP, version = MemoryV2Contract.DUBBO_SERVICE_VERSION, protocol = "dubbo")
@ConditionalOnProperty(name = "memory.v2.archive-enabled", havingValue = "true")
public class MemoryV2RpcServiceImpl implements MemoryV2RpcService {
    private final MemoryArchiveService archive;
    private final MemorySharingAccessService sharing;
    private final MemoryV2RequestSecurity security;
    private final com.bkanent.memory.service.PreferenceHistoryService preferences;
    private final com.bkanent.memory.service.MemoryMatterService matters;
    private final com.bkanent.memory.service.MemoryExperienceService experiences;
    private final com.bkanent.memory.service.MemorySharingWriteService sharingWrites;
    private final com.bkanent.memory.service.MemoryCollaborationSearchService collaborationSearch;

    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security) {
        this(archive, sharing, security, null, null, null, null, null, true);
    }

    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                  com.bkanent.memory.service.PreferenceHistoryService preferences) {
        this(archive, sharing, security, preferences, null, null, null, null, true);
    }

    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                  com.bkanent.memory.service.PreferenceHistoryService preferences, com.bkanent.memory.service.MemoryMatterService matters) {
        this(archive, sharing, security, preferences, matters, null, null, null, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                  com.bkanent.memory.service.PreferenceHistoryService preferences, com.bkanent.memory.service.MemoryMatterService matters,
                                  ObjectProvider<com.bkanent.memory.service.MemoryExperienceService> experiences,
                                  ObjectProvider<com.bkanent.memory.service.MemorySharingWriteService> sharingWrites,
                                  ObjectProvider<com.bkanent.memory.service.MemoryCollaborationSearchService> collaborationSearch) {
        this(archive, sharing, security, preferences, matters, experiences.getIfAvailable(), sharingWrites.getIfAvailable(),
                collaborationSearch.getIfAvailable(), true);
    }

    private MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                   com.bkanent.memory.service.PreferenceHistoryService preferences, com.bkanent.memory.service.MemoryMatterService matters,
                                   com.bkanent.memory.service.MemoryExperienceService experiences,
                                   com.bkanent.memory.service.MemorySharingWriteService sharingWrites,
                                   com.bkanent.memory.service.MemoryCollaborationSearchService collaborationSearch,
                                   boolean ignored) {
        this.archive = archive;
        this.sharing = sharing;
        this.security = security;
        this.preferences = preferences;
        this.matters = matters;
        this.experiences = experiences;
        this.sharingWrites = sharingWrites;
        this.collaborationSearch = collaborationSearch;
    }

    public MemoryMatterWriteResponse transitionMatter(MemoryMatterWriteRequest request, MemoryRequestProof proof) {
        if(matters==null) { security.authorize(request,proof,MemoryV2Contract.OPERATION_MATTER_WRITE); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return matters.transition(request,proof);
    }

    public MemoryWorkingSetResponse readWorkingSet(MemoryWorkingSetRequest request, MemoryRequestProof proof) { return archive.readWorkingSet(request, proof); }
    public MemoryReceivePermitResponse requestReceivePermit(MemoryReceivePermitRequest request, MemoryRequestProof proof) { return archive.requestReceivePermit(request, proof); }
    public MemoryDeliveryResponse deliverMessages(MemoryDeliveryRequest request, MemoryRequestProof proof) { return archive.deliverMessages(request, proof); }
    public MemorySummaryResponse submitSummary(MemorySummaryRequest request, MemoryRequestProof proof) { return archive.submitSummary(request, proof); }
    public MemoryMatterQueryResponse queryMatters(MemoryMatterQueryRequest request, MemoryRequestProof proof) { return archive.queryMatters(request, proof); }
    public MemoryEntityQueryResponse queryEntities(MemoryEntityQueryRequest request, MemoryRequestProof proof) { return archive.queryEntities(request, proof); }
    public MemoryGrantResponse getGrant(MemoryGrantRequest request, MemoryRequestProof proof) { return sharing.getGrant(request, proof); }
    public MemoryShareGrantManagementResponse manageShareGrant(MemoryShareGrantManagementRequest request, MemoryRequestProof proof) {
        if (sharingWrites == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return sharingWrites.manageGrant(request, proof);
    }
    public MemorySharedExportStatusResponse submitSharedExport(MemorySharedExportSubmitRequest request, MemoryRequestProof proof) {
        if (sharingWrites == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return sharingWrites.submitExport(request, proof);
    }
    public MemorySharedExportStatusResponse commitSharedExport(MemorySharedExportCommitRequest request, MemoryRequestProof proof) {
        if (sharingWrites == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return sharingWrites.commitExport(request, proof);
    }
    public MemorySharedExportStatusResponse sharedExportStatus(MemorySharedExportStatusRequest request, MemoryRequestProof proof) {
        if (sharingWrites == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return sharingWrites.status(request, proof);
    }
    public MemoryWithdrawalResponse withdrawSource(MemoryWithdrawalRequest request, MemoryRequestProof proof) { return archive.withdrawSource(request, proof); }
    public MemoryPrivacyCleanupStatusResponse privacyCleanupStatus(MemoryPrivacyCleanupStatusRequest request, MemoryRequestProof proof) {
        return archive.privacyCleanupStatus(request, proof);
    }
    public MemoryWriterResponse manageWriter(MemoryWriterRequest request, MemoryRequestProof proof) { return archive.manageWriter(request, proof); }
    public MemoryBoundaryResponse checkBoundary(MemoryBoundaryRequest request, MemoryRequestProof proof) { return archive.checkBoundary(request, proof); }
    public MemoryPreferenceWriteResponse submitPreferenceAssessment(MemoryPreferenceWriteRequest request, MemoryRequestProof proof) {
        if (preferences == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_PREFERENCE_WRITE); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return preferences.submit(request, proof);
    }
    public MemoryPreferenceDetailResponse readPreferenceHistory(MemoryPreferenceDetailRequest request, MemoryRequestProof proof) {
        if (preferences == null) { security.authorize(request, proof, MemoryV2Contract.OPERATION_PREFERENCE_DETAIL); throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        return preferences.read(request, proof);
    }

    public MemoryPreferenceQueryResponse searchPreferences(MemoryPreferenceQueryRequest request, MemoryRequestProof proof) {
        if (preferences != null) return preferences.search(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_PREFERENCES);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    public MemoryPreferenceAssemblyResponse assemblePreferences(MemoryPreferenceAssemblyRequest request, MemoryRequestProof proof) {
        if (preferences == null) {
            security.authorize(request, proof, MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES);
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
        return preferences.assemble(request, proof);
    }

    public MemoryExperienceQueryResponse searchExperiences(MemoryExperienceQueryRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.search(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    @Override
    public MemoryCollaborationSearchResponse searchCollaborations(
            MemoryCollaborationSearchRequest request, MemoryRequestProof proof) {
        if (collaborationSearch != null) return collaborationSearch.search(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_COLLABORATION);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    public MemoryExperienceEventStatusResponse submitExperienceOutcome(MemoryExperienceOutcomeRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.submitOutcome(request, proof);
        security.authorizeExperienceOutcome(request, proof);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    public MemoryExperienceEventStatusResponse submitExperienceFeedback(MemoryExperienceFeedbackRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.submitFeedback(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    public MemoryExperienceEventStatusResponse experienceEventStatus(MemoryExperienceEventStatusRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.status(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_STATUS);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    public MemoryExperienceLifecycleResponse changeExperienceLifecycle(MemoryExperienceLifecycleRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.changeLifecycle(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    @Override
    public MemoryDomainExperienceManagementResponse manageDomainExperience(
            MemoryDomainExperienceManagementRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.manageDomainExperience(request, proof);
        security.authorizeDomainExperienceManagement(request, proof);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    @Override
    public MemoryDomainExperienceQueryResponse searchDomainExperiences(
            MemoryDomainExperienceQueryRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.searchDomainExperiences(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_SEARCH);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    @Override
    public MemoryDomainExperienceUseCheckResponse checkDomainExperienceUse(
            MemoryDomainExperienceUseCheckRequest request, MemoryRequestProof proof) {
        if (experiences != null) return experiences.checkDomainExperienceUse(request, proof);
        security.authorize(request, proof, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_CHECK);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }
}
