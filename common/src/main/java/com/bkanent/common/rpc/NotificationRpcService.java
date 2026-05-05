package com.bkanent.common.rpc;

public interface NotificationRpcService {

    void sendStationMessage(Long userId, String title, String content);
}
