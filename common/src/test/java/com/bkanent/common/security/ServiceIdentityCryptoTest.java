package com.bkanent.common.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceIdentityCryptoTest {
    @Test
    void signatureIsBoundToProtocolDomainAndPayload() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "canonical-request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = ServiceIdentityCrypto.sign("BK-ANENT:A2A-AUTH:v1", ServiceIdentityCrypto.ED25519,
                payload, pair.getPrivate());

        assertThat(ServiceIdentityCrypto.verify("BK-ANENT:A2A-AUTH:v1", ServiceIdentityCrypto.ED25519,
                payload, signature, pair.getPublic())).isTrue();
        assertThat(ServiceIdentityCrypto.verify("BK-ANENT:MEMORY-V2:v1", ServiceIdentityCrypto.ED25519,
                payload, signature, pair.getPublic())).isFalse();
        assertThat(ServiceIdentityCrypto.verify("BK-ANENT:A2A-AUTH:v1", ServiceIdentityCrypto.ED25519,
                "changed-request".getBytes(java.nio.charset.StandardCharsets.UTF_8), signature, pair.getPublic())).isFalse();
    }

    @Test
    void publicKeyAndSignatureUseCanonicalEncodings() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String encodedKey = ServiceIdentityCrypto.encodePublicKey(pair.getPublic());
        var decodedKey = ServiceIdentityCrypto.decodePublicKey(encodedKey);
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertThat(ServiceIdentityCrypto.decodePublicKey(publicPem).getEncoded()).containsExactly(pair.getPublic().getEncoded());
        byte[] payload = new byte[]{1, 2, 3};
        String encodedSignature = ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(
                "BK-ANENT:MEMORY-V2:v1", ServiceIdentityCrypto.ED25519, payload, pair.getPrivate()));

        assertThat(ServiceIdentityCrypto.decodeSignature(encodedSignature)).hasSize(64);
        assertThat(ServiceIdentityCrypto.verify("BK-ANENT:MEMORY-V2:v1", ServiceIdentityCrypto.ED25519,
                payload, ServiceIdentityCrypto.decodeSignature(encodedSignature), decodedKey)).isTrue();
    }
}
