package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** No I/O. Allowlisting never exempts a host from the public-address requirement. */
public final class RagEndpointPolicy {
    private final Supplier<Set<String>> allowedHosts;

    public RagEndpointPolicy() {
        this(Set::of);
    }

    public RagEndpointPolicy(Supplier<Set<String>> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public URI validateUrl(String value) {
        try {
            return validateUrl(URI.create(value));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw denied();
        }
    }

    public URI validateUrl(URI uri) {
        if (uri == null || uri.isOpaque() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getHost() == null
                || uri.getRawAuthority() == null || uri.getRawAuthority().contains("%")
                || uri.getRawAuthority().contains("@") || uri.getPort() == 0 || uri.getPort() > 65535
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw denied();
        }
        // URI#getPort returns -1 for no port; reject malformed/empty port authorities explicitly.
        String authority = uri.getRawAuthority();
        String host = uri.getHost();
        String suffix = authority.substring(host.length());
        if (!suffix.isEmpty() && !suffix.matches(":[1-9][0-9]{0,4}")) {
            throw denied();
        }
        validateHost(host);
        return uri;
    }

    public String validateHost(String host) {
        String normalized = normalize(host);
        Set<String> configured = allowedHosts.get();
        if (configured == null) {
            throw denied();
        }
        if (!configured.isEmpty() && configured.stream().map(RagEndpointPolicy::normalize)
                .noneMatch(normalized::equals)) {
            throw denied();
        }
        byte[] literal = literalAddress(normalized);
        if (literal != null) {
            requirePublic(literal);
        } else if (normalized.endsWith(".arpa")) {
            throw denied(); // infrastructure/reverse-DNS names are not public HTTP endpoints
        }
        return normalized;
    }

    static String normalize(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.indexOf('%') >= 0) {
            throw denied();
        }
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            parseV6(host); // no name lookup, scopes or zone identifiers
            return host;
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw denied();
        }
        if (host.length() > 253 || host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("local") || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".home.arpa") || host.endsWith(".onion")) {
            throw denied();
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.equals("localhost") || !label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw denied();
            }
        }
        if (host.matches("[0-9.]+")) {
            parseV4(host); // rejects integer, octal, shorthand and leading-zero spellings
        } else if (labels.length < 2 || labels[labels.length - 1].matches("[0-9]+|0x[0-9a-f]+")
                || host.matches("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")) {
            throw denied();
        }
        return host;
    }

    static byte[] literalAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return parseV6(host);
        }
        return host.matches("[0-9.]+") ? parseV4(host) : null;
    }

    public void requirePublic(InetAddress address) {
        if (address == null) {
            throw denied();
        }
        requirePublic(address.getAddress());
    }

    private static void requirePublic(byte[] address) {
        if (address.length == 4) {
            int a = address[0] & 255;
            int b = address[1] & 255;
            int c = address[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99) || b == 168
                    || (b == 31 && c == 196) || (b == 52 && c == 193) || (b == 175 && c == 48)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113)) {
                throw denied();
            }
        } else if (address.length == 16) {
            // Only allocated global unicast; reject mapped/compatible, NAT64, ULA, link/site local,
            // multicast, documentation, transition and IANA special-purpose allocations.
            int first = ((address[0] & 255) << 8) | (address[1] & 255);
            int second = ((address[2] & 255) << 8) | (address[3] & 255);
            if ((first & 0xe000) != 0x2000 || (first == 0x2001 && second < 0x0200)
                    || (first == 0x2001 && second == 0x0db8) || first == 0x2002
                    || (first == 0x2620 && second == 0x004f && (address[4] & 255) == 0x80 && address[5] == 0)
                    || (first == 0x3fff && (second & 0xf000) == 0)) {
                throw denied();
            }
        } else {
            throw denied();
        }
    }

    private static byte[] parseV4(String host) {
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4) {
            throw denied();
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!segments[i].matches("0|[1-9][0-9]{0,2}")) {
                throw denied();
            }
            int number = Integer.parseInt(segments[i]);
            if (number > 255) {
                throw denied();
            }
            bytes[i] = (byte) number;
        }
        return bytes;
    }

    private static byte[] parseV6(String host) {
        if (!host.matches("[0-9a-f:.]+")) {
            throw denied();
        }
        if (host.contains(".")) {
            int last = host.lastIndexOf(':');
            byte[] v4 = parseV4(host.substring(last + 1));
            host = host.substring(0, last + 1)
                    + Integer.toHexString(((v4[0] & 255) << 8) | (v4[1] & 255)) + ":"
                    + Integer.toHexString(((v4[2] & 255) << 8) | (v4[3] & 255));
        }
        int compressed = host.indexOf("::");
        if (compressed >= 0 && host.indexOf("::", compressed + 2) >= 0) {
            throw denied();
        }
        String left = compressed >= 0 ? host.substring(0, compressed) : host;
        String right = compressed >= 0 ? host.substring(compressed + 2) : "";
        String[] head = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] tail = right.isEmpty() ? new String[0] : right.split(":", -1);
        int count = head.length + tail.length;
        if ((compressed < 0 && count != 8) || (compressed >= 0 && count >= 8)) {
            throw denied();
        }
        byte[] result = new byte[16];
        putGroups(result, 0, head);
        putGroups(result, 8 - tail.length, tail);
        return result;
    }

    private static void putGroups(byte[] result, int offset, String[] groups) {
        for (int i = 0; i < groups.length; i++) {
            if (!groups[i].matches("[0-9a-f]{1,4}")) {
                throw denied();
            }
            int value = Integer.parseInt(groups[i], 16);
            result[(offset + i) * 2] = (byte) (value >>> 8);
            result[(offset + i) * 2 + 1] = (byte) value;
        }
    }

    static RagException denied() {
        // Deliberately no URL, address, provider exception/cause, credentials or payload.
        return new RagException(RagErrorCode.ENDPOINT_POLICY_DENIED);
    }
}
