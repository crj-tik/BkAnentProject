package com.bkanent.common.authz;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * v1 explicit ordered, typed, length-prefixed UTF-8 fields; no map, JSON, reflection or toString.
 * Hashes and signature inputs have independent type tags. Wire changes require a protocol bump.
 */
public final class AuthzCanonicalHash {
    private AuthzCanonicalHash() { }

    /** Only stable authority facts: signature, algorithm, key rotation and cache TTL are excluded. */
    public static String snapshotHash(AuthorizationSnapshot snapshot) {
        AuthzContract.require(snapshot != null);
        return digest(encode("snapshot-content", snapshotFields(snapshot)));
    }

    /** Hash exact received/forwarded bytes before JSON parsing. Streaming/oversized bodies reject. */
    public static String bodyHash(byte[] body) {
        AuthzContract.require(body != null && body.length <= AuthzContract.MAX_BODY_BYTES);
        return digest(body);
    }

    public static String requestBindingHash(RequestBinding binding) {
        AuthzContract.require(binding != null);
        return digest(encode("request-binding", requestFields(binding)));
    }

    public static String delegationBindingHash(DelegationBinding binding) {
        AuthzContract.require(binding != null);
        return digest(encode("delegation-binding", delegationFields(binding)));
    }

    /** Binds a service proof to token possession without exposing the token in the proof. */
    public static String resolveRequestHash(String token, RequestBinding binding) {
        AuthzContract.text(token, 16384);
        AuthzContract.require(binding != null);
        String credentialDigest = digest(encode("credential", List.of(token)));
        return digest(encode("resolve-request", List.of(credentialDigest, requestFields(binding))));
    }

    /** The actual signed parent is covered, not just its globally shared snapshot hash. */
    public static String delegationRequestHash(AuthorizationContext parent, DelegationBinding binding) {
        AuthzContract.require(parent != null && binding != null && parent.signature() != null);
        return digest(encode("delegate-request", Arrays.asList(contextFields(parent), parent.algorithm(),
                parent.keyId(), parent.signature(), delegationFields(binding))));
    }

    static byte[] snapshotBytes(AuthorizationSnapshot value) {
        return signed("snapshot", snapshotFields(value), value.algorithm(), value.keyId());
    }

    static byte[] freshnessBytes(AuthzFreshness value) {
        return signed("freshness", Arrays.asList(value.userId(), value.permissionRevision(), value.credentialEpoch(),
                value.status().name(), value.permissionHash(), value.checkedAtEpochSecond(), value.notAfterEpochSecond(),
                value.nonce()), value.algorithm(), value.keyId());
    }

    static byte[] contextBytes(AuthorizationContext value) {
        return signed("context", contextFields(value), value.algorithm(), value.keyId());
    }

    static byte[] callerBytes(CallerProof value) {
        return signed("request", Arrays.asList(value.callerService(), value.audience(), value.operation().name(),
                value.requestId(), value.requestHash(), value.nonce(), value.issuedAtEpochSecond(),
                value.expiresAtEpochSecond()), value.algorithm(), value.keyId());
    }

    private static List<?> snapshotFields(AuthorizationSnapshot value) {
        return Arrays.asList(value.actorId(), value.permissionRevision(), value.catalogVersion(),
                value.businessAdmin(), value.permissions(), value.grantDeadlineEpochSecond());
    }

    private static List<?> contextFields(AuthorizationContext value) {
        return Arrays.asList(value.actorId(), value.permissionRevision(), value.credentialEpoch(), value.permissionHash(),
                value.allowedOperations(), value.audience(), value.requestId(), value.runId(), value.sessionId(),
                value.policyDigest(), value.requestHash(), value.nonce(), value.issuedAtEpochSecond(),
                value.expiresAtEpochSecond(), value.runHardDeadlineEpochSecond());
    }

    private static List<?> delegationFields(DelegationBinding value) {
        return Arrays.asList(value.callerService(), value.audience(), value.actorId(), value.operations(), value.runId(),
                value.taskId(), value.requestId(), value.bodyHash(), value.policyDigest(), value.nonce(),
                value.issuedAtEpochSecond(), value.expiresAtEpochSecond());
    }

    private static List<?> requestFields(RequestBinding value) {
        List<?> query = value.query().stream().map(pair -> List.of(pair.name(), pair.value())).toList();
        return Arrays.asList(value.callerService(), value.audience(), value.method(), value.originalPath(),
                value.targetPath(), query, value.requestId(), value.runId(), value.sessionId(), value.bodyHash(),
                value.policyDigest(), value.nonce(), value.issuedAtEpochSecond(), value.expiresAtEpochSecond());
    }

    private static byte[] signed(String type, List<?> fields, String algorithm, String keyId) {
        return encode(type, Arrays.asList(algorithm, keyId, fields));
    }

    private static byte[] encode(String type, List<?> fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, "bkanent-authz-v1");
                write(out, type);
                write(out, fields);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void write(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof String text) {
            out.writeByte(1);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (value instanceof Long number) {
            out.writeByte(2);
            out.writeLong(number);
        } else if (value instanceof Boolean flag) {
            out.writeByte(3);
            out.writeBoolean(flag);
        } else if (value instanceof List<?> list) {
            out.writeByte(4);
            out.writeInt(list.size());
            for (Object item : list) write(out, item);
        } else {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
