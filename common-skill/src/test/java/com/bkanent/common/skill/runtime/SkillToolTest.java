package com.bkanent.common.skill.runtime;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolTest {

    private final SkillRegistry registry = new SkillRegistry(new SkillFileLoader() {
        @Override
        public List<SkillDefinition> loadAll() {
            return List.of(
                    SkillDefinition.builder()
                            .name("contract-risk-review")
                            .description("审查合同风险")
                            .domain("contract")
                            .tools(List.of("getContractDetail", "reviewContractRisks"))
                            .systemPrompt("按步骤审查合同风险。")
                            .build(),
                    SkillDefinition.builder()
                            .name("other-domain-skill")
                            .description("其他领域")
                            .domain("trade")
                            .build());
        }
    }, "");

    private final SkillTool tool = new SkillTool(registry, "contract");

    @Test
    void activationResultCarriesBodyToolsAndTask() {
        String result = tool.call("{\"name\":\"contract-risk-review\",\"task\":\"审查合同101的风险\"}");

        assertThat(result)
                .contains("contract-risk-review 已激活")
                .contains("按步骤审查合同风险。")
                .contains("[本技能可用工具] getContractDetail, reviewContractRisks")
                .contains("[任务] 审查合同101的风险");
    }

    @Test
    void unknownSkillNameReturnsErrorWithCatalog() {
        String result = tool.call("{\"name\":\"not-exist\",\"task\":\"任务\"}");

        assertThat(result)
                .contains("未找到技能 'not-exist'")
                .contains("contract-risk-review");
    }

    @Test
    void crossDomainSkillRejected() {
        String result = tool.call("{\"name\":\"other-domain-skill\",\"task\":\"任务\"}");

        assertThat(result).contains("未找到技能 'other-domain-skill'");
    }

    @Test
    void blankTaskRejected() {
        String result = tool.call("{\"name\":\"contract-risk-review\",\"task\":\"\"}");

        assertThat(result).contains("task 不能为空");
    }

    @Test
    void blankNameRejected() {
        String result = tool.call("{\"task\":\"任务\"}");

        assertThat(result).contains("name 不能为空");
    }

    @Test
    void unparseableArgumentsReturnParamError() {
        String result = tool.call("not-json");

        assertThat(result).contains("无法解析调用参数");
    }

    @Test
    void inputSchemaContainsRuntimeEnumOfDomainSkills() throws Exception {
        String schema = tool.getToolDefinition().inputSchema();

        assertThat(schema)
                .contains("\"enum\"")
                .contains("contract-risk-review")
                .doesNotContain("other-domain-skill");
        assertThat(tool.getToolDefinition().name()).isEqualTo("skill");
    }
}
