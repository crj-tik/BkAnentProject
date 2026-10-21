package com.bkanent.common.memory;

/**
 * Native memory v2 Dubbo contract (group/version fixed in MemoryV2Contract; explicit
 * consumer/provider binding happens in deployment configuration, task 2.4). Every method
 * declares throws so the official fastjson2 serialization registers the MemoryException
 * envelope for this interface; other throwables stay rejected. The server validates the
 * envelope's current-user identity and signature — the shape checks here only pin the
 * contract surface.
 */
public interface MemoryV2RpcService {
    default MemoryMatterWriteResponse transitionMatter(MemoryMatterWriteRequest request, MemoryRequestProof proof) throws MemoryException {
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }
    int CONTRACT_VERSION = MemoryV2Contract.VERSION;

    MemoryWorkingSetResponse readWorkingSet(MemoryWorkingSetRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryReceivePermitResponse requestReceivePermit(MemoryReceivePermitRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryDeliveryResponse deliverMessages(MemoryDeliveryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemorySummaryResponse submitSummary(MemorySummaryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryMatterQueryResponse queryMatters(MemoryMatterQueryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryEntityQueryResponse queryEntities(MemoryEntityQueryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryPreferenceQueryResponse searchPreferences(MemoryPreferenceQueryRequest request, MemoryRequestProof proof) throws MemoryException;

    default MemoryPreferenceAssemblyResponse assemblePreferences(MemoryPreferenceAssemblyRequest request, MemoryRequestProof proof) throws MemoryException {
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    MemoryExperienceQueryResponse searchExperiences(MemoryExperienceQueryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryGrantResponse getGrant(MemoryGrantRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryWithdrawalResponse withdrawSource(MemoryWithdrawalRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryPrivacyCleanupStatusResponse privacyCleanupStatus(MemoryPrivacyCleanupStatusRequest request,
                                                            MemoryRequestProof proof) throws MemoryException;

    MemoryWriterResponse manageWriter(MemoryWriterRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryBoundaryResponse checkBoundary(MemoryBoundaryRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryPreferenceWriteResponse submitPreferenceAssessment(MemoryPreferenceWriteRequest request, MemoryRequestProof proof) throws MemoryException;

    MemoryPreferenceDetailResponse readPreferenceHistory(MemoryPreferenceDetailRequest request, MemoryRequestProof proof) throws MemoryException;
}
