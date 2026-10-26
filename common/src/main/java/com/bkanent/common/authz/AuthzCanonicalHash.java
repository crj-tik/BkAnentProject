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
import java.util.Map;

/**
 * v2 explicit ordered, typed, length-prefixed UTF-8 fields; no map, JSON, reflection or toString.
 * Hashes and signature inputs have independent type tags. Wire changes require a protocol bump.
 */
public final class AuthzCanonicalHash {
    private AuthzCanonicalHash() { }

    /** Only stable authority facts: signature, algorithm, key rotation and cache TTL are excluded. */
    public static String snapshotHash(AuthorizationSnapshot snapshot) {
        AuthzContract.require(snapshot != null);
        return digest(encode("snapshot-content-v2", snapshotFields(snapshot)));
    }

    /** Digest of every signed context field, including its key identity and signature. */
    public static String signedContextHash(AuthorizationContext context) {
        AuthzContract.require(context != null && context.signature() != null);
        return digest(encode("signed-context-v2", Arrays.asList(contextFields(context), context.algorithm(),
                context.keyId(), context.signature())));
    }

    /** Digest of every signed freshness field, including its key identity and signature. */
    public static String signedFreshnessHash(AuthzFreshness freshness) {
        AuthzContract.require(freshness != null && freshness.signature() != null);
        return digest(encode("signed-freshness", Arrays.asList(freshnessFields(freshness), freshness.algorithm(),
                freshness.keyId(), freshness.signature())));
    }

    /** Digest of all three signed Auth facts for a bounded downstream service request proof. */
    public static String signedResponseHash(AuthzResponse response) {
        AuthzContract.require(response != null && response.snapshot().signature() != null
                && response.context().signature() != null && response.freshness().signature() != null);
        return digest(encode("signed-response-v1", Arrays.asList(snapshotHash(response.snapshot()),
                response.snapshot().algorithm(), response.snapshot().keyId(), response.snapshot().signature(),
                signedContextHash(response.context()), signedFreshnessHash(response.freshness()))));
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

    /** Independent refresh type binds real token possession, the complete signed original and new request. */
    public static String refreshRequestHash(String token, AuthorizationContext original, RequestBinding binding) {
        AuthzContract.text(token, 16384);
        AuthzContract.require(original != null && original.signature() != null && binding != null);
        String credentialDigest = digest(encode("credential", List.of(token)));
        return digest(encode("refresh-request", Arrays.asList(credentialDigest, contextFields(original),
                original.algorithm(), original.keyId(), original.signature(), requestFields(binding))));
    }

    /** The actual signed parent is covered, not just its globally shared snapshot hash. */
    public static String delegationRequestHash(AuthorizationContext parent, DelegationBinding binding) {
        AuthzContract.require(parent != null && binding != null && parent.signature() != null);
        return digest(encode("delegate-request", Arrays.asList(contextFields(parent), parent.algorithm(),
                parent.keyId(), parent.signature(), delegationFields(binding))));
    }

    /** Independent proof type covers every field and signature of the complete ingress response. */
    public static String executionBindingRequestHash(AuthzResponse ingress, ExecutionBinding binding) {
        AuthzContract.require(ingress != null && binding != null);
        return digest(encode("execution-binding-request", Arrays.asList(
                signedResponseFields(ingress), executionFields(binding), binding.requestId(), binding.nonce(),
                binding.issuedAtEpochSecond(), binding.expiresAtEpochSecond(), binding.algorithm())));
    }

    /** Durable identity excludes retry correlation/time but fixes the original authority and deadlines. */
    public static String executionBindingTupleHash(AuthzResponse ingress, ExecutionBinding binding) {
        AuthzContract.require(ingress != null && binding != null);
        AuthorizationContext context = ingress.context();
        return digest(encode("execution-binding-tuple", Arrays.asList(executionFields(binding),
                context.permissionRevision(), context.credentialEpoch(), context.permissionHash(),
                context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond())));
    }

    /** Canonical scope digest for the durable ingress-binding fact (order/duplicates normalized). */
    public static String executionOperationsHash(List<String> operations) {
        return digest(encode("execution-operations", AuthzContract.permissions(operations)));
    }

    private static List<?> executionFields(ExecutionBinding value) {
        return Arrays.asList(value.callerService(), value.actorId(), value.runId(), value.sessionId(),
                value.operations(), value.ingressRequestHash(), value.executionHash(), value.policyDigest());
    }

    private static List<?> signedResponseFields(AuthzResponse value) {
        AuthzContract.require(value.snapshot().signature() != null && value.context().signature() != null
                && value.freshness().signature() != null);
        AuthorizationSnapshot snapshot = value.snapshot();
        AuthorizationContext context = value.context();
        AuthzFreshness freshness = value.freshness();
        return Arrays.asList(snapshotFields(snapshot), snapshot.algorithm(), snapshot.keyId(), snapshot.signature(),
                contextFields(context), context.algorithm(), context.keyId(), context.signature(),
                Arrays.asList(freshness.userId(), freshness.permissionRevision(), freshness.credentialEpoch(),
                        freshness.status().name(), freshness.permissionHash(), freshness.checkedAtEpochSecond(),
                        freshness.notAfterEpochSecond(), freshness.nonce()),
                freshness.algorithm(), freshness.keyId(), freshness.signature());
    }

    static byte[] snapshotBytes(AuthorizationSnapshot value) {
        return signed("snapshot", snapshotFields(value), value.algorithm(), value.keyId());
    }

    static byte[] freshnessBytes(AuthzFreshness value) {
        return signed("freshness", freshnessFields(value), value.algorithm(), value.keyId());
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
                value.businessAdmin(), value.permissions(), value.grantDeadlineEpochSecond(),
                identityFields(value.businessIdentities()));
    }

    private static List<?> contextFields(AuthorizationContext value) {
        return Arrays.asList(value.actorId(), value.permissionRevision(), value.credentialEpoch(), value.permissionHash(),
                value.allowedOperations(), value.audience(), value.requestId(), value.runId(), value.sessionId(),
                value.policyDigest(), value.requestHash(), value.nonce(), value.issuedAtEpochSecond(),
                value.expiresAtEpochSecond(), value.runHardDeadlineEpochSecond(),
                identityFields(value.businessIdentities()));
    }

    private static List<?> identityFields(Map<String, Long> identities) {
        return identities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<?> freshnessFields(AuthzFreshness value) {
        return Arrays.asList(value.userId(), value.permissionRevision(), value.credentialEpoch(),
                value.status().name(), value.permissionHash(), value.checkedAtEpochSecond(),
                value.notAfterEpochSecond(), value.nonce());
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
                write(out, "bkanent-authz-v2");
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
