package com.bkanent.agent.service;

import com.bkanent.agent.mcp.model.AgentToolCatalogItem;

import java.util.List;

/**
 * AgentToolCatalogService 服务类。
 */
public interface AgentToolCatalogService {

    List<AgentToolCatalogItem> listCatalog();
}
