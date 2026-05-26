package com.bkanent.common.agent;

public final class SessionEventTags {

    public static final String APPROVAL = "approval";
    public static final String HANDOFF = "handoff";
    public static final String ARTIFACT = "artifact";
    public static final String TASK_STATUS = "task_status";
    public static final String PUBLISH = "publish";
    public static final String NOTIFICATION = "notification";
    public static final String GENERAL = "general";

    private SessionEventTags() {
    }

    public static String resolveTag(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return GENERAL;
        }
        if (eventType.startsWith("task.approval") || "task.waiting_approval".equals(eventType)) {
            return APPROVAL;
        }
        if (eventType.startsWith("handoff.")) {
            return HANDOFF;
        }
        if (eventType.startsWith("artifact.")) {
            return ARTIFACT;
        }
        if (eventType.startsWith("publish.")) {
            return PUBLISH;
        }
        if (eventType.startsWith("notification.")) {
            return NOTIFICATION;
        }
        if (eventType.startsWith("task.")) {
            return TASK_STATUS;
        }
        return GENERAL;
    }
}
