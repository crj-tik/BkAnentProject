package com.bkanent.memory.service;

import com.bkanent.common.security.ServiceIdentityCrypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** One isolated Ed25519 identity for in-process Memory v2 fixtures. */
final class MemoryTestIdentity {
    static final KeyPair PAIR = generate();

    private MemoryTestIdentity() { }

    static String encodedPublicKey() {
        return ServiceIdentityCrypto.encodePublicKey(PAIR.getPublic());
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
