package com.bkanent.notification.service;

import com.bkanent.notification.model.NotificationWorkflowEventConsumeResponse;

import java.util.List;

public interface NotificationWorkflowEventQueryService {

    List<NotificationWorkflowEventConsumeResponse> listRecent(String consumeStatus,
                                                             String eventType,
                                                             String taskId,
                                                             Long recipientUserId,
                                                             Integer limit);
}
