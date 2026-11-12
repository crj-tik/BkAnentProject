package com.bkanent.common.rpc;

/** A narrowly scoped, caller-signed permit for a background in-app reminder. */
public record AuthorizedNotificationPermit(
        String callerService,
        String keyId,
        String operationCode,
        long recipientUserId,
        String sceneCode,
        String title,
        String content,
        String idempotencyKey,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        String payloadHash,
        String signature) {

    public static final String CUSTOMER_ENTRUST_REMINDER_OPERATION = "notification.message.send.entrust-reminder";
    public static final String CUSTOMER_ENTRUST_EXPIRING_SCENE = "CUSTOMER_ENTRUST_EXPIRING";

    public AuthorizedNotificationPermit {
        if (callerService == null || !callerService.matches("[a-z][a-z0-9-]{0,63}")
                || keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,96}")
                || !CUSTOMER_ENTRUST_REMINDER_OPERATION.equals(operationCode)
                || recipientUserId <= 0
                || !CUSTOMER_ENTRUST_EXPIRING_SCENE.equals(sceneCode)
                || title == null || title.isBlank() || title.length() > 128
                || content == null || content.isBlank() || content.length() > 2048
                || idempotencyKey == null || !idempotencyKey.startsWith("customer-entrust-expiring:")
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")
                || issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond
                || payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")
                || signature != null && !signature.matches("[A-Za-z0-9_-]{86}")) {
            throw new IllegalArgumentException("Invalid authorized notification permit");
        }
    }
}
