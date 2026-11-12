package com.bkanent.customer.service;

import com.bkanent.common.rpc.AuthorizedNotificationPermit;
import com.bkanent.common.rpc.AuthorizedNotificationPermitSignature;
import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.customer.config.CustomerReminderProperties;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;

/** Sends only short-lived, service-signed customer reminder permits to Notification. */
@Component
public class CustomerNotificationPermitClient {
    private final CustomerReminderProperties properties;
    private volatile PrivateKey cachedKey;

    @DubboReference(check = false, timeout = 3000, retries = 0)
    private NotificationRpcService notificationRpcService;

    public CustomerNotificationPermitClient(CustomerReminderProperties properties) {
        this.properties = properties;
    }

    public void sendEntrustReminder(long recipientUserId, String title, String content, String idempotencyKey) {
        Instant now = Instant.now();
        String scene = AuthorizedNotificationPermit.CUSTOMER_ENTRUST_EXPIRING_SCENE;
        var unsigned = new AuthorizedNotificationPermit("customer-service", properties.getKeyId(),
                AuthorizedNotificationPermit.CUSTOMER_ENTRUST_REMINDER_OPERATION, recipientUserId, scene,
                title, content, idempotencyKey, now.getEpochSecond(), now.plusSeconds(30).getEpochSecond(),
                AuthorizedNotificationPermitSignature.payloadHash(scene, title, content), null);
        notificationRpcService.sendAuthorizedReminder(AuthorizedNotificationPermitSignature.sign(unsigned, key()));
    }

    private PrivateKey key() {
        PrivateKey current = cachedKey;
        if (current != null) return current;
        synchronized (this) {
            if (cachedKey == null) cachedKey = ServiceIdentityCrypto.loadPrivateKey(Path.of(properties.getPrivateKeyFile()));
            return cachedKey;
        }
    }
}
