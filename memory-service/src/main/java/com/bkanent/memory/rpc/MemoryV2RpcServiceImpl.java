package com.bkanent.memory.rpc;

import com.bkanent.common.memory.*;
import com.bkanent.memory.service.MemoryArchiveService;
import com.bkanent.memory.service.MemorySharingAccessService;
import com.bkanent.memory.service.MemoryV2RequestSecurity;
import org.apache.dubbo.config.annotation.DubboService;
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

    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security) {
        this(archive, sharing, security, null);
    }

    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                  com.bkanent.memory.service.PreferenceHistoryService preferences) {
        this(archive,sharing,security,preferences,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryV2RpcServiceImpl(MemoryArchiveService archive, MemorySharingAccessService sharing, MemoryV2RequestSecurity security,
                                  com.bkanent.memory.service.PreferenceHistoryService preferences, com.bkanent.memory.service.MemoryMatterService matters) {
        this.archive = archive;
        this.sharing = sharing;
        this.security = security;
        this.preferences = preferences;
        this.matters = matters;
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
        security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }
}
