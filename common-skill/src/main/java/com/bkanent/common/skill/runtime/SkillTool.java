package com.bkanent.common.skill.runtime;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能伪工具：模型通过调用它"选择并激活"一个技能。
 *
 * <p>这不是一个执行业务的工具，而是模型表达技能选择决策的结构化通道。
 * 调用被受理后，工具结果返回该技能的执行指引（SKILL.md 正文），随后的
 * 提示词换装与工具收窄由 {@link SkillRoutingModelInterceptor} 完成。</p>
 *
 * <h3>参数契约</h3>
 * <ul>
 *   <li>{@code name} — 必填，取值限于运行时生成的技能 enum（随注册表热加载更新）</li>
 *   <li>{@code task} — 必填，模型对"本次在技能下要完成什么"的重述（换装后的注意力锚点）</li>
 *   <li>{@code context} — 可选对象，带入已确定的中间数据，避免重复推导</li>
 * </ul>
 *
 * <p>技能本身不接收参数：name 供编排层查表，task/context 是模型写给
 * 换装后自己的交接便条。同一执行内再次调用解释为切换技能（最新生效）。</p>
 */
public final class SkillTool implements ToolCallback {

    public static final String TOOL_NAME = "skill";

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    private final SkillRegistry registry;
    private final String domain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillTool(SkillRegistry registry, String domain) {
        this.registry = registry;
        this.domain = domain;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("加载专项技能。当任务匹配某技能的适用场景时调用，调用后按该技能的指引和工具集继续执行。"
                        + "不匹配任何技能时无需调用。")
                .inputSchema(buildInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = parseArguments(toolInput);
        if (args == null) {
            return "技能调用参数错误：无法解析调用参数，请以 JSON 对象重试（字段：name、task）。";
        }

        String name = stringValue(args.get("name"));
        String task = stringValue(args.get("task"));
        if (name.isBlank()) {
            return "技能调用参数错误：name 不能为空。当前可用技能：" + catalogNames();
        }
        if (task.isBlank()) {
            return "技能调用参数错误：task 不能为空，请重述在本技能下要完成的具体任务后重试。";
        }

        SkillDefinition skill = registry.getByName(name);
        if (skill == null || skill.supervisorSkill()
                || !skill.domain().equals(domain)) {
            log.warn("Skill '{}' requested but not available in domain '{}'", name, domain);
            return "技能调用失败：未找到技能 '" + name + "'。当前可用技能：" + catalogNames()
                    + "。请修正后重试，或不使用技能直接执行。";
        }

        log.info("Skill '{}' activated (task={})", name, task);
        return renderActivation(skill, task);
    }

    private String renderActivation(SkillDefinition skill, String task) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("技能 ").append(skill.name()).append(" 已激活。\n\n");
        sb.append("[执行指引]\n").append(skill.systemPrompt()).append("\n\n");
        if (skill.tools().isEmpty()) {
            sb.append("[本技能可用工具] 未限定，保留全量工具\n\n");
        } else {
            sb.append("[本技能可用工具] ").append(String.join(", ", skill.tools())).append("\n\n");
        }
        sb.append("[任务] ").append(task).append("\n\n");
        sb.append("请按指引继续执行。");
        return sb.toString();
    }

    /**
     * 生成 skill 伪工具的 JSON Schema，enum 由注册表运行时生成。
     */
    private String buildInputSchema() {
        List<String> names = registry.findOperationalSkills(domain).stream()
                .map(SkillDefinition::name)
                .toList();

        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        if (names.isEmpty()) {
            name.put("description", "技能名称（当前无可选技能，无需调用本工具）");
        } else {
            name.put("enum", names);
            name.put("description", "技能名称，只能从可用技能清单中选择");
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "在本技能下要完成的具体任务：对用户请求的提炼，含关键约束和已确定的中间结论");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "object");
        context.put("description", "可选。需要带入技能的关键中间数据（如已查到的ID列表、已排除的选项），避免重复推导");
        context.put("additionalProperties", true);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("task", task);
        properties.put("context", context);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "task"));

        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build skill tool schema", e);
        }
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("Failed to parse skill tool arguments: {}", toolInput);
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String catalogNames() {
        List<String> names = new ArrayList<>(registry.findOperationalSkills(domain).stream()
                .map(SkillDefinition::name)
                .toList());
        return names.isEmpty() ? "（无）" : String.join("、", names);
    }
}
