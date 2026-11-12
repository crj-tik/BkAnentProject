package com.bkanent.common.rpc;

import com.bkanent.common.security.ServiceIdentityCrypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Protocol-separated Ed25519 signature and body hash for notification system permits. */
public final class AuthorizedNotificationPermitSignature {
    private static final String DOMAIN = "bkanent:notification-permit:v1";

    private AuthorizedNotificationPermitSignature() { }

    public static AuthorizedNotificationPermit sign(AuthorizedNotificationPermit permit, PrivateKey key) {
        requireBodyHash(permit);
        byte[] signature = ServiceIdentityCrypto.sign(DOMAIN, ServiceIdentityCrypto.ED25519,
                canonical(permit), key);
        return copy(permit, ServiceIdentityCrypto.encodeSignature(signature));
    }

    public static void verify(AuthorizedNotificationPermit permit, PublicKey key) {
        requireBodyHash(permit);
        if (permit.signature() == null || !ServiceIdentityCrypto.verify(DOMAIN, ServiceIdentityCrypto.ED25519,
                canonical(permit), ServiceIdentityCrypto.decodeSignature(permit.signature()), key)) {
            throw new IllegalArgumentException("Notification permit signature is invalid");
        }
    }

    public static String payloadHash(String sceneCode, String title, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, sceneCode);
                write(output, title);
                write(output, content);
            }
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Notification permit hash failed", exception);
        }
    }

    private static void requireBodyHash(AuthorizedNotificationPermit permit) {
        if (permit == null || !payloadHash(permit.sceneCode(), permit.title(), permit.content()).equals(permit.payloadHash())) {
            throw new IllegalArgumentException("Notification permit payload hash does not match its body");
        }
    }

    private static byte[] canonical(AuthorizedNotificationPermit permit) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                write(output, permit.callerService());
                write(output, permit.keyId());
                write(output, permit.operationCode());
                output.writeLong(permit.recipientUserId());
                write(output, permit.sceneCode());
                write(output, permit.title());
                write(output, permit.content());
                write(output, permit.idempotencyKey());
                output.writeLong(permit.issuedAtEpochSecond());
                output.writeLong(permit.expiresAtEpochSecond());
                write(output, permit.payloadHash());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Notification permit canonicalization failed", exception);
        }
    }

    private static AuthorizedNotificationPermit copy(AuthorizedNotificationPermit source, String signature) {
        return new AuthorizedNotificationPermit(source.callerService(), source.keyId(), source.operationCode(),
                source.recipientUserId(), source.sceneCode(), source.title(), source.content(),
                source.idempotencyKey(), source.issuedAtEpochSecond(), source.expiresAtEpochSecond(),
                source.payloadHash(), signature);
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
