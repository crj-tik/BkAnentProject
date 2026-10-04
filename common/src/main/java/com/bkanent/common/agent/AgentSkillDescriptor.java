package com.bkanent.common.agent;

import java.util.List;

/** Published skill facts; a Card ID does not imply a local executable skill name. */
public record AgentSkillDescriptor(String id, String name, String description, List<String> tags,
                                   List<String> examples, List<String> inputModes, List<String> outputModes) {
    public AgentSkillDescriptor {
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        inputModes = inputModes == null ? List.of() : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of() : List.copyOf(outputModes);
    }
}
