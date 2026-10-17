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
    int CONTRACT_VERSION = MemoryV2Contract.VERSION;

    MemoryWorkingSetResponse readWorkingSet(MemoryWorkingSetRequest request) throws MemoryException;

    MemoryReceivePermitResponse requestReceivePermit(MemoryReceivePermitRequest request) throws MemoryException;

    MemoryDeliveryResponse deliverMessages(MemoryDeliveryRequest request) throws MemoryException;

    MemorySummaryResponse submitSummary(MemorySummaryRequest request) throws MemoryException;

    MemoryMatterQueryResponse queryMatters(MemoryMatterQueryRequest request) throws MemoryException;

    MemoryEntityQueryResponse queryEntities(MemoryEntityQueryRequest request) throws MemoryException;

    MemoryPreferenceQueryResponse searchPreferences(MemoryPreferenceQueryRequest request) throws MemoryException;

    MemoryExperienceQueryResponse searchExperiences(MemoryExperienceQueryRequest request) throws MemoryException;

    MemoryGrantResponse getGrant(MemoryGrantRequest request) throws MemoryException;
}
