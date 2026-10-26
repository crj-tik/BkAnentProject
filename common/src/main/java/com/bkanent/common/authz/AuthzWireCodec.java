package com.bkanent.common.authz;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, typed JSON transport, not Java serialization or a standalone bearer credential. */
public final class AuthzWireCodec {
    public static final int MAX_ENCODED_HEADER_CHARS = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private AuthzWireCodec() { }

    public static String encodeResponse(AuthzResponse response) { return encode(response); }
    public static AuthzResponse decodeResponse(String encoded) { return decode(encoded, AuthzResponse.class); }
    public static String encodeBinding(RequestBinding binding) { return encode(binding); }
    public static RequestBinding decodeBinding(String encoded) { return decode(encoded, RequestBinding.class); }
    public static String encodeDelegatedAuthorizationRequest(DelegatedAuthorizationRequest request) {
        return encode(request);
    }
    public static DelegatedAuthorizationRequest decodeDelegatedAuthorizationRequest(String encoded) {
        return decode(encoded, DelegatedAuthorizationRequest.class);
    }

    /** No RPC/token parsing: verify the three Auth signatures and actual bytes before JSON execution. */
    public static void verifyIngress(AuthzResponse response, RequestBinding binding, String normalizedToken,
                                     String audience, String method, String targetPath,
                                     List<RequestBinding.QueryParameter> query, byte[] rawBody,
                                     Map<String, PublicKey> authorityKeys, Instant now) {
        AuthzContract.require(binding != null && query != null && now != null);
        AuthzContract.check(binding.issuedAtEpochSecond() <= now.getEpochSecond()
                && now.getEpochSecond() < binding.expiresAtEpochSecond(), AuthzErrorCode.EXPIRED);
        AuthzSignature.verifyResponse(response, audience, binding.requestId(),
                AuthzCanonicalHash.resolveRequestHash(normalizedToken, binding), binding.nonce(), authorityKeys, now);
        AuthorizationContext context = response.context();
        AuthzContract.check(audience.equals(binding.audience()) && method.equals(binding.method())
                && validatePath(targetPath).equals(binding.targetPath())
                && query.stream().sorted(java.util.Comparator.comparing(RequestBinding.QueryParameter::name)
                        .thenComparing(RequestBinding.QueryParameter::value)).toList().equals(binding.query())
                && AuthzCanonicalHash.bodyHash(rawBody).equals(binding.bodyHash())
                && Objects.equals(context.runId(), binding.runId())
                && Objects.equals(context.sessionId(), binding.sessionId())
                && context.policyDigest().equals(binding.policyDigest()), AuthzErrorCode.CONTEXT_CONFLICT);
    }

    /** Decode UTF-8 exactly once; preserve duplicate non-identity pairs, reject ambiguous encodings. */
    public static List<RequestBinding.QueryParameter> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return List.of();
        AuthzContract.require(rawQuery.length() <= 32768);
        String[] pairs = rawQuery.split("&", -1);
        AuthzContract.require(pairs.length <= AuthzContract.MAX_QUERY_PARAMETERS);
        List<RequestBinding.QueryParameter> result = new ArrayList<>();
        Set<String> identityFields = new HashSet<>();
        for (String pair : pairs) {
            int equal = pair.indexOf('=');
            AuthzContract.require(equal > 0);
            String name = decodeQueryComponent(pair.substring(0, equal));
            String value = decodeQueryComponent(pair.substring(equal + 1));
            String identity = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (identity.endsWith("id") || Set.of("actor", "token", "ticket", "credential", "authorization")
                    .contains(identity)) {
                AuthzContract.require(identityFields.add(identity));
            }
            result.add(new RequestBinding.QueryParameter(name, value));
        }
        return result.stream().sorted(java.util.Comparator.comparing(RequestBinding.QueryParameter::name)
                .thenComparing(RequestBinding.QueryParameter::value)).toList();
    }

    /** v1 does not support encoded/normalized path aliases; route adapters must reject rather than guess. */
    public static String validatePath(String rawPath) {
        AuthzContract.text(rawPath, 2048);
        AuthzContract.require(rawPath.startsWith("/") && !rawPath.contains("//")
                && !rawPath.contains("%") && !rawPath.contains("\\") && !rawPath.contains(";")
                && !rawPath.contains("?") && !rawPath.contains("#"));
        for (String segment : rawPath.split("/", -1)) {
            AuthzContract.require(!segment.equals(".") && !segment.equals(".."));
        }
        return rawPath;
    }

    private static String decodeQueryComponent(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            AuthzContract.require(c != '+' && c != ';' && c >= 0x21 && c <= 0x7e);
            if (c == '%') {
                AuthzContract.require(i + 2 < raw.length());
                int high = Character.digit(raw.charAt(++i), 16);
                int low = Character.digit(raw.charAt(++i), 16);
                AuthzContract.require(high >= 0 && low >= 0);
                bytes.write(high * 16 + low);
            } else {
                bytes.write(c);
            }
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            AuthzContract.validText(decoded);
            // A second percent decode or form '+' interpretation must never produce another value.
            AuthzContract.require(decoded.indexOf('%') < 0 && decoded.indexOf('+') < 0);
            return decoded;
        } catch (CharacterCodingException exception) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    private static String encode(Object value) {
        AuthzContract.require(value != null);
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            AuthzContract.require(bytes.length <= MAX_ENCODED_HEADER_CHARS * 3 / 4);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (java.io.IOException exception) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }

    private static <T> T decode(String encoded, Class<T> type) {
        AuthzContract.require(encoded != null && !encoded.isEmpty()
                && encoded.length() <= MAX_ENCODED_HEADER_CHARS && encoded.matches("[A-Za-z0-9_-]+"));
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            AuthzContract.require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded));
            T value = MAPPER.readValue(bytes, type);
            AuthzContract.require(value != null);
            return value;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
    }
}
