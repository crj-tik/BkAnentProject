package com.bkanent.agent.milvus.core;

import java.util.List;

/**
 * MilvusEmbeddingService 服务类。
 */
public interface MilvusEmbeddingService {

    List<Float> embed(String content);
}
