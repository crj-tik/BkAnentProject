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
    RagDnsRecords(RagEndpointPolicy policy) {
        // Host authorization belongs to the resolver's single immutable per-connection snapshot.
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        // JDK DNS provider uses the platform's configured recursive servers. No HTTP/URL transport.
        environment.put("java.naming.provider.url", "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "1000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        long deadline = RagHttpDeadline.capture(java.time.Duration.ofSeconds(5));
        InitialDirContext context = null;
        try {
            RagHttpDeadline.remainingNanos(deadline);
            context = new InitialDirContext(environment);
            String name = host.endsWith(".") ? host : host + ".";
            // JNDI combines different requested RR types into QTYPE=ANY, which does not
            // guarantee all A/AAAA records and bypasses Docker's internal A/AAAA handling.
            RagHttpDeadline.remainingNanos(deadline);
            Attributes ipv4 = context.getAttributes(name, new String[]{"A"});
            RagHttpDeadline.remainingNanos(deadline);
            Attributes ipv6 = context.getAttributes(name, new String[]{"AAAA"});
            RagHttpDeadline.remainingNanos(deadline);
            List<InetAddress> addresses = new ArrayList<>();
            append(addresses, ipv4.get("A"), false);
            append(addresses, ipv6.get("AAAA"), true);
            RagHttpDeadline.remainingNanos(deadline);
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
                addresses.add(parseRecord(String.valueOf(records.next()), ipv6));
            }
        } finally {
            records.close();
        }
    }

    static InetAddress parseRecord(RagEndpointPolicy policy, String value, boolean ipv6) throws UnknownHostException {
        InetAddress address = parseRecord(value, ipv6);
        policy.requirePublic(address); // retained public-only test seam
        return address;
    }

    static InetAddress parseRecord(String value, boolean ipv6) throws UnknownHostException {
        if (value == null) {
            throw RagEndpointPolicy.denied();
        }
        byte[] bytes = RagEndpointPolicy.literalAddress(value.toLowerCase(java.util.Locale.ROOT));
        if (bytes == null || bytes.length != (ipv6 ? 16 : 4)) {
            throw RagEndpointPolicy.denied();
        }
        // Even a managed CIDR may never authorize special/transition/mapped bytes.
        if (bytes.length == 16 && (bytes[0] & 0xfe) != 0xfc) {
            RagEndpointPolicy.requirePublic(bytes);
        }
        return ipv6 ? Inet6Address.getByAddress(null, bytes, -1) : InetAddress.getByAddress(bytes);
    }
}
