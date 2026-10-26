package com.bkanent.notification.rpc;

import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 通知消息 RPC 实现。
 */
@DubboService
public class NotificationRpcServiceImpl implements NotificationRpcService {

    @Override
    public void sendStationMessage(Long userId, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void sendEmailMessage(String receiverAddress, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void sendRobotMessage(String channel, String webhookUrl, String title, String content) {
        throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }
}
