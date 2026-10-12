package com.bkanent.rag.config;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagStage;
import com.bkanent.rag.storage.RagSpaceDefinition;

/** Explicit administrative diagnostics only; never invoked during application construction. */
public interface RagCapabilityProbe {
    RagStage stage();

    /** Must inspect the actual backend/schema/model/protocol, not a configured flag or TCP port. */
    void inspect(RagSpaceDefinition space);

    /** Only schema owners implement provisioning; requests contain no physical targets. */
    default void provision(RagSpaceDefinition space) {
        throw new RagException(RagErrorCode.FORBIDDEN);
    }
}
