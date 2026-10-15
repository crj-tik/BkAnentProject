package com.bkanent.agent.studio;

import com.bkanent.agent.registry.AgentRegistry;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class StudioCapabilityService {
    private final AgentRegistry registry;
    public StudioCapabilityService(AgentRegistry registry) { this.registry = registry; }
    public List<Map<String, Object>> list(boolean administrator) {
        Set<String> domains = new HashSet<>();
        registry.listDescriptors().forEach(descriptor -> {
            if (descriptor.agentCard() != null && descriptor.agentCard().supportedDomains() != null)
                descriptor.agentCard().supportedDomains().forEach(domain -> domains.add(domain.toLowerCase(Locale.ROOT)));
        });
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(capability("listings", "房源与客户匹配", "查找房源、比较差异，围绕真实需求做选择。", "找房与客户", "/tools/listings", domains.contains("listing"), "客户需求、区域与预算"));
        result.add(capability("customers", "客户与跟进", "核对需求、跟进、收藏和业主委托。", "找房与客户", "/tools/customers", false, "客户档案与业务经纪人"));
        result.add(capability("marketing", "营销内容", "基于真实房源创作、编辑和保存内容。", "内容与推广", "/tools/marketing", domains.contains("marketing"), "房源、平台与内容要求"));
        result.add(capability("media", "媒体与素材", "提交生成要求，跟踪真实任务和输出。", "内容与推广", "/tools/media", domains.contains("media"), "素材、生成类型与要求"));
        result.add(capability("contracts", "合同与办理", "区分办理状态与有原文证据的条款审查。", "交易与结算", "/tools/contracts", domains.contains("contract"), "合同及已授权附件"));
        result.add(capability("settlements", "佣金与结算", "核对规则、结算明细和选定归集范围。", "交易与结算", "/tools/settlements", domains.contains("settlement"), "月份、结算记录与范围"));
        result.add(capability("business", "经营分析", "查看经营周期、门店指标和实际明细。", "经营与知识", "/business", domains.contains("business"), "周期、组织与指标口径"));
        result.add(capability("interviews", "访谈与复盘", "准备提纲、确认问题并整理复盘资产。", "经营与知识", "/interviews", domains.contains("interview"), "场景、角色与目标"));
        result.add(capability("knowledge", "知识与工作上下文", "在授权资料中检索并核对引用。", "经营与知识", "/tools/knowledge", false, "问题与资料范围"));
        result.add(capability("notifications", "消息与提醒", "核对站内消息、收件人与真实渠道状态。", "协作与管理", "/tools/notifications", false, "收件人与内容"));
        var promotions = capability("promotions", "发布与推广", "准备精确版本的内容计划，核对渠道与效果来源。", "内容与推广", "/tools/promotions", false, "正文版本、账号与素材");
        promotions.put("availability", "NOT_CONFIGURED"); promotions.put("reason", "尚未配置真实外部发布渠道；模拟效果不代表真实采集"); result.add(promotions);
        if (administrator) result.add(capability("admin", "账号与运行管理", "管理账号，查看已接入能力和运行状态。", "协作与管理", "/users", true, "管理权限"));
        return result;
    }
    private Map<String, Object> capability(String id, String name, String description, String category, String path, boolean discovered, String inputs) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("id", id); value.put("name", name); value.put("description", description); value.put("category", category); value.put("professionalPath", path);
        value.put("availability", discovered ? "AVAILABLE" : "UNKNOWN"); value.put("reason", discovered ? "已发现领域能力，执行时以实际依赖状态为准" : "数据操作面可尝试连接，运行状态以实际接口响应为准");
        value.put("inputRequirements", List.of(inputs)); value.put("outputTypes", List.of()); value.put("directOperations", List.of());
        value.put("goalExample", "请帮我完成" + name + "相关工作，先核对" + inputs + "。"); return value;
    }
}
