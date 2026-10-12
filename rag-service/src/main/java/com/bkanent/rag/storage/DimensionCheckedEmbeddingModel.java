package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

/** Delegates the original Spring AI model; validates actual vectors, not a configured dimension. */
final class DimensionCheckedEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final int dimension;
    DimensionCheckedEmbeddingModel(EmbeddingModel delegate, int dimension) {
        this.delegate = delegate;
        this.dimension = dimension;
    }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);
        if (response == null || response.getResults().size() != request.getInstructions().size()) mismatch();
        response.getResults().forEach(result -> check(result.getOutput()));
        return response;
    }
    @Override public float[] embed(Document document) { return check(delegate.embed(document)); }
    @Override public int dimensions() { return dimension; }
    private float[] check(float[] values) {
        if (values == null || values.length != dimension) mismatch();
        for (float value : values) if (!Float.isFinite(value)) mismatch();
        return values;
    }
    private static void mismatch() { throw new RagException(RagErrorCode.SPACE_NOT_READY, "embeddingDimension"); }
}
