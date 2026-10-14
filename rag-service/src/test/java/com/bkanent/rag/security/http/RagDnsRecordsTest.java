package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.naming.CommunicationException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.spi.NamingManager;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the real InitialDirContext facade without external DNS or a global JNDI factory builder. */
class RagDnsRecordsTest {
    private final RagEndpointPolicy policy = RagEndpointPolicy.configured(() ->
            new RagHttpPolicyConfiguration("MANAGED_INTERNAL", List.of("public.example"),
                    Map.of("public.example", List.of("172.24.0.0/16", "fc00::/7"))));

    private static BasicAttributes records(String type, String... values) {
        BasicAttributes attributes = new BasicAttributes(true);
        if (values.length > 0) {
            BasicAttribute attribute = new BasicAttribute(type);
            for (String value : values) attribute.add(value);
            attributes.put(attribute);
        }
        return attributes;
    }

    private static DirContext context(String[] ipv4, String[] ipv6) throws Exception {
        DirContext context = mock(DirContext.class);
        when(context.getAttributes(anyString(), any(String[].class))).thenAnswer(call -> {
            assertEquals("public.example.", call.getArgument(0));
            String[] types = call.getArgument(1);
            assertEquals(1, types.length, "mixed RR types become an unsafe/incompatible ANY query");
            return switch (types[0]) {
                case "A" -> records("A", ipv4);
                case "AAAA" -> records("AAAA", ipv6);
                default -> throw new AssertionError("unexpected RR type");
            };
        });
        return context;
    }

    private InetAddress[] resolve() throws UnknownHostException {
        return new RagPublicDnsResolver(policy, new RagDnsRecords(policy)).resolve("public.example");
    }

