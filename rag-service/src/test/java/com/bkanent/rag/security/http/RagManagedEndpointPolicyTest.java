package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RagManagedEndpointPolicyTest {
    static RagHttpPolicyConfiguration config(List<String> hosts, Map<String, List<String>> networks) {
        return new RagHttpPolicyConfiguration("MANAGED_INTERNAL", hosts, networks);
    }

    static RagEndpointPolicy managed(List<String> hosts, Map<String, List<String>> networks) {
        return RagEndpointPolicy.configured(() -> config(hosts, networks));
    }

    static InetAddress address(String literal) throws Exception {
        return RagDnsRecords.parseRecord(literal, literal.contains(":"));
    }

    static void denied(org.junit.jupiter.api.function.Executable action) {
        RagException ex = assertThrows(RagException.class, action);
        assertEquals(RagErrorCode.ENDPOINT_POLICY_DENIED, ex.code());
        assertEquals("ENDPOINT_POLICY_DENIED", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void explicitHostMapAllowsDockerAndBothDistinctHostRuntimeShapes() throws Exception {
        var policy = managed(List.of("elasticsearch", "host.docker.internal", "10.117.102.54"), Map.of(
                "elasticsearch", List.of("172.24.0.0/16"),
                "host.docker.internal", List.of("192.168.65.254/32"),
                "10.117.102.54", List.of("10.117.102.54/32")));
        policy.validateUrl("http://elasticsearch:9200/index");
        policy.validateUrl("http://host.docker.internal:18119/rerank");
        policy.validateUrl("http://10.117.102.54:18119/rerank");
        assertEquals(address("172.24.0.2"), new RagPublicDnsResolver(policy,
                host -> new InetAddress[]{addressUnchecked("172.24.0.2")}).resolve("elasticsearch")[0]);
        var hostDns = new RagPublicDnsResolver(policy, host -> new InetAddress[]{addressUnchecked("192.168.65.254")});
        assertEquals(address("192.168.65.254"), hostDns.resolve("host.docker.internal")[0]);
        denied(() -> new RagPublicDnsResolver(policy, host -> new InetAddress[]{addressUnchecked("10.117.102.54")})
                .resolve("host.docker.internal")); // Windows address cannot borrow the literal's map
        var windows = managed(List.of("host.docker.internal"), Map.of("host.docker.internal", List.of("10.117.102.54/32")));
        assertEquals(address("10.117.102.54"), new RagPublicDnsResolver(windows,
                host -> new InetAddress[]{addressUnchecked("10.117.102.54")}).resolve("host.docker.internal")[0]);
        denied(() -> new RagPublicDnsResolver(windows, host -> new InetAddress[]{addressUnchecked("192.168.65.254")})
                .resolve("host.docker.internal"));
    }

    @Test
    void publicCompanyHostNeedsNoPrivateCidrAndDefaultPublicBehaviorIsUnchanged() throws Exception {
        var company = managed(List.of("open-chatgpt.ke.com"), Map.of());
        company.validateUrl("https://open-chatgpt.ke.com/v1/embeddings");
        assertEquals(2, new RagPublicDnsResolver(company, host -> new InetAddress[]{
                addressUnchecked("43.144.246.193"), addressUnchecked("81.70.125.41")})
                .resolve("open-chatgpt.ke.com").length);
        denied(() -> new RagPublicDnsResolver(company, host -> new InetAddress[]{addressUnchecked("10.1.1.1")})
                .resolve("open-chatgpt.ke.com"));
        var publicOnly = RagEndpointPolicy.configured(() -> new RagHttpPolicyConfiguration("PUBLIC_ONLY",
                List.of("public.example"), Map.of("public.example", List.of("10.0.0.0/8"))));
        denied(() -> new RagPublicDnsResolver(publicOnly, host -> new InetAddress[]{addressUnchecked("10.1.1.1")})
                .resolve("public.example"));
        assertDoesNotThrow(() -> new RagEndpointPolicy().validateUrl("https://8.8.8.8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10.0.0.0", "10.0.0.0/", "10.0.0.0/0", "0.0.0.0/0", "::/0", "*",
            "10.0.0.0/7", "172.16.0.0/11", "192.168.0.0/15", "8.8.8.0/24", "169.254.0.0/16",
            "127.0.0.0/8", "100.64.0.0/10", "224.0.0.0/4", "240.0.0.0/4", "10.1.1.1/8",
            "172.24.0.1/16", "10.0.0.0/33", "10.0.0.0/08", "010.0.0.0/8", "0xa.0.0.0/8",
            "10.0.0.0/+8", "10.0.0.0/8 ", "10.0.0.0/8,192.168.0.0/16", "fc00::/6", "fd00::1/64",
            "FC00::/7", "fc00:0:0:0:0:0:0:0/7", "fd00:0000::/8", "fd00::/129",
            "::ffff:10.0.0.0/104", "64:ff9b::/96", "2002::/16", "2001:db8::/32"})
    void rejectsMalformedNoncanonicalOrOverbroadNetworks(String cidr) {
        denied(() -> managed(List.of("elasticsearch"), Map.of("elasticsearch", List.of(cidr)))
                .validateUrl("http://elasticsearch:9200"));
    }

    @Test
    void missingInvalidOrCrossHostConfigurationFailsClosed() throws Exception {
        denied(() -> managed(List.of(), Map.of()).validateHost("elasticsearch"));
        denied(() -> managed(List.of("*.example"), Map.of()).validateHost("public.example"));
        denied(() -> managed(List.of("elasticsearch"), Map.of("other.example", List.of("10.0.0.0/8")))
                .validateHost("elasticsearch"));
        denied(() -> managed(List.of("elasticsearch"), Map.of("elasticsearch", List.of())).validateHost("elasticsearch"));
        denied(() -> RagEndpointPolicy.configured(() -> new RagHttpPolicyConfiguration("DISABLED", List.of(), Map.of()))
                .validateHost("public.example"));
        denied(() -> managed(List.of("10.0.0.1"), Map.of()).validateUrl("http://10.0.0.1"));
        var policy = managed(List.of("elasticsearch", "rerank"), Map.of(
                "elasticsearch", List.of("172.24.0.0/16"), "rerank", List.of("10.0.0.0/8")));
        denied(() -> new RagPublicDnsResolver(policy, host -> new InetAddress[]{addressUnchecked("10.0.0.2")})
                .resolve("elasticsearch"));
        denied(() -> new RagPublicDnsResolver(policy, host -> new InetAddress[]{addressUnchecked("172.24.0.2")})
                .resolve("unlisted"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "http://x.localhost", "http://localhost.example", "http://127.0.0.1",
            "http://169.254.169.254", "http://[::1]", "http://[::ffff:10.0.0.1]", "http://[fe80::1]",
            "http://[64:ff9b::a00:1]", "http://[2002:a00:1::1]", "http://224.0.0.1", "http://240.0.0.1",
            "http://10.1", "http://167772161", "http://012.0.0.1", "http://0xa000001", "http://0xa.0.0.1",
            "http://10.000.0.1", "http://user:secret@elasticsearch", "http://elasticsearch/#fragment",
            "http://elasticsearch:", "http://elasticsearch:00", "http://elasticsearch:65536",
            "http://elasticsearch:+9200", "http://elasticsearch%2eexample"})
    void managedModeNeverExemptsUnsafeOrAmbiguousUrls(String url) {
        var policy = managed(List.of("elasticsearch"), Map.of("elasticsearch", List.of("10.0.0.0/8")));
        denied(() -> policy.validateUrl(url));
    }

    @Test
    void checksAllAnswersRebindingAndLiveHostOrNetworkRemoval() throws Exception {
        var config = new AtomicReference<>(config(List.of("elasticsearch"), Map.of("elasticsearch", List.of("172.24.0.0/16"))));
        var policy = RagEndpointPolicy.configured(config::get);
        var answers = new AtomicReference<>(new InetAddress[]{address("172.24.0.2")});
        var lookups = new AtomicInteger();
        var dns = new RagPublicDnsResolver(policy, host -> { lookups.incrementAndGet(); return answers.get(); });
        assertEquals(address("172.24.0.2"), dns.resolve("elasticsearch")[0]);
        answers.set(new InetAddress[]{address("172.24.0.2"), address("8.8.8.8")});
        assertEquals(2, dns.resolve("elasticsearch").length); // both branches can be authorized in one answer
        answers.set(new InetAddress[]{address("172.24.0.2"), address("192.168.0.2")});
        denied(() -> dns.resolve("elasticsearch"));
        answers.set(new InetAddress[]{address("127.0.0.1")});
        denied(() -> dns.resolve("elasticsearch"));
        answers.set(new InetAddress[]{address("172.24.0.2")});
        config.set(config(List.of("elasticsearch"), Map.of()));
        denied(() -> dns.resolve("elasticsearch"));
        config.set(config(List.of("other"), Map.of()));
        denied(() -> dns.resolve("elasticsearch"));
        assertEquals(4, lookups.get()); // removal rejected before lookup; no permanent hostname cache
        denied(() -> new RagPublicDnsResolver(managed(List.of("other.example"), Map.of()),
                host -> new InetAddress[0]).resolve("other.example"));
    }

    @Test
    void rawAaaaAllowsBothUlaHalvesButNeverMappedTransitionOrSpecialAddresses() throws Exception {
        var policy = managed(List.of("elasticsearch"), Map.of("elasticsearch", List.of("fc00::/7")));
        var answers = new AtomicReference<>(new InetAddress[]{address("fc01::1"), address("fd12::1")});
        var dns = new RagPublicDnsResolver(policy, host -> answers.get());
        assertEquals(2, dns.resolve("elasticsearch").length);
        for (String special : List.of("::ffff:172.24.0.2", "::ffff:8.8.8.8", "64:ff9b::a00:1",
                "2002:a00:1::1", "2001::1", "fe80::1", "fec0::1", "ff02::1")) {
            denied(() -> RagDnsRecords.parseRecord(special, true));
            byte[] bytes = RagEndpointPolicy.literalAddress(special);
            answers.set(new InetAddress[]{java.net.Inet6Address.getByAddress(null, bytes, -1)});
            denied(() -> dns.resolve("elasticsearch"));
        }
    }

    @Test
    void literalPrivateAddressesUseTheirExactHostMapWithoutDnsAndAnswersAreNotProviderOwned() throws Exception {
        var policy = managed(List.of("10.117.102.54", "fc01::1"), Map.of(
                "10.117.102.54", List.of("10.117.102.54/32"), "fc01::1", List.of("fc01::1/128")));
        var dns = new RagPublicDnsResolver(policy, host -> { throw new AssertionError("literal lookup"); });
        assertEquals(address("10.117.102.54"), dns.resolve("10.117.102.54")[0]);
        assertEquals(address("fc01::1"), dns.resolve("[fc01::1]")[0]);
        policy.validateUrl("https://[fc01::1]:443/path");
        denied(() -> managed(List.of("10.117.102.54"), Map.of("10.117.102.54", List.of("10.117.102.55/32")))
                .validateUrl("http://10.117.102.54"));
        var provider = new InetAddress[]{address("172.24.0.2")};
        var privateDns = new RagPublicDnsResolver(managed(List.of("elasticsearch"),
                Map.of("elasticsearch", List.of("172.24.0.0/16"))), host -> provider);
        var result = privateDns.resolve("elasticsearch");
        assertNotSame(provider, result);
        provider[0] = address("127.0.0.1");
        assertEquals(address("172.24.0.2"), result[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "100.64.0.1", "192.0.0.9", "198.18.0.1",
            "224.0.0.1", "240.0.0.1", "::1", "::ffff:8.8.8.8", "fe80::1", "ff02::1", "64:ff9b::808:808", "2002::1"})
    void evenPreciselyAllowlistedSpecialLiteralsRemainDenied(String literal) {
        denied(() -> managed(List.of(literal), Map.of()).validateHost(literal));
    }

    @Test
    void oneDnsResolutionFreezesConfigAndDoesNotMixNewHostsWithOldCidrs() throws Exception {
        var mutableHosts = new ArrayList<>(List.of("elasticsearch"));
        var mutableRanges = new ArrayList<>(List.of("172.24.0.0/16"));
        var mutableMap = new HashMap<String, List<String>>();
        mutableMap.put("elasticsearch", mutableRanges);
        var before = config(mutableHosts, mutableMap);
        mutableHosts.clear();
        mutableRanges.clear();
        mutableMap.clear();
        var live = new AtomicReference<>(before);
        var reads = new AtomicInteger();
        var policy = RagEndpointPolicy.configured(() -> { reads.incrementAndGet(); return live.get(); });
        var dns = new RagPublicDnsResolver(policy, host -> {
            live.set(config(List.of("other"), Map.of("other", List.of("10.0.0.0/8"))));
            return new InetAddress[]{addressUnchecked("172.24.0.2")};
        });
        assertEquals(address("172.24.0.2"), dns.resolve("elasticsearch")[0]);
        assertEquals(1, reads.get());
        denied(() -> dns.resolve("elasticsearch"));
        assertEquals(2, reads.get());
    }

    private static InetAddress addressUnchecked(String literal) {
        try { return address(literal); } catch (Exception ex) { throw new AssertionError(ex); }
    }
}
