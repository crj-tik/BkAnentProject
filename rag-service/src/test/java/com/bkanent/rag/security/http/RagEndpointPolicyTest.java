package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RagEndpointPolicyTest {
    private final RagEndpointPolicy policy = new RagEndpointPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://public.vendor.com", "//public.vendor.com", "http:public.vendor.com",
            "https://user:secret@public.vendor.com/path", "https://public.vendor.com@127.0.0.1", "https://localhost",
            "https://LOCALHOST.", "https://x.localhost.", "https://localhost..", "https://127.0.0.1",
            "http://127.1", "http://2130706433", "http://0177.0.0.1", "http://0x7f000001", "http://0x7f.0.0.1",
            "http://127.000.0.1", "https://[::1]", "https://[::ffff:127.0.0.1]", "https://[::ffff:8.8.8.8]",
            "https://[fe80::1%25eth0]", "https://public.vendor.com:0", "https://public.vendor.com:65536",
            "https://public.vendor.com:", "https://public.vendor.com/#secret", "https://public%2eexample",
            "https://host.docker.internal", "https://public.vendor.com\\@127.0.0.1"})
    void rejectsUnsafeAndAmbiguousUrlsWithoutLookingUpNames(String url) {
        RagException ex = assertThrows(RagException.class, () -> policy.validateUrl(url));
        assertEquals(RagErrorCode.ENDPOINT_POLICY_DENIED, ex.code());
        assertEquals("ENDPOINT_POLICY_DENIED", ex.getMessage());
        assertNull(ex.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1.2.3", "10.2.3.4", "100.64.0.1", "100.127.255.254", "127.0.0.2",
            "169.254.1.2", "172.16.0.1", "172.31.255.1", "192.168.1.1", "192.0.0.9", "192.0.2.1",
            "192.88.99.1", "192.31.196.1", "192.52.193.1", "192.175.48.1", "198.18.0.1", "198.19.1.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "240.0.0.1", "255.255.255.255", "::", "::1", "::ffff:8.8.8.8", "64:ff9b::808:808",
            "100::1", "2001::1", "2001:2::1", "2001:20::1", "2001:db8::1", "2002:808:808::1",
            "2620:4f:8000::1", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff02::1", "3fff::1"})
    void rejectsReservedAddressBytes(String literal) throws Exception {
        byte[] bytes = RagEndpointPolicy.literalAddress(literal);
        // Inet6Address preserves mapped byte length; InetAddress would silently turn it into IPv4.
        InetAddress address = bytes.length == 16 ? java.net.Inet6Address.getByAddress(null, bytes, -1)
                : InetAddress.getByAddress(bytes);
        assertThrows(RagException.class, () -> policy.requirePublic(address));
    }

    @Test
    void acceptsPublicFormsAndChecksLiveAllowlist() {
        AtomicReference<Set<String>> hosts = new AtomicReference<>(Set.of("PUBLIC.VENDOR.COM."));
        RagEndpointPolicy restricted = new RagEndpointPolicy(hosts::get);
        assertEquals("public.vendor.com", restricted.validateHost("Public.Vendor.Com."));
        restricted.validateUrl("https://public.vendor.com:443/v1/embeddings?version=1");
        hosts.set(Set.of("other.example"));
        assertThrows(RagException.class, () -> restricted.validateHost("public.vendor.com"));
        assertDoesNotThrow(() -> policy.validateUrl("https://8.8.8.8/v1"));
        assertDoesNotThrow(() -> policy.validateUrl("https://[2606:4700:4700::1111]/v1"));
        assertThrows(RagException.class,
                () -> new RagEndpointPolicy(() -> Set.of("127.0.0.1")).validateHost("127.0.0.1"));
    }
}
