package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.SkillMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.stream.Collectors.toMap;

/**
 * 子 Agent 技能支持组件（重构版）。
 *
 * <p>核心设计变更：不再预建多个 ReactAgent，改为：</p>
 * <ol>
 *   <li>将 skill 的 YAML 元数据（name + description）编译为"技能清单 prompt"，
 *       注入到 defaultAgent 的 system prompt 末尾。这样 LLM 知道有哪些技能可用的
 *       —— 但只是一份轻量目录，不包含每个技能的执行细节。</li>
 *   <li>请求到达时，先用关键词快速匹配。命中 → 动态构建一个轻量 ChatClient
 *      （注入该 skill 的完整 systemPrompt + 只加载其 tools）；</li>
 *   <li>未命中 → defaultAgent 自行处理（全量 tools + 含技能清单的 system prompt）。</li>
 * </ol>
 *
 * <p>ChatClient 是每次请求时即时构建的，不缓存。构建成本极低（只是包装引用），
 * ChatModel 是全服务共享的单例，不会重复创建。</p>
 *
 * <h3>与 Claude Code Skills 的对照</h3>
 * <pre>
 * Claude Code:
 *   system prompt 中有 "可用的技能: code-review, security-review, ..."（目录）
 *   → 匹配到 skill → 注入该 skill 的完整 prompt + 专属 tools
 *
 * 本组件:
 *   buildCatalogPrompt() → 注入 defaultAgent 的 system prompt（目录）
 *   → match(userMessage) → buildSkillClient(match) → 动态 ChatClient（完整 prompt + 过滤 tools）
 * </pre>
 */
public class SubAgentSkillSupport {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSkillSupport.class);

    private final String domain;
    private final ChatModel chatModel;
    private final Map<String, ToolCallback> toolIndex;
    private final SkillRegistry registry;
    private final SkillMatcher matcher;

    /**
     * @param domain      当前 Agent 的 domain（如 "trade", "compare", "marketing"）
     * @param chatModel   共享的 ChatModel 单例
     * @param allCallbacks 该 Agent 的全部 ToolCallback
     * @param registry    技能注册中心
     */
    public SubAgentSkillSupport(String domain,
                                 ChatModel chatModel,
                                 ToolCallback[] allCallbacks,
                                 SkillRegistry registry) {
        this.domain = domain;
        this.chatModel = chatModel;
        this.toolIndex = Arrays.stream(allCallbacks == null ? new ToolCallback[0] : allCallbacks)
                .collect(toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (a, b) -> a
                ));
        this.registry = registry;
        this.matcher = new SkillMatcher(registry);
    }

    // ──────────────────────────────────────────────
    // 技能清单 prompt（注入 defaultAgent 的 system prompt）
    // ──────────────────────────────────────────────

    /**
     * 构建轻量级技能清单 prompt。
     * 只包含每个 skill 的 name + description（YAML 部分），不包含执行细节。
     * 将此字符串追加到 defaultAgent 的 system prompt 末尾。
     *
     * @return 技能清单文本；如果没有注册任何操作技能则返回空字符串
     */
    public String buildCatalogPrompt() {
        List<SkillDefinition> skills = registry.findOperationalSkills(domain);
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("\n\n## 可用技能清单\n");
        sb.append("以下是可用的专项技能，每个技能有特定的工具集和执行流程：\n\n");
        sb.append("| 技能名称 | 适用场景 |\n");
        sb.append("|---------|--------|\n");

        for (SkillDefinition skill : skills) {
            sb.append("| **").append(skill.name()).append("** | ")
                    .append(skill.description()).append(" |\n");
        }

        sb.append("\n当用户请求精确匹配上述某个技能的适用场景时，你可以建议使用该技能以获得更精准的结果。\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────
    // 请求时匹配
    // ──────────────────────────────────────────────

    /**
     * 对用户消息进行技能匹配。
     */
    public SkillMatchResult match(String userMessage) {
        return matcher.match(userMessage, domain);
    }

    // ──────────────────────────────────────────────
    // 动态构建 ChatClient（替代预建 ReactAgent）
    // ──────────────────────────────────────────────

    /**
     * 为匹配到的 skill 动态构建一个 ChatClient。
     *
     * <p>ChatClient 内置了 tool calling 循环（等价于 ReAct loop），
     * 因此不需要 ReactAgent。每次请求即时构建，构建成本极低。</p>
     *
     * @param match 匹配结果
     * @return skill 专属的 ChatClient；如果未匹配到则返回 null（调用方应使用 defaultAgent）
     */
    public ChatClient buildSkillClient(SkillMatchResult match) {
        if (!match.isMatched()) {
            return null;
        }

        SkillDefinition skill = match.skill();
        ToolCallback[] tools = resolveTools(skill);

        log.info("Skill '{}' matched (score={}) — building ChatClient with {} tools",
                skill.name(), match.score(), tools.length);

        return ChatClient.builder(chatModel)
                .defaultSystem(skill.systemPrompt())
                .defaultTools(tools)
                .build();
    }

    /**
     * 解析当前匹配的 skill 的 system prompt；未匹配时返回默认 prompt。
     */
    public String resolveSystemPrompt(SkillMatchResult match, String defaultPrompt) {
        if (match.isMatched() && !match.skill().systemPrompt().isBlank()) {
            return match.skill().systemPrompt();
        }
        return defaultPrompt;
    }

    // ──────────────────────────────────────────────
    // 内部工具方法
    // ──────────────────────────────────────────────

    /**
     * 根据 skill 的 tools 列表过滤 ToolCallback。
     * 如果 skill.tools 为空，返回全量工具（表示不限制）。
     * 如果过滤后为空，返回全量工具（容错）。
     */
    private ToolCallback[] resolveTools(SkillDefinition skill) {
        if (skill.tools().isEmpty()) {
            return toolIndex.values().toArray(new ToolCallback[0]);
        }

        List<ToolCallback> filtered = skill.tools().stream()
                .map(toolIndex::get)
                .filter(Objects::nonNull)
                .toList();

        if (filtered.isEmpty()) {
            log.warn("Skill '{}' listed tools {} but none found in toolIndex — falling back to all {} tools",
                    skill.name(), skill.tools(), toolIndex.size());
            return toolIndex.values().toArray(new ToolCallback[0]);
        }

        return filtered.toArray(new ToolCallback[0]);
    }

    /**
     * 返回当前 domain 注册的操作技能数量。
     */
    public int skillCount() {
        return registry.findOperationalSkills(domain).size();
    }
}
