package com.bkanent.agent.orchestration;

/** Waiting is a suspension of dispatch, never a terminal business result. */
public final class AsyncRunStatus {
    private AsyncRunStatus() {}
    public static boolean terminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status);
    }
    public static boolean suspended(String status) {
        return "WAITING_USER_INPUT".equalsIgnoreCase(status) || "WAITING_USER_APPROVAL".equalsIgnoreCase(status);
    }
    public static boolean stopStatusStream(String status) { return terminal(status) || suspended(status); }
}
