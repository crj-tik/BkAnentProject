package com.bkanent.rag.security.http;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/** Preserve AAAA bytes: JDK getAllByName silently collapses mapped IPv6 into Inet4Address. */
final class RagDnsRecords implements RagPublicDnsResolver.Lookup {
    private final RagEndpointPolicy policy;

    RagDnsRecords(RagEndpointPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        // JDK DNS provider uses the platform's configured recursive servers. No HTTP/URL transport.
        environment.put("java.naming.provider.url", "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "1000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = null;
        try {
            context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes(host.endsWith(".") ? host : host + ".", new String[]{"A", "AAAA"});
            List<InetAddress> addresses = new ArrayList<>();
            append(addresses, attributes.get("A"), false);
            append(addresses, attributes.get("AAAA"), true);
            return addresses.toArray(InetAddress[]::new);
        } catch (NamingException ex) {
            throw new UnknownHostException("RAG_DNS_UNAVAILABLE");
        } finally {
            if (context != null) {
                try { context.close(); } catch (NamingException ignored) { /* no untrusted provider details logged */ }
            }
        }
    }

    private void append(List<InetAddress> addresses, Attribute attribute, boolean ipv6) throws NamingException,
            UnknownHostException {
        if (attribute == null) {
            return;
        }
        NamingEnumeration<?> records = attribute.getAll();
        try {
            while (records.hasMore()) {
                addresses.add(parseRecord(policy, String.valueOf(records.next()), ipv6));
            }
        } finally {
            records.close();
        }
    }

    static InetAddress parseRecord(RagEndpointPolicy policy, String value, boolean ipv6) throws UnknownHostException {
        byte[] bytes = RagEndpointPolicy.literalAddress(value.toLowerCase(java.util.Locale.ROOT));
        if (bytes == null || bytes.length != (ipv6 ? 16 : 4)) {
            throw RagEndpointPolicy.denied();
        }
        InetAddress address = ipv6 ? Inet6Address.getByAddress(null, bytes, -1) : InetAddress.getByAddress(bytes);
        policy.requirePublic(address);
        return address;
    }
}
