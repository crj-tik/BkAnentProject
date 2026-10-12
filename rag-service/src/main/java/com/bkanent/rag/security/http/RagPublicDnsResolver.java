package com.bkanent.rag.security.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Used by both HTTP stacks at connection creation, not as a preflight DNS check. */
public final class RagPublicDnsResolver implements org.apache.hc.client5.http.DnsResolver, org.apache.http.conn.DnsResolver {
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final RagEndpointPolicy policy;
    private final Lookup lookup;
    private final java.util.concurrent.ExecutorService executor;

    public RagPublicDnsResolver(RagEndpointPolicy policy) {
        this(policy, new RagDnsRecords(policy));
    }

    public RagPublicDnsResolver(RagEndpointPolicy policy, Lookup lookup) {
        this(policy, lookup, null);
    }

    public RagPublicDnsResolver(RagEndpointPolicy policy, Lookup lookup,
                                java.util.concurrent.ExecutorService executor) {
        this.policy = policy;
        this.lookup = lookup;
        this.executor = executor;
    }

    private InetAddress[] lookup(String host) throws UnknownHostException {
        if (executor == null) {
            return lookup.resolve(host); // deterministic test seam; production uses bounded DNS executor
        }
        long deadline = RagHttpDeadline.capture(java.time.Duration.ofSeconds(5));
        java.util.concurrent.Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> lookup.resolve(host));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            return future.get(RagHttpDeadline.remainingNanos(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);
            throw RagHttpDeadline.timeout();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw RagHttpDeadline.timeout();
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof com.bkanent.common.rag.RagException rag) {
                throw rag;
            }
            throw new UnknownHostException("RAG_DNS_UNAVAILABLE");
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        String normalized = policy.validateHost(host);
        byte[] literal = RagEndpointPolicy.literalAddress(normalized);
        InetAddress[] addresses;
        try {
            addresses = literal == null ? lookup(normalized)
                    : new InetAddress[]{InetAddress.getByAddress(literal)};
        } catch (UnknownHostException ex) {
            // The OS resolver can embed the host in its error; never retain that cause.
            throw new UnknownHostException("RAG_DNS_UNAVAILABLE");
        }
        if (addresses == null || addresses.length == 0) {
            throw RagEndpointPolicy.denied();
        }
        for (InetAddress address : addresses) {
            policy.requirePublic(address);
        }
        // Return the entire validated answer unchanged; clients connect to these InetAddress bytes.
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Never call InetAddress#getCanonicalHostName: that performs a second, uncontrolled lookup.
        return policy.validateHost(host);
    }
}
