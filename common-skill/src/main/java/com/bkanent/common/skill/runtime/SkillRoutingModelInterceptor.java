package com.bkanent.common.skill.runtime;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能路由拦截器：在每次模型调用前完成技能状态判定与请求换装。
 *
 * <p>无状态设计——技能激活态通过扫描消息历史中最近一次 {@code skill}
 * 伪工具调用判定（最新生效，即同执行内的技能切换语义），不依赖任何
 * 跨调用的会话存储，天然兼容 A2A 分布式调用。</p>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>技能已激活：系统提示词叠加技能正文与任务锚点，工具面收窄到
 *       技能声明的工具集合（保留 skill 伪工具本身以支持中途切换）；
 *       技能未声明工具集合时保留全量工具。</li>
 *   <li>技能未激活：系统提示词叠加轻量技能目录（场景导向 description），
 *       工具面不变，由模型自主决定是否调用 skill 伪工具。</li>
 * </ul>
 *
 * <p>挂载顺序建议：放在 Supervisor 上下文拦截器之后
 * （{@code .interceptors(a2aSupervisorContext, skillRoutingModel)}），
 * 使技能内容叠加在 Supervisor 上下文之后。</p>
 */
public final class SkillRoutingModelInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutingModelInterceptor.class);

    private final SkillRegistry registry;
    private final String domain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillRoutingModelInterceptor(SkillRegistry registry, String domain) {
        this.registry = registry;
        this.domain = domain;
    }

    @Override
    public String getName() {
        return "skill-routing";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String activeSkillName = findLastActivatedSkill(request.getMessages());

        if (activeSkillName != null) {
            SkillDefinition skill = registry.getByName(activeSkillName);
            if (skill == null) {
                // 技能在执行途中被删除（如外部目录热加载），退回默认路径
                log.warn("Activated skill '{}' no longer registered, falling back to default path",
                        activeSkillName);
                return handler.call(request);
            }
            return handler.call(withSkill(request, skill, activeSkillName));
        }

        String catalog = buildCatalogPrompt(supervisorHint(request));
        if (catalog.isBlank()) {
            return handler.call(request);
        }
        SystemMessage enhanced = appendToSystem(request.getSystemMessage(), catalog);
        return handler.call(ModelRequest.builder(request)
                .systemMessage(enhanced)
                .build());
    }

    /**
     * 读取 Supervisor 元数据中的建议性技能提示（skillHint，位于 supervisor 命名空间）。
     */
    private String supervisorHint(ModelRequest request) {
        Map<String, Object> context = request.getContext();
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object supervisor = context.get("supervisor");
        if (supervisor instanceof Map<?, ?> supervisorMap) {
            Object hint = supervisorMap.get("skillHint");
            return hint == null ? null : hint.toString();
        }
        return null;
    }

    /**
     * 扫描消息历史，返回最近一次 skill 伪工具调用选择的技能名；无则返回 null。
     */
    private String findLastActivatedSkill(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String last = null;
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistantMessage)
                    || !assistantMessage.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                if (!SkillTool.TOOL_NAME.equals(toolCall.name())) {
                    continue;
                }
                String name = extractSkillName(toolCall.arguments());
                if (name != null && !name.isBlank()) {
                    last = name;
                }
            }
        }
        return last;
    }

    private String extractSkillName(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(arguments, Map.class);
            Object name = parsed.get("name");
            return name == null ? null : name.toString();
        } catch (Exception e) {
            log.debug("Failed to parse skill tool call arguments: {}", arguments);
            return null;
        }
    }

    private ModelRequest withSkill(ModelRequest request, SkillDefinition skill, String taskAnchor) {
        String skillBlock = "\n\n[当前技能: " + skill.name() + " 生效中]\n"
                + skill.systemPrompt()
                + "\n\n[任务] 请继续围绕技能激活时声明的任务执行。";

        SystemMessage enhanced = appendToSystem(request.getSystemMessage(), skillBlock);
        List<String> narrowedTools = narrowTools(request.getTools(), skill);

        log.info("Skill '{}' active — tools {} -> {}",
                skill.name(),
                request.getTools() == null ? 0 : request.getTools().size(),
                narrowedTools.size());

        return ModelRequest.builder(request)
                .systemMessage(enhanced)
                .tools(narrowedTools)
                .build();
    }

    /**
     * 将工具面收窄到技能声明的工具集合，保留 skill 伪工具以支持中途切换。
     * 技能未声明工具集合（空）时保留全量；声明的工具名与工具面无交集时
     * 告警并保留全量（容错）。
     */
    private List<String> narrowTools(List<String> requestTools, SkillDefinition skill) {
        if (requestTools == null || requestTools.isEmpty()) {
            return requestTools;
        }
        List<String> allowed = skill.tools();
        if (allowed == null || allowed.isEmpty()) {
            return requestTools;
        }

        List<String> narrowed = requestTools.stream()
                .filter(name -> allowed.contains(name) || SkillTool.TOOL_NAME.equals(name))
                .toList();

        if (narrowed.stream().noneMatch(name -> !SkillTool.TOOL_NAME.equals(name))) {
            log.warn("Skill '{}' tools {} do not match any available tool — keeping all {} tools",
                    skill.name(), allowed, requestTools.size());
            return requestTools;
        }
        return narrowed;
    }

    /**
     * 构建轻量技能目录（名称 + 场景导向描述），注入未激活技能时的系统提示词。
     * Supervisor 建议的技能置顶并标记（仅建议，不改变选择权）；建议技能不存在于
     * 本领域时静默忽略。
     */
    private String buildCatalogPrompt(String hint) {
        List<SkillDefinition> skills = new java.util.ArrayList<>(
                registry.findOperationalSkills(domain));
        if (skills.isEmpty()) {
            return "";
        }
        skills.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        SkillDefinition hinted = null;
        if (hint != null && !hint.isBlank()) {
            hinted = skills.stream()
                    .filter(skill -> skill.name().equals(hint))
                    .findFirst().orElse(null);
        }
        if (hinted != null) {
            skills.remove(hinted);
            skills.add(0, hinted);
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("\n\n## 可用技能\n");
        sb.append("当任务匹配以下技能的适用场景时，调用 skill 工具（name 传技能名，task 传要完成的任务）以加载专项指引与工具：\n");
        for (SkillDefinition skill : skills) {
            String marker = skill == hinted ? "（Supervisor 建议）" : "";
            sb.append("- **").append(skill.name()).append("**").append(marker)
                    .append(": ").append(skill.description()).append("\n");
        }
        return sb.toString();
    }

    private SystemMessage appendToSystem(SystemMessage existing, String addition) {
        String base = existing == null ? "" : existing.getText();
        return new SystemMessage(base + addition);
    }
}
