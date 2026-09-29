package com.bkanent.agent.catalog;

import com.bkanent.common.agent.AgentCard;

import java.util.List;
import java.util.Set;

/**
 * DomainCatalog 领域目录：Supervisor 的领域词表唯一派生点。
 *
 * <p>词表来源于 Agent 注册表（Nacos 动态注册），并带有冷启动兜底语义：
 * 注册表首次成功刷新（返回至少一个 Agent）之前使用
 * {@code agent.distributed.catalog.cold-start-fallback-domains}，
 * 之后由注册表词表完全接管，兜底词表不再参与合并。</p>
 *
 * <p>规划 prompt、计划校验、并行图路由、规则路由都必须从这里取词表，
 * 不允许再维护独立的静态领域名单。</p>
 */
public interface DomainCatalog {

    String SOURCE_COLD_START_FALLBACK = "COLD_START_FALLBACK";
    String SOURCE_REGISTRY = "REGISTRY";

    /**
     * 当前生效的领域词表，字典序稳定排序。
     */
    Set<String> domains();

    default boolean contains(String domain) {
        return domain != null && domains().contains(domain);
    }

    /**
     * 按域解析默认 intent。解析顺序：注册元数据 agent-default-intent →
     * 配置 catalog.default-intents 种子表 → 该域 Agent Card supportedSkills 首项。
     * 都无法解析时返回 null，由调用方决定报错策略。
     */
    String resolveDefaultIntent(String domain);

    /**
     * nextHint 改写：应用配置 catalog.hint-rewrites，无命中时原样返回。
     */
    String rewriteHint(String nextHint);

    /**
     * 当前注册表中的 Agent Card 列表（与快照同源）。
     */
    List<AgentCard> cards();

    /**
     * 并行图分支槽位容量（编译期拓扑配置）。
     */
    int branchCapacity();

    /**
     * 当前目录快照：词表 + Agent Card + 词表来源标记。
     * 同一次规划/校验应使用同一份快照，避免请求内词表不一致。
     */
    CatalogSnapshot snapshot();

    record CatalogSnapshot(
            List<String> domains,
            List<AgentCard> cards,
            String vocabularySource
    ) {
    }
}
