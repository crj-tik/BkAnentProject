package com.bkanent.notification.service;

import com.bkanent.common.rpc.NotificationRpcService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class NotificationRpcServiceImpl implements NotificationRpcService {

    @Override
    public void sendStationMessage(Long userId, String title, String content) {
        System.out.printf("notify user=%s title=%s%n", userId, title);
    }
}
