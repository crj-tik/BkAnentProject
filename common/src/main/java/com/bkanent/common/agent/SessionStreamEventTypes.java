package com.bkanent.common.agent;

public final class SessionStreamEventTypes {

    public static final String WORKFLOW_STARTED = "workflow.started";
    public static final String WORKFLOW_WAITING_APPROVAL = "workflow.waiting_approval";
    public static final String WORKFLOW_COMPLETED = "workflow.completed";
    public static final String WORKFLOW_FAILED = "workflow.failed";
    public static final String AGENT_STARTED = "agent.started";
    public static final String AGENT_DELTA = "agent.delta";
    public static final String AGENT_PROGRESS = "agent.progress";
    public static final String AGENT_TOOL_STARTED = "agent.tool.started";
    public static final String AGENT_TOOL_COMPLETED = "agent.tool.completed";
    public static final String AGENT_COMPLETED = "agent.completed";
    public static final String AGENT_FAILED = "agent.failed";
    public static final String HANDOFF_STARTED = "handoff.started";
    public static final String HANDOFF_COMPLETED = "handoff.completed";

    private SessionStreamEventTypes() {
    }

    public static boolean isTerminal(String eventType) {
        return WORKFLOW_COMPLETED.equals(eventType)
                || WORKFLOW_FAILED.equals(eventType)
                || AGENT_COMPLETED.equals(eventType)
                || AGENT_FAILED.equals(eventType)
                || (eventType != null && (eventType.endsWith(".completed")
                || eventType.endsWith(".failed")
                || eventType.endsWith(".cancelled")));
    }

    /**
     * Stable namespaces accepted by the Supervisor stream envelope. Existing
     * business events remain compatible while new child-agent events use the
     * agent.* lifecycle namespace.
     */
    public static boolean isAllowed(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        return eventType.startsWith("workflow.")
                || eventType.startsWith("agent.")
                || eventType.startsWith("handoff.")
                || eventType.startsWith("a2a.")
                || eventType.startsWith("supervisor.")
                || eventType.startsWith("approval.")
                || eventType.startsWith("artifact.")
                || eventType.startsWith("permission.")
                || eventType.startsWith("security.")
                || eventType.startsWith("graph.")
                || eventType.startsWith("task.");
    }

    public static String defaultPhase(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "generic";
        }
        if (eventType.startsWith("workflow.")) {
            return "workflow";
        }
        if (eventType.startsWith("handoff.")) {
            return "handoff";
        }
        if (eventType.startsWith("agent.")) {
            return "agent_execution";
        }
        if (eventType.startsWith("a2a.")) {
            return "a2a_execution";
        }
        if (eventType.startsWith("supervisor.")) {
            return "supervisor";
        }
        return eventType.substring(0, Math.max(1, eventType.indexOf('.') > 0
                ? eventType.indexOf('.') : eventType.length()));
    }
}
