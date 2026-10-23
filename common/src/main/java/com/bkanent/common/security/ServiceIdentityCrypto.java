package com.bkanent.common.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** JDK-only Ed25519 helpers for protocol-separated service identities. */
public final class ServiceIdentityCrypto {
    public static final String ED25519 = "Ed25519";
    private static final int MAX_PRIVATE_KEY_FILE_BYTES = 16 * 1024;
    private static final int MAX_PUBLIC_KEY_BYTES = 4096;

    private ServiceIdentityCrypto() { }

    public static PrivateKey loadPrivateKey(Path path) {
        if (path == null) throw new IllegalArgumentException("service identity private key is not configured");
        try {
            byte[] file = Files.readAllBytes(path);
            if (file.length == 0 || file.length > MAX_PRIVATE_KEY_FILE_BYTES) {
                throw new IllegalArgumentException("service identity private key file has an invalid size");
            }
            String pem = new String(file, StandardCharsets.US_ASCII).trim();
            String encoded = pemPayload(pem, "PRIVATE KEY");
            byte[] der = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(ED25519).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("service identity private key could not be loaded", exception);
        }
    }

    public static PublicKey decodePublicKey(String encodedOrPem) {
        if (encodedOrPem == null || encodedOrPem.isBlank() || encodedOrPem.length() > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("service identity public key is not configured");
        }
        try {
            String value = encodedOrPem.trim();
            String encoded = value.startsWith("-----BEGIN PUBLIC KEY-----")
                    ? pemPayload(value, "PUBLIC KEY") : value;
            byte[] der = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance(ED25519).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("service identity public key is invalid", exception);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        // Some JDK providers name their Ed25519 key implementation "EdDSA".
        if (key == null || !(ED25519.equals(key.getAlgorithm()) || "EdDSA".equals(key.getAlgorithm()))) {
            throw new IllegalArgumentException("an Ed25519 public key is required");
        }
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static byte[] sign(String protocolDomain, String algorithm, byte[] payload, PrivateKey key) {
        if (payload == null || key == null || !ED25519.equals(algorithm)) {
            throw new IllegalArgumentException("invalid service identity signature input");
        }
        try {
            Signature signer = Signature.getInstance(ED25519);
            signer.initSign(key);
            signer.update(frame(protocolDomain, algorithm, payload));
            return signer.sign();
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("service identity signing failed", exception);
        }
    }

    public static boolean verify(String protocolDomain, String algorithm, byte[] payload,
                                 byte[] signatureBytes, PublicKey key) {
        if (payload == null || signatureBytes == null || key == null || !ED25519.equals(algorithm)) return false;
        try {
            Signature verifier = Signature.getInstance(ED25519);
            verifier.initVerify(key);
            verifier.update(frame(protocolDomain, algorithm, payload));
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException | IOException exception) {
            return false;
        }
    }

    public static String encodeSignature(byte[] signature) {
        if (signature == null || signature.length != 64) throw new IllegalArgumentException("invalid Ed25519 signature");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    public static byte[] decodeSignature(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{86}")) {
            throw new IllegalArgumentException("invalid Ed25519 signature");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        if (decoded.length != 64 || !encodeSignature(decoded).equals(encoded)) {
            throw new IllegalArgumentException("invalid Ed25519 signature");
        }
        return decoded;
    }

    private static byte[] frame(String domain, String algorithm, byte[] payload) throws IOException {
        if (domain == null || !domain.matches("[A-Za-z0-9:-]{8,96}")) {
            throw new IllegalArgumentException("invalid protocol signature domain");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(domain.length() + algorithm.length() + payload.length + 16);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            write(output, domain);
            write(output, algorithm);
            output.writeInt(payload.length);
            output.write(payload);
        }
        return bytes.toByteArray();
    }

    private static String pemPayload(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new IllegalArgumentException("expected PKCS#8/X.509 " + label + " PEM");
        }
        String body = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        if (body.isEmpty()) throw new IllegalArgumentException("empty " + label + " PEM");
        return body;
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