    @Test
    void freshJndiContextUsesPlatformServersAndSeparateTypesForEveryResolution() throws Exception {
        DirContext first = context(new String[]{"172.24.0.2", "8.8.8.8"}, new String[]{"fd12::1"});
        DirContext second = context(new String[]{"172.24.0.3"}, new String[]{});
        AtomicInteger contexts = new AtomicInteger();
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenAnswer(call -> {
                Hashtable<?, ?> environment = call.getArgument(0);
                assertEquals(Map.of("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory",
                        "java.naming.provider.url", "dns:",
                        "com.sun.jndi.dns.timeout.initial", "1000",
                        "com.sun.jndi.dns.timeout.retries", "1"), environment);
                return contexts.getAndIncrement() == 0 ? first : second;
            });
            InetAddress[] addresses = resolve();
            assertEquals(3, addresses.length);
            assertInstanceOf(Inet6Address.class, addresses[2]);
            assertEquals(16, addresses[2].getAddress().length);
            assertArrayEquals(new byte[]{(byte) 172, 24, 0, 3}, resolve()[0].getAddress());
            assertEquals(2, contexts.get());
        }
        for (DirContext fixture : List.of(first, second)) {
            var calls = inOrder(fixture);
            calls.verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"A"}));
            calls.verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"AAAA"}));
            calls.verify(fixture).close();
            verifyNoMoreInteractions(fixture);
        }
    }

    @Test
    void successfulEmptyAaaaDoesNotDiscardARecords() throws Exception {
        DirContext fixture = context(new String[]{"172.24.0.2"}, new String[]{});
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            assertEquals(1, resolve().length);
        }
        verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"AAAA"}));
        verify(fixture).close();
    }

    @Test
    void successfulEmptyAStillRequiresAndReturnsRawAaaa() throws Exception {
        DirContext fixture = context(new String[]{}, new String[]{"2606:4700::1111"});
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            InetAddress[] addresses = resolve();
            assertEquals(1, addresses.length);
            assertInstanceOf(Inet6Address.class, addresses[0]);
            assertEquals(16, addresses[0].getAddress().length);
        }
        verify(fixture).close();
    }

    @Test
    void successfulEmptyBothTypesFailsClosed() throws Exception {
        DirContext fixture = context(new String[]{}, new String[]{});
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            RagException failure = assertThrows(RagException.class, this::resolve);
            assertEquals(RagErrorCode.ENDPOINT_POLICY_DENIED, failure.code());
        }
        verify(fixture).close();
    }

    @ParameterizedTest
    @CsvSource({"A", "AAAA"})
    void eitherQueryFailureNeverReturnsPartialRecordsOrProviderDetails(String failedType) throws Exception {
        DirContext fixture = context(new String[]{"172.24.0.2"}, new String[]{});
        when(fixture.getAttributes(eq("public.example."), aryEq(new String[]{failedType})))
                .thenThrow(new CommunicationException("secret-provider-url"));
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            UnknownHostException failure = assertThrows(UnknownHostException.class, this::resolve);
            assertEquals("RAG_DNS_UNAVAILABLE", failure.getMessage());
            assertNull(failure.getCause());
        }
        if (failedType.equals("A")) {
            verify(fixture, never()).getAttributes(eq("public.example."), aryEq(new String[]{"AAAA"}));
        }
        verify(fixture).close();
    }

    @ParameterizedTest
    @CsvSource({"A, 127.0.0.1", "A, 192.168.1.2", "AAAA, ::ffff:8.8.8.8",
            "AAAA, ::ffff:172.24.0.2", "AAAA, fe80::1", "AAAA, 64:ff9b::808:808"})
    void unsafeAnswerInEitherTypeRejectsEntireResolution(String type, String unsafe) throws Exception {
        DirContext fixture = context(type.equals("A") ? new String[]{"172.24.0.2", unsafe}
                : new String[]{"172.24.0.2"}, type.equals("AAAA") ? new String[]{unsafe} : new String[]{});
        try (var naming = mockStatic(NamingManager.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            RagException failure = assertThrows(RagException.class, this::resolve);
            assertEquals(RagErrorCode.ENDPOINT_POLICY_DENIED, failure.code());
            assertNull(failure.getCause());
        }
        verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"A"}));
        verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"AAAA"}));
        verify(fixture).close();
    }

    @ParameterizedTest
    @CsvSource({"3, false", "4, true", "5, true"})
    void separateQueriesShareOneAggregateDeadlineAndNeverReturnExpiredAnswers(int expiredCheck,
                                                                             boolean queriedAaaa) throws Exception {
        DirContext fixture = context(new String[]{"172.24.0.2"}, new String[]{});
        long absoluteDeadline = 123456789L;
        AtomicInteger checks = new AtomicInteger();
        try (var naming = mockStatic(NamingManager.class); var deadline = mockStatic(RagHttpDeadline.class)) {
            naming.when(() -> NamingManager.getInitialContext(any())).thenReturn(fixture);
            deadline.when(() -> RagHttpDeadline.capture(Duration.ofSeconds(5))).thenReturn(absoluteDeadline);
            deadline.when(() -> RagHttpDeadline.remainingNanos(absoluteDeadline)).thenAnswer(call -> {
                if (checks.incrementAndGet() == expiredCheck) throw new RagException(RagErrorCode.TIMEOUT);
                return 1L;
            });
            RagException failure = assertThrows(RagException.class, this::resolve);
            assertEquals(RagErrorCode.TIMEOUT, failure.code());
            deadline.verify(() -> RagHttpDeadline.capture(Duration.ofSeconds(5)), times(1));
            deadline.verify(() -> RagHttpDeadline.remainingNanos(absoluteDeadline), times(expiredCheck));
            deadline.verifyNoMoreInteractions();
        }
        verify(fixture).getAttributes(eq("public.example."), aryEq(new String[]{"A"}));
        verify(fixture, times(queriedAaaa ? 1 : 0)).getAttributes(eq("public.example."), aryEq(new String[]{"AAAA"}));
        verify(fixture).close();
    }

    @Test
    void alreadyExpiredCallerBudgetOpensNoJndiContext() throws Exception {
        try (var naming = mockStatic(NamingManager.class);
             var ignored = RagHttpDeadline.until(System.nanoTime() - 1)) {
            RagException failure = assertThrows(RagException.class, this::resolve);
            assertEquals(RagErrorCode.TIMEOUT, failure.code());
            naming.verifyNoInteractions();
        }
    }
}
