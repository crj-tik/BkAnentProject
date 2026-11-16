package com.bkanent.notification.controller;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.notification.mapper.NotificationEventConsumeMapper;
import com.bkanent.notification.mapper.NotificationRecordMapper;
import com.bkanent.notification.rpc.NotificationRpcServiceImpl;
import com.bkanent.notification.service.NotificationManagementService;
import com.bkanent.notification.service.NotificationWorkflowEventQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationController.class, properties = {
        "notification.authorization.enabled=false", "dubbo.enabled=false",
        "spring.config.location=classpath:/notification-web-test.yml",
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
class NotificationControllerAuthorizationTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private NotificationManagementService management;
    @MockitoBean private NotificationWorkflowEventQueryService events;
    @MockitoBean private NotificationRecordMapper records;
    @MockitoBean private NotificationEventConsumeMapper consumes;
    @MockitoBean private NotificationRpcServiceImpl rpc;

    @Test
    void rejectedRecipientSubstitutionReturnsForbiddenInsteadOfServerError() throws Exception {
        when(management.listUserMessages(99L, null, null))
                .thenThrow(new AuthzException(AuthzErrorCode.PERMISSION_DENIED));
        mvc.perform(get("/notifications").param("userId", "99"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }
}
