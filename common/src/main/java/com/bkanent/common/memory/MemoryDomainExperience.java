package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/**
 * Independently governed domain generalization. This public read model deliberately
 * contains no owner, private experience identity, source citation, or grant reference.
 */
public record MemoryDomainExperience(String aggregateId, String domainId, String agentId, long revision,
        String purpose, String capabilityTag, String scenario, String applicability, String conclusion,
        String evidenceSummary, String policyVersion, String contentHash, Instant validUntil,
        Instant publishedAt) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryDomainExperience {
        MemoryContract.require(aggregateId != null && aggregateId.matches("dge_[A-Za-z0-9._:%-]{1,120}"),
                MemoryException.Field.IDENTITY);
        domainId = MemoryContract.id(domainId, MemoryException.Field.SCOPE);
        agentId = MemoryContract.id(agentId, MemoryException.Field.SCOPE);
        MemoryContract.require(revision > 0, MemoryException.Field.VERSION);
        purpose = MemoryContract.id(purpose, MemoryException.Field.SCOPE);
        capabilityTag = MemoryContract.optionalId(capabilityTag, MemoryException.Field.SCOPE);
        scenario = MemoryContract.text(scenario, MemoryException.Field.TEXT);
        applicability = MemoryContract.text(applicability, MemoryException.Field.TEXT);
        conclusion = MemoryContract.text(conclusion, MemoryException.Field.TEXT);
        evidenceSummary = MemoryContract.text(evidenceSummary, MemoryException.Field.TEXT);
        policyVersion = MemoryContract.id(policyVersion, MemoryException.Field.VERSION);
        MemoryContract.require(contentHash != null && contentHash.matches("[0-9a-f]{64}"), MemoryException.Field.HASH);
        MemoryContract.required(validUntil, MemoryException.Field.EVIDENCE);
        MemoryContract.required(publishedAt, MemoryException.Field.EVIDENCE);
    }
}
