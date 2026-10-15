package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.rag.index.RagIndexService;
import com.bkanent.rag.security.RagAccessControl;
import com.bkanent.rag.storage.ProjectionFilters;
import org.apache.dubbo.config.annotation.DubboService;

// fastjson2ctor pin (KI-113 follow-up): Dubbo's built-in fastjson2 writes FieldBased
// JSONB which crashes decoding record list fields; the ctor SPI keeps the strict typed
// contract without class-name tags. See Fastjson2ConstructorSerializationTest.
@DubboService(parameters = {"serialization", "fastjson2ctor"})
public class RagIndexRpcServiceImpl implements RagIndexRpcService {
    private final RagAccessControl access;
    private final RagIndexService index;
    public RagIndexRpcServiceImpl(RagAccessControl access, RagIndexService index) { this.access = access; this.index = index; }
    @Override public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope authentication) {
        if (event == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "event");
        var space = access.submit(event, authentication);
        if (!space.schemaVersion().equals(event.schemaVersion())) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "schemaVersion");
        if (event.operation() == RagIndexOperation.UPSERT) ProjectionFilters.validateDocument(space, event.document());
        return index.submit(event);
    }
    @Override public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope authentication) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        access.status(request, authentication);
        return index.getStatus(request);
    }
}
