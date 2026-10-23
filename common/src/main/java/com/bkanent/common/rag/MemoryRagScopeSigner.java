package com.bkanent.common.rag;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** HMAC scope inside the separate RAG search protocol; not a Memory v2 RPC proof or compatibility path. */
public final class MemoryRagScopeSigner {
    private MemoryRagScopeSigner() { }

    public static MemoryRagAuthorizationScope sign(MemoryRagAuthorizationScope unsigned, byte[] secret) {
        require(secret != null && secret.length >= 32);
        return copy(unsigned, Base64.getUrlEncoder().withoutPadding().encodeToString(mac(bytes(unsigned), secret)));
    }

    public static void verify(MemoryRagAuthorizationScope scope, String expectedKeyId, byte[] secret, RagSearchRequest search,
                              String ownerId, String domainId, String agentId, Instant now) {
        require(scope != null && secret != null && secret.length >= 32 && search != null && now != null);
        require(scope.contractVersion() == MemoryRagAuthorizationScope.VERSION
                && scope.keyId().equals(expectedKeyId)
                && "memory-service".equals(scope.issuerService())
                && scope.ownerId().equals(ownerId) && scope.domainId().equals(domainId)
                && scope.agentId().equals(agentId) && scope.spaceId().equals(search.spaceId())
                && scope.requestId().equals(search.requestId())
                && scope.requestHash().equals(RagCanonicalHash.requestHash(search)));
        require(scope.expiresAtEpochSecond() > now.getEpochSecond()
                && scope.issuedAtEpochSecond() <= now.getEpochSecond() + 5);
        byte[] supplied;
        try { supplied = Base64.getUrlDecoder().decode(scope.signature()); }
        catch (RuntimeException invalid) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        byte[] expected = mac(bytes(copy(scope, null)), secret);
        require(MessageDigest.isEqual(expected, supplied)
                && Base64.getUrlEncoder().withoutPadding().encodeToString(supplied).equals(scope.signature()));
    }

    private static MemoryRagAuthorizationScope copy(MemoryRagAuthorizationScope value, String signature) {
        require(value != null);
        return new MemoryRagAuthorizationScope(value.contractVersion(), value.keyId(), value.issuerService(),
                value.ownerId(), value.domainId(), value.agentId(), value.conversationId(), value.matterId(),
                value.recordType(), value.spaceId(), value.requestId(), value.requestHash(), value.grantIds(),
                value.purpose(), value.issuedAtEpochSecond(), value.expiresAtEpochSecond(), signature);
    }

    private static byte[] bytes(MemoryRagAuthorizationScope scope) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            write(out, "memory.rag.scope.v1");
            out.writeInt(scope.contractVersion());
            write(out, scope.keyId()); write(out, scope.issuerService()); write(out, scope.ownerId());
            write(out, scope.domainId()); write(out, scope.agentId()); write(out, scope.conversationId());
            write(out, scope.matterId()); write(out, scope.recordType().name()); write(out, scope.spaceId());
            write(out, scope.requestId()); write(out, scope.requestHash()); write(out, scope.purpose());
            out.writeInt(scope.grantIds().size()); for (String grant : scope.grantIds()) write(out, grant);
            out.writeLong(scope.issuedAtEpochSecond()); out.writeLong(scope.expiresAtEpochSecond());
            out.flush(); return buffer.toByteArray();
        } catch (IOException exception) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static byte[] mac(byte[] bytes, byte[] secret) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(bytes); }
        catch (GeneralSecurityException exception) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
    }

    private static void require(boolean condition) { if (!condition) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
}
