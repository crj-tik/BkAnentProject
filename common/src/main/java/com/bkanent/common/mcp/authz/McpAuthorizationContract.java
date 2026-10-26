package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzDeadlines;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzRpcOperation;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthzWireCodec;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.CallerProof;
import com.bkanent.common.authz.DelegatedAuthorizationRequest;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact MCP JSON-RPC request binding shared by the trusted Agent client and server guard. */
public final class McpAuthorizationContract {
    public static final String HEADER = "X-Bkanent-MCP-Authorization";
    public static final String TRANSPORT_CONTEXT_KEY = McpAuthorizationContract.class.getName() + ".invocation";
    public static final int MAX_HEADER_CHARS = 64 * 1024;
    private static final ObjectMapper MCP_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final SecureRandom RANDOM = new SecureRandom();

    private McpAuthorizationContract() { }

    public record Request(String method, String requestId, String toolName) {
        public boolean isToolCall() { return "tools/call".equals(method); }
        public boolean isMetadataCall() {
            return Set.of("initialize", "notifications/initialized", "ping", "tools/list").contains(method);
        }
    }

    public record Verified(String actorId, String callerService, String audience, String operation,
                           String requestId, String toolName, AuthorizationContext context,
                           AuthzFreshness freshness, DelegationBinding binding) {
        @Override public String toString() { return "VerifiedMcpAuthorization[REDACTED]"; }
    }

    public static Request parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > AuthzContract.MAX_BODY_BYTES) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        try {
            JsonNode root = MCP_JSON.readTree(body);
            if (root == null || !root.isObject() || !"2.0".equals(root.path("jsonrpc").asText())) {
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            }
            String method = root.path("method").asText(null);
            if (method == null || method.isBlank()) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            if (!"tools/call".equals(method)) return new Request(method, null, null);

            JsonNode id = root.get("id");
            if (id == null || id.isNull() || !(id.isTextual() || id.isIntegralNumber())) {
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            }
            String requestId = id.asText();
            JsonNode name = root.path("params").path("name");
            if (!name.isTextual() || name.asText().isBlank()) {
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            }
            return new Request(method, requestId, name.asText());
        } catch (AuthzException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    /** Signs one actual tools/call body; caller supplies only trusted run facts, never model arguments. */
    public static String sign(byte[] body, AuthorizationContext context, AuthzFreshness freshness,
                              String callerService, String audience, String toolName, String operation,
                              String keyId, PrivateKey signingKey, Instant now) {
        try {
            Request parsed = parse(body);
            if (!parsed.isToolCall() || !toolName.equals(parsed.toolName()) || context == null || freshness == null
                    || signingKey == null || now == null || context.runId() == null || context.sessionId() == null
                    || !callerService.equals(context.audience()) || !context.actorId().equals(freshness.userId())
                    || context.permissionRevision() != freshness.permissionRevision()
                    || context.credentialEpoch() != freshness.credentialEpoch()
                    || !context.permissionHash().equals(freshness.permissionHash())
                    || freshness.status() != AuthorizationStatus.ACTIVE
                    || !context.allowedOperations().contains(operation)) {
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
            }
            long issuedAt = now.getEpochSecond();
            long expiresAt = Math.min(issuedAt + AuthzDeadlines.REQUEST_SECONDS,
                    Math.min(context.expiresAtEpochSecond(), Math.min(context.runHardDeadlineEpochSecond(),
                            freshness.notAfterEpochSecond())));
            if (expiresAt <= issuedAt || issuedAt < freshness.checkedAtEpochSecond()) {
                throw new AuthzException(AuthzErrorCode.EXPIRED);
            }
            byte[] nonceBytes = new byte[32];
            RANDOM.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            DelegationBinding binding = new DelegationBinding(callerService, audience, context.actorId(),
                    List.of(operation), context.runId(), null, parsed.requestId(),
                    AuthzCanonicalHash.bodyHash(body), context.policyDigest(), nonce, issuedAt, expiresAt);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
            CallerProof proof = AuthzSignature.sign(new CallerProof(callerService, audience,
                    AuthzRpcOperation.VALIDATE_DELEGATION, parsed.requestId(), requestHash, nonce, issuedAt,
                    expiresAt, AuthzContract.ALGORITHM, keyId, null), signingKey);
            String encoded = AuthzWireCodec.encodeDelegatedAuthorizationRequest(
                    new DelegatedAuthorizationRequest(context, freshness, binding, proof));
            if (encoded.length() > MAX_HEADER_CHARS) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            return encoded;
        } catch (AuthzException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    /** Verifies the complete proof locally; it performs no Auth or Redis call. */
    public static Verified verify(String encoded, byte[] body, String expectedCaller, String expectedAudience,
                                  String expectedTool, String expectedOperation,
                                  Map<String, PublicKey> authorityKeys, Map<String, PublicKey> callerKeys,
                                  Instant now) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_HEADER_CHARS || now == null) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            Request parsed = parse(body);
            DelegatedAuthorizationRequest request = AuthzWireCodec.decodeDelegatedAuthorizationRequest(encoded);
            AuthorizationContext context = request.context();
            AuthzFreshness freshness = request.freshness();
            DelegationBinding binding = request.binding();
            CallerProof callerProof = request.callerProof();
            if (!parsed.isToolCall() || !expectedTool.equals(parsed.toolName())
                    || !expectedCaller.equals(binding.callerService())
                    || !expectedAudience.equals(binding.audience())
                    || !context.actorId().equals(binding.actorId())
                    || !expectedOperation.equals(binding.operations().size() == 1 ? binding.operations().get(0) : null)
                    || !parsed.requestId().equals(binding.requestId()) || binding.taskId() != null
                    || !AuthzCanonicalHash.bodyHash(body).equals(binding.bodyHash())
                    || !context.actorId().equals(freshness.userId())
                    || context.permissionRevision() != freshness.permissionRevision()
                    || context.credentialEpoch() != freshness.credentialEpoch()
                    || !context.permissionHash().equals(freshness.permissionHash())
                    || freshness.status() != AuthorizationStatus.ACTIVE
                    || !context.allowedOperations().contains(expectedOperation)
                    || binding.expiresAtEpochSecond() > freshness.notAfterEpochSecond()
                    || binding.issuedAtEpochSecond() < freshness.checkedAtEpochSecond()
                    || context.runId() == null || context.sessionId() == null) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
            AuthzSignature.verifyFreshness(freshness, authorityKeys, now);
            AuthzSignature.verifyDelegationBinding(context, binding, binding, authorityKeys, now);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
            AuthzSignature.verifyCallerProof(callerProof, expectedCaller, expectedAudience,
                    AuthzRpcOperation.VALIDATE_DELEGATION, parsed.requestId(), requestHash, binding.nonce(),
                    Set.of(AuthzRpcOperation.VALIDATE_DELEGATION), callerKeys, now);
            return new Verified(context.actorId(), binding.callerService(), binding.audience(), expectedOperation,
                    parsed.requestId(), parsed.toolName(), context, freshness, binding);
        } catch (AuthzException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }
}
