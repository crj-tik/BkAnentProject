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
        boolean platformOperationsDiscovered = domains.contains("platform-operations");
        result.add(capability("business", "经营分析", "查看经营周期、门店指标和实际明细。", "经营与知识", "/platform-operations/analytics", platformOperationsDiscovered, "周期、组织与指标口径"));
        result.add(capability("reviews", "门店复盘", "整理经营事项、诊断与计划，核对行动反馈和观察。", "经营与知识", "/platform-operations/reviews", platformOperationsDiscovered, "门店、事项与计划"));
        result.add(capability("interviews", "访谈", "准备访谈提纲、确认问题并整理访谈资产。", "经营与知识", "/interviews", domains.contains("interview"), "场景、角色与目标"));
        result.add(capability("knowledge", "知识与工作上下文", "在授权资料中检索并核对引用。", "经营与知识", "/platform-operations/knowledge", platformOperationsDiscovered, "问题与资料范围"));
        result.add(capability("notifications", "消息与提醒", "核对站内消息、收件人与真实渠道状态。", "协作与管理", "/tools/notifications", false, "收件人与内容"));
        var promotions = capability("promotions", "发布与推广", "准备精确版本的内容计划，核对渠道与效果来源。", "内容与推广", "/tools/promotions", false, "正文版本、账号与素材");
        promotions.put("availability", "NOT_CONFIGURED"); promotions.put("reason", "尚未配置真实外部发布渠道；模拟效果不代表真实采集"); result.add(promotions);
        if (administrator) {
            var admin = capability("admin", "账号与运行管理", "管理账号，查看已接入能力和运行状态。", "协作与管理", "/users", true, "管理权限");
            admin.put("authorization", Map.of("status", "AUTHORIZED", "checkMode", "PERMISSION_CHECKED", "permission", "auth.users.manage"));
            result.add(admin);
        }
        return result;
    }
    private Map<String, Object> capability(String id, String name, String description, String category, String path, boolean discovered, String inputs) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("id", id); value.put("name", name); value.put("description", description); value.put("category", category); value.put("professionalPath", path);
        value.put("availability", discovered ? "AVAILABLE" : "UNKNOWN"); value.put("reason", discovered ? "已在Agent目录发现对应能力；此状态不代表依赖实时健康或用户获准执行" : "尚未在Agent目录确认对应能力；实际状态以目标接口响应为准");
        value.put("authorization", Map.of("status", "NOT_EVALUATED", "checkMode", "PER_OPERATION", "reason", "具体用户权限和资源范围由目标业务接口逐请求校验"));
        value.put("inputRequirements", List.of(inputs)); value.put("outputTypes", List.of()); value.put("directOperations", List.of());
        value.put("goalExample", "请帮我完成" + name + "相关工作，先核对" + inputs + "。"); return value;
    }
}
