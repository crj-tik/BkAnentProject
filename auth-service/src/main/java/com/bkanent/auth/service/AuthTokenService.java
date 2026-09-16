package com.bkanent.auth.service;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small signed-token service for the authentication baseline.
 *
 * <p>Revocation is intentionally process-local until a shared session store is introduced.</p>
 */
@Service
public class AuthTokenService {

    private static final String TOKEN_VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AuthTokenProperties properties;
    private final Set<String> revokedTokenDigests = ConcurrentHashMap.newKeySet();
    private volatile byte[] signingKey;

    public AuthTokenService(AuthTokenProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        if (StringUtils.hasText(properties.getSecret())) {
            signingKey = properties.getSecret().getBytes(StandardCharsets.UTF_8);
            return;
        }
        if (!properties.isAllowRandomSecret()) {
            throw new IllegalStateException("auth.token.secret must be configured outside the local profile");
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        signingKey = generated;
    }

    public String issueAccessToken(UserAccountEntity account) {
        return issue(account, "access", properties.getAccessTtlSeconds());
    }

    public String issueRefreshToken(UserAccountEntity account) {
        return issue(account, "refresh", properties.getRefreshTtlSeconds());
    }

    public boolean isValidAccessToken(String token) {
        TokenPrincipal principal = parse(token);
        return principal != null && "access".equals(principal.tokenType());
    }

    public void revoke(String token) {
        if (StringUtils.hasText(token)) {
            revokedTokenDigests.add(digest(token));
        }
    }

    public TokenPrincipal parse(String token) {
        if (!StringUtils.hasText(token) || signingKey == null || revokedTokenDigests.contains(digest(token))) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !TOKEN_VERSION.equals(parts[0])) {
            return null;
        }
        String payload = parts[0] + "." + parts[1];
        byte[] expectedSignature = sign(payload);
        byte[] actualSignature;
        try {
            actualSignature = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return null;
        }
        String[] fields = decode(parts[1]).split("\\|", -1);
        if (fields.length != 3) {
            return null;
        }
        try {
            long userId = Long.parseLong(fields[0]);
            long expiresAt = Long.parseLong(fields[1]);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return null;
            }
            return new TokenPrincipal(userId, fields[2], expiresAt);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String issue(UserAccountEntity account, String tokenType, long ttlSeconds) {
        if (account == null || account.getId() == null) {
            throw new IllegalArgumentException("account id must not be null");
        }
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String encodedPayload = ENCODER.encodeToString(
                (account.getId() + "|" + expiresAt + "|" + tokenType).getBytes(StandardCharsets.UTF_8)
        );
        String payload = TOKEN_VERSION + "." + encodedPayload;
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("unable to sign authentication token", exception);
        }
    }

    private String decode(String encoded) {
        try {
            return new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String digest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to hash revoked token", exception);
        }
    }

    public record TokenPrincipal(long userId, String tokenType, long expiresAt) {
    }
}
