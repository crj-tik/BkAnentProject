package com.bkanent.common.agent;

public enum OrchestrationMode {
    AUTO,
    EXPLICIT_SKILL;

    public static OrchestrationMode from(SkillSelection selection) {
        return selection == null ? AUTO : EXPLICIT_SKILL;
    }
}
