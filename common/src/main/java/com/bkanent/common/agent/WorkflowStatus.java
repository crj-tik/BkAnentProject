package com.bkanent.common.agent;

/**
 * WorkflowStatus 表示工作流状态。
 */
public enum WorkflowStatus {
    RUNNING,
    WAITING_USER_APPROVAL,
    WAITING_USER_INPUT,
    COMPLETED,
    FAILED,
    CANCELED
}
