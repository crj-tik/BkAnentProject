package com.bkanent.common.rag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1 canonical encoding: domain-separated typed length-prefixed UTF-8 binary, sorted map keys,
 * declared record component order, normalized decimal values, Instant seconds/nanos. Never uses
 * Jackson, Java serialization or record toString. Any change to this format requires a version bump.
 */
public final class RagCanonicalHash {
    private static final Set<Class<?>> REQUESTS = Set.of(RagSearchRequest.class, RagIndexEvent.class,
            RagStatusRequest.class, RagInspectRequest.class, RagProvisionRequest.class, RagReplayRequest.class,
            RagListingSearchRequest.class, RagListingReadRequest.class, RagListingIndexRequest.class, MemoryRagSearchRequest.class, A2aBeaconBinding.class);
    private static final Set<Class<?>> BUSINESS = Set.of(RagSearchRequest.class, RagRecallOptions.class,
            RagFusionOptions.class, RagFilter.class, RagValue.class, RagDocument.class, RagIndexEvent.class,
            RagStatusRequest.class, RagInspectRequest.class, RagProvisionRequest.class, RagReplayRequest.class,
            RagListingSearchRequest.class, RagListingReadRequest.class, RagListingIndexRequest.class, MemoryRagSearchRequest.class, A2aBeaconBinding.class);

    private RagCanonicalHash() { }

    /** Search omission and explicit effective defaults share a hash; all explicit illegal values reject. */
    public static String requestHash(Object request) {
        RagContract.require(request != null && REQUESTS.contains(request.getClass()), "request");
        if (request instanceof RagSearchRequest search) request = search.normalized();
        if (request instanceof MemoryRagSearchRequest memory) {
            request = new MemoryRagSearchRequest(memory.search().normalized(), memory.topK(),
                    memory.minimumSemanticRelevance(), memory.halfLifeDays());
        }
        return hash(encode("rag.request.v1", request, false));
    }

    public static String documentHash(RagIndexOperation operation, RagDocument document) {
        RagContract.require(operation != null && (operation == RagIndexOperation.UPSERT ? document != null : document == null), "document");
        return hash(encode("rag.document.v1", Arrays.asList(operation, document), false));
    }

    /** Transport identities/timestamps do not change a document revision's canonical payload. */
    public static String eventPayloadHash(RagIndexEvent event) {
        RagContract.require(event != null, "event");
        return hash(encode("rag.event-payload.v1", Arrays.asList(event.contractVersion(), event.spaceId(),
                event.documentId(), event.revision(), event.schemaVersion(), event.operation(),
                event.contentHash(), event.document()), false));
    }

    /** Auth provider hashes facts before attaching its response attestation, avoiding a signing cycle. */
    public static String principalHash(AuthRagPrincipal principal) {
        RagContract.require(principal != null, "principal");
        return hash(encode("rag.principal.v1", Arrays.asList(principal.userId(), principal.roleCode(),
                principal.accountActive(), principal.roleActive(), principal.expiresAtEpochSecond()), false));
    }

    /** Used directly as the RESOLVE_PRINCIPAL request hash; null represents no user credentials. */
    public static String credentialDigest(RagCredentialContext credentials) {
        if (credentials == null) return null;
        return hash(encode("rag.credential.v2", Arrays.asList(credentials.beacon() == null ? "access" : "a2a-beacon",
                credentials.beacon() == null ? credentials.accessToken() : credentials.beacon()), false));
    }

    static byte[] envelopeBytes(RagAuthEnvelope envelope) {
        return encode("rag.auth.v1", Arrays.asList(envelope.contractVersion(), envelope.keyId(), envelope.audience(),
                envelope.callerService(), envelope.operation(), envelope.requestId(), envelope.requestHash(),
                envelope.actorMode(), envelope.actorId(), envelope.issuedAtEpochSecond(),
                envelope.expiresAtEpochSecond(), envelope.credentialDigest()), false);
    }

    static byte[] scopeBytes(RagAuthorizationScope scope) {
        return encode("rag.scope.v2", Arrays.asList(scope.contractVersion(), scope.keyId(), scope.issuerService(),
                scope.ownerDomain(), scope.spaceId(), scope.actorId(), scope.operation(), scope.requestHash(),
                scope.policyVersion(), scope.issuedAtEpochSecond(), scope.expiresAtEpochSecond(),
                scope.ownerId(), scope.allowedResourceIds(), scope.activeOnly(), scope.businessWideRead()), false);
    }

    private static byte[] encode(String domain, Object value, boolean ignored) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, domain);
            write(output, value);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException | ReflectiveOperationException e) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "canonicalPayload");
        }
    }

    private static void write(DataOutputStream output, Object value) throws IOException, ReflectiveOperationException {
        if (value == null) {
            output.writeByte(0);
        } else if (value instanceof String text) {
            output.writeByte(1);
            writeString(output, text);
        } else if (value instanceof Boolean bool) {
            output.writeByte(2);
            output.writeBoolean(bool);
        } else if (value instanceof Number number) {
            output.writeByte(3);
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                RagContract.require(Math.abs((long) decimal.scale()) <= 1000 && decimal.precision() <= 128, "number");
                writeString(output, decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString());
            } catch (NumberFormatException e) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "number");
            }
        } else if (value instanceof Instant instant) {
            output.writeByte(4);
            output.writeLong(instant.getEpochSecond());
            output.writeInt(instant.getNano());
        } else if (value instanceof Enum<?> enumeration && enumeration.getDeclaringClass().getPackageName().equals(RagContract.class.getPackageName())) {
            output.writeByte(5);
            writeString(output, enumeration.getDeclaringClass().getSimpleName());
            writeString(output, enumeration.name());
        } else if (value instanceof List<?> list) {
            output.writeByte(6);
            output.writeInt(list.size());
            for (Object item : list) write(output, item);
        } else if (value instanceof Map<?, ?> map) {
            output.writeByte(7);
            RagContract.require(map.keySet().stream().allMatch(String.class::isInstance), "map");
            List<String> keys = map.keySet().stream().map(String.class::cast).sorted().toList();
            output.writeInt(keys.size());
            for (String key : keys) {
                writeString(output, key);
                write(output, map.get(key));
            }
        } else if (BUSINESS.contains(value.getClass())) {
            output.writeByte(8);
            writeString(output, value.getClass().getSimpleName());
            RecordComponent[] components = value.getClass().getRecordComponents();
            output.writeInt(components.length);
            for (RecordComponent component : components) {
                writeString(output, component.getName());
                write(output, component.getAccessor().invoke(value));
            }
        } else {
            // Deny credentials, keys, auth envelopes and arbitrary objects, including nested ones.
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "canonicalPayload");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.writeInt(bytes.length);
            output.write(bytes);
        } catch (CharacterCodingException e) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "canonicalText");
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
