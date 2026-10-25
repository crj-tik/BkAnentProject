package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;

/** Original and forwarded targets are explicit. Query pairs are decoded once by the adapter. */
public record RequestBinding(String callerService, String audience, String method,
                             String originalPath, String targetPath, List<QueryParameter> query,
                             String requestId, String runId, String sessionId, String bodyHash,
                             String policyDigest, String nonce, long issuedAtEpochSecond,
                             long expiresAtEpochSecond) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public RequestBinding {
        callerService = AuthzContract.service(callerService);
        audience = AuthzContract.service(audience);
        AuthzContract.require(method != null && method.matches("[A-Z]{1,16}"));
        originalPath = path(originalPath);
        targetPath = path(targetPath);
        AuthzContract.require(query != null && query.size() <= AuthzContract.MAX_QUERY_PARAMETERS
                && query.stream().allMatch(java.util.Objects::nonNull));
        query = query.stream().sorted(Comparator.comparing(QueryParameter::name)
                .thenComparing(QueryParameter::value)).toList();
        requestId = AuthzContract.text(requestId, 128);
        runId = AuthzContract.nullableText(runId, 128);
        sessionId = AuthzContract.nullableText(sessionId, 128);
        bodyHash = AuthzContract.hash(bodyHash);
        policyDigest = AuthzContract.hash(policyDigest);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.period(issuedAtEpochSecond, expiresAtEpochSecond, AuthzDeadlines.REQUEST_SECONDS);
    }

    private static String path(String value) {
        AuthzContract.text(value, 2048);
        AuthzContract.require(value.startsWith("/") && !value.startsWith("//")
                && value.indexOf('?') < 0 && value.indexOf('#') < 0 && value.indexOf('\\') < 0);
        return value;
    }

    /** Empty value is distinct from an absent pair; duplicate pairs remain in the hash. */
    public record QueryParameter(String name, String value) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        public QueryParameter {
            name = AuthzContract.text(name, 256);
            AuthzContract.require(value != null && value.length() <= 2048);
            AuthzContract.validText(value);
        }

        @Override public String toString() { return "QueryParameter[REDACTED]"; }
    }

    @Override public String toString() { return "RequestBinding[REDACTED]"; }
}
