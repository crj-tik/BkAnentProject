package com.bkanent.notification.rpc;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRpcServiceImplAuthorizationTest {
    private final NotificationRpcServiceImpl service = new NotificationRpcServiceImpl();

    @Test
    void legacyRpcMethodsWithoutRequestBoundRecipientProofAreRejected() {
        assertThatThrownBy(() -> service.sendStationMessage(73L, "title", "body"))
                .isInstanceOf(AuthzException.class)
                .extracting(error -> ((AuthzException) error).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.sendEmailMessage("user@example.test", "title", "body"))
                .isInstanceOf(AuthzException.class)
                .extracting(error -> ((AuthzException) error).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.sendRobotMessage("WECOM", "https://example.test/hook", "title", "body"))
                .isInstanceOf(AuthzException.class)
                .extracting(error -> ((AuthzException) error).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }
}
