package com.bkanent.common.memory;

import java.io.Serializable;

/** Canonical logical scope, not ACL. Private scope always includes both domain and agent. */
public record MemoryScope(Kind kind, String domainId, String agentId, String sharedScopeId) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Kind { DOMAIN_PRIVATE, TASK_SHARED, SESSION_SHARED }
    public MemoryScope {
        MemoryContract.required(kind, MemoryException.Field.SCOPE);
        if (kind == Kind.DOMAIN_PRIVATE) {
            domainId = MemoryContract.id(domainId, MemoryException.Field.SCOPE);
            agentId = MemoryContract.id(agentId, MemoryException.Field.SCOPE);
            MemoryContract.require(sharedScopeId == null, MemoryException.Field.SCOPE);
        } else {
            sharedScopeId = MemoryContract.id(sharedScopeId, MemoryException.Field.SCOPE);
            MemoryContract.require(domainId == null && agentId == null, MemoryException.Field.SCOPE);
        }
    }
    public static MemoryScope privateScope(String domainId, String agentId) {
        return new MemoryScope(Kind.DOMAIN_PRIVATE, domainId, agentId, null);
    }
    /** Length-framed identity prevents separator collisions; it is not a physical collection name. */
    public String canonicalScopeId() {
        return kind.name() + ":" + (kind == Kind.DOMAIN_PRIVATE ? segment(domainId) + segment(agentId) : segment(sharedScopeId));
    }
    private static String segment(String value) { return value.length() + ":" + value; }
}
