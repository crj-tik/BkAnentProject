package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Scoped aliases and brief evidence; similarity is not entity identity or authorization. */
public record MemoryEntityAnchor(String entityId, MemoryContextKey contextKey, String matterId,
                                 MemoryRevision version, List<String> aliases, String briefDefinition,
                                 List<MemorySourceDependency> dependencies) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryEntityAnchor {
        entityId = MemoryContract.id(entityId, MemoryException.Field.SOURCE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        aliases = MemoryContract.unique(aliases, MemoryException.Field.SOURCE);
        aliases.forEach(alias -> MemoryContract.text(alias, MemoryException.Field.TEXT));
        briefDefinition = MemoryContract.text(briefDefinition, MemoryException.Field.TEXT);
        dependencies = MemoryContract.nonempty(dependencies, MemoryException.Field.EVIDENCE);
    }
}
