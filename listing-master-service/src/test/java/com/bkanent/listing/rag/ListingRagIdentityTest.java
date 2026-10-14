package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ListingRagIdentityTest {
    static final String DOMAIN = "domain-independent-secret-01234567890123456789";
    static final String AUTH = "auth-independent-response-01234567890123456789";
    static ListingRagProperties properties() {
        ListingRagProperties p = new ListingRagProperties(); p.setSecret(DOMAIN); p.setAuthResponseSecret(AUTH); return p;
    }
    static AuthRagPrincipal response(RagCredentialContext credentials, RagAuthEnvelope request, String role, boolean active) {
        long now = Instant.now().getEpochSecond();
        AuthRagPrincipal base = new AuthRagPrincipal(7L, role, active, true, now + 3600);
        RagAuthEnvelope proof = RagRequestSigner.sign(new RagAuthEnvelope(1, "auth-response-current", "listing-master-service",
                "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), RagCanonicalHash.principalHash(base),
                RagActorMode.USER, "7", now, now + 60, RagCanonicalHash.credentialDigest(credentials), null), AUTH.getBytes(StandardCharsets.UTF_8));
        return new AuthRagPrincipal(base.userId(), base.roleCode(), base.accountActive(), base.roleActive(), base.expiresAtEpochSecond(), proof);
    }
    @Test void requestUsesIndependentDomainKeyAndCredentialHashAndRealRole() {
        var p = properties(); p.setReadRoleCodes(Set.of("BROKER"));
        var factory = new StaticListableBeanFactory();
        factory.addBean("auth", (RagPrincipalRpcService) (credentials, request) -> {
            assertEquals("listing-master-service", request.callerService());
            assertEquals("auth-service", request.audience());
            assertEquals(RagCanonicalHash.credentialDigest(credentials), request.requestHash());
            RagRequestSigner.verify(request, request.requestHash(), "auth-service", RagOperation.RESOLVE_PRINCIPAL,
                    request.requestId(), credentials, new RagSigningKey("listing-current", "listing-master-service", null,
                            Set.of(RagOperation.RESOLVE_PRINCIPAL), DOMAIN.getBytes(StandardCharsets.UTF_8)), Instant.now());
            return response(credentials, request, "BROKER", true);
        });
        var identity = new ListingRagIdentity(p, factory.getBeanProvider(RagPrincipalRpcService.class));
        assertEquals(7L, identity.requireReader(new RagCredentialContext("access-token")).userId());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> identity.requireAdmin(new RagCredentialContext("access-token"))).code());
    }
    @Test void unsignedOrInactivePrincipalFailsClosedAndDefaultPolicyGrantsNothing() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        var identity = new ListingRagIdentity(properties(), factory.getBeanProvider(RagPrincipalRpcService.class));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> identity.resolve(new RagCredentialContext("token"))).code());
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> response(c, a, "ADMIN", false));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> identity.resolve(new RagCredentialContext("token"))).code());
        factory.addBean("auth", (RagPrincipalRpcService) (c, a) -> response(c, a, "ADMIN", true));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> identity.requireAdmin(new RagCredentialContext("token"))).code());
    }
    /** KI-101: real constructor autowiring with both the Dubbo FactoryBean product and its @Bean bridge. */
    @Test void allThreeRpcBridgesCoexistWithReferenceProductsAndUseTheirQualifiedProviders() throws Exception {
        var fixture = new WiringFixture();
        try (var context = fixture.context(null)) {
            assertDualCandidates(context, RagPrincipalRpcService.class, "principal", "ragPrincipalRpcService");
            assertDualCandidates(context, RagRetrievalRpcService.class, "retrieval", "ragRetrievalRpcService");
            assertDualCandidates(context, RagIndexRpcService.class, "index", "ragIndexRpcService");
            assertQualifiedProvider(context.getBean(ListingRagIdentity.class), "auth", context, "ragPrincipalRpcService");
            assertQualifiedProvider(context.getBean(ListingRagFacade.class), "rpc", context, "ragRetrievalRpcService");
            assertQualifiedProvider(context.getBean(ListingRagDispatcher.class), "rpc", context, "ragIndexRpcService");

            var request = new MockHttpServletRequest("POST", "/listings/rag/search");
            request.addHeader("Authorization", "Bearer wiring-token");
            var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            context.getBean(ListingRequestAuthenticationFilter.class).doFilter(request, response, (a, b) -> {
                calls.incrementAndGet();
                assertEquals(fixture.credentials, ListingRagCredentialContext.current());
                assertTrue(context.getBean(ListingRagFacade.class).search(
                        ListingRagFacadeTest.input(null, null, null), fixture.credentials, "7").matches().isEmpty());
            });
            assertEquals(200, response.getStatus());
            assertEquals(1, calls.get());
            assertThrows(RagException.class, ListingRagCredentialContext::current);
            assertEquals(3, fixture.principalCalls.get()); // Filter, search entry, post-retrieval revalidation.
            assertEquals(1, fixture.retrievalCalls.get());
            assertEquals(RagIndexState.ACCEPTED, context.getBean(ListingRagDispatcher.class).deliver("wiring-event").state());
            assertEquals(1, fixture.indexCalls.get());

            // The independent old permission RPC remains usable and is never substituted for signed RAG auth.
            assertEquals(7L, context.getBean(AuthPermissionRpcService.class).resolvePrincipal("wiring-token").userId());
            assertEquals(1, fixture.permissionCalls.get());
            fixture.principalFailure = RagErrorCode.AUTHENTICATION_FAILED;
            response = new MockHttpServletResponse();
            context.getBean(ListingRequestAuthenticationFilter.class).doFilter(request, response, (a, b) -> calls.incrementAndGet());
            assertEquals(401, response.getStatus());
            assertEquals(1, calls.get());
            fixture.principalFailure = RagErrorCode.DEPENDENCY_UNAVAILABLE;
            response = new MockHttpServletResponse();
            context.getBean(ListingRequestAuthenticationFilter.class).doFilter(request, response, (a, b) -> calls.incrementAndGet());
            assertEquals(503, response.getStatus());
            assertEquals(1, calls.get());
            assertEquals(1, fixture.retrievalCalls.get());
            assertEquals(1, fixture.permissionCalls.get());
        }
    }

    @Test void missingNamedBridgeDoesNotFallBackToRemainingReferenceProduct() throws Exception {
        for (String missing : List.of("ragPrincipalRpcService", "ragRetrievalRpcService", "ragIndexRpcService")) {
            var fixture = new WiringFixture();
            try (var context = fixture.context(missing)) {
                if ("ragPrincipalRpcService".equals(missing)) {
                    var request = new MockHttpServletRequest("POST", "/listings/rag/search");
                    request.addHeader("Authorization", "Bearer wiring-token");
                    var response = new MockHttpServletResponse();
                    context.getBean(ListingRequestAuthenticationFilter.class).doFilter(request, response, (a, b) -> fail("missing auth bridge"));
                    assertEquals(503, response.getStatus());
                    assertEquals(0, fixture.principalCalls.get());
                } else if ("ragRetrievalRpcService".equals(missing)) {
                    assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class,
                            () -> context.getBean(ListingRagFacade.class).search(
                                    ListingRagFacadeTest.input(null, null, null), fixture.credentials, "7")).code());
                    assertEquals(0, fixture.retrievalCalls.get());
                } else {
                    assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class,
                            () -> context.getBean(ListingRagDispatcher.class).deliver("wiring-event")).code());
                    assertEquals(0, fixture.indexCalls.get());
                    assertEquals("RETRY_PENDING", fixture.jdbc.queryForObject(
                            "SELECT state FROM listing_rag_outbox WHERE event_id='wiring-event'", String.class));
                }
                assertEquals(0, fixture.permissionCalls.get());
            }
        }
    }

    private static <T> void assertDualCandidates(AnnotationConfigApplicationContext context, Class<T> type,
                                                 String reference, String bridge) {
        assertEquals(Set.of(reference, bridge), Set.of(context.getBeanNamesForType(type)));
        assertTrue(context.getBean("&" + reference) instanceof FactoryBean<?>);
        assertSame(context.getBean(reference), context.getBean(bridge));
        assertFalse(context.getBeanFactory().getBeanDefinition(bridge).isPrimary());
        assertThrows(NoUniqueBeanDefinitionException.class, () -> context.getBeanProvider(type).getIfAvailable());
    }

    private static void assertQualifiedProvider(Object consumer, String field,
                                                AnnotationConfigApplicationContext context, String bridge) {
        ObjectProvider<?> provider = (ObjectProvider<?>) ReflectionTestUtils.getField(consumer, field);
        assertNotNull(provider);
        assertSame(context.getBean(bridge), provider.getIfAvailable());
    }

    /** Local products replace only network transport; the actual Distributed @Configuration and Spring resolver run. */
    private static class WiringFixture {
        final RagCredentialContext credentials = new RagCredentialContext("wiring-token");
        final AtomicInteger principalCalls = new AtomicInteger();
        final AtomicInteger retrievalCalls = new AtomicInteger();
        final AtomicInteger indexCalls = new AtomicInteger();
        final AtomicInteger permissionCalls = new AtomicInteger();
        final ListingRagProperties properties = properties();
        final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        final JdbcTemplate jdbc;
        RagErrorCode principalFailure;

        WiringFixture() throws Exception {
            properties.setReadRoleCodes(Set.of("BROKER"));
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE listing_rag_outbox(event_id VARCHAR(64) PRIMARY KEY,payload CLOB,state VARCHAR(32),attempts INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR(64),error_code VARCHAR(64))");
            var event = new RagIndexEvent(1, "wiring-request", "wiring-event", ListingRagIdentity.SPACE,
                    "listing:11", 1, "1", RagIndexOperation.DELETE, "0".repeat(64), null, Instant.now());
            jdbc.update("INSERT INTO listing_rag_outbox(event_id,payload,state,attempts) VALUES (?,?,?,0)",
                    event.eventId(), json.writeValueAsString(event), "PENDING");
        }

        AnnotationConfigApplicationContext context(String missingBridge) throws Exception {
            var context = new AnnotationConfigApplicationContext();
            context.setEnvironment(new MockEnvironment()); // Never load process secrets or deployment configuration.
            var factory = context.getBeanFactory();
            factory.registerSingleton("principal", new LocalReference<>(RagPrincipalRpcService.class, (credential, request) -> {
                principalCalls.incrementAndGet();
                if (principalFailure != null) throw new RagException(principalFailure);
                RagRequestSigner.verify(request, RagCanonicalHash.credentialDigest(credential), "auth-service",
                        RagOperation.RESOLVE_PRINCIPAL, request.requestId(), credential,
                        new RagSigningKey("listing-current", ListingRagIdentity.SERVICE, null,
                                Set.of(RagOperation.RESOLVE_PRINCIPAL), DOMAIN.getBytes(StandardCharsets.UTF_8)), Instant.now());
                return response(credential, request, "BROKER", true);
            }));
            factory.registerSingleton("retrieval", new LocalReference<>(RagRetrievalRpcService.class, (request, authentication, credential, scope) -> {
                retrievalCalls.incrementAndGet();
                return ListingRagFacadeTest.response(request, List.of());
            }));
            factory.registerSingleton("index", new LocalReference<>(RagIndexRpcService.class, new RagIndexRpcService() {
                public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope authentication) {
                    throw new AssertionError("Already accepted event must not be resubmitted");
                }
                public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope authentication) {
                    indexCalls.incrementAndGet();
                    RagRequestSigner.verify(authentication, RagCanonicalHash.requestHash(request), "rag-service",
                            RagOperation.GET_STATUS, request.requestId(), null,
                            new RagSigningKey("listing-current", ListingRagIdentity.SERVICE, null,
                                    Set.of(RagOperation.GET_STATUS), DOMAIN.getBytes(StandardCharsets.UTF_8)), Instant.now());
                    return new RagIndexStatus(1, request.requestId(), "wiring-event", ListingRagIdentity.SPACE,
                            "listing:11", 1, "0".repeat(64), RagIndexState.ACCEPTED, 1, false, null, null, null);
                }
            }));
            factory.registerSingleton("authPermissionRpcServiceBridge", new AuthPermissionRpcService() {
                public boolean validateToken(String token) { return resolvePrincipal(token) != null; }
                public AuthenticatedPrincipal resolvePrincipal(String token) {
                    permissionCalls.incrementAndGet();
                    return "wiring-token".equals(token) ? new AuthenticatedPrincipal(7L) : null;
                }
                public boolean hasPermission(Long userId, String permission) { return Long.valueOf(7).equals(userId); }
            });
            Class<?> distributed = Class.forName("com.bkanent.listing.ListingRpcReferenceConfiguration$Distributed");
            factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (distributed.isInstance(bean)) {
                        for (String field : List.of("principal", "retrieval", "index")) {
                            ReflectionTestUtils.setField(bean, field, factory.getBean(field));
                        }
                    }
                    return bean;
                }
            });
            context.register(distributed, ListingRagIdentity.class, ListingRagFacade.class, ListingRagDispatcher.class,
                    ListingRagGrantService.class, ListingRequestAuthenticationFilter.class);
            context.registerBean(ListingRagProperties.class, () -> properties);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(ListingMapper.class, () -> mock(ListingMapper.class));
            context.registerBean(ListingConverter.class, ListingConverter::new);
            context.registerBean(ListingKnowledgeDocumentAssembler.class, ListingKnowledgeDocumentAssembler::new);
            context.registerBean(ListingRagOutbox.class, () -> mock(ListingRagOutbox.class));
            if (missingBridge != null) context.addBeanFactoryPostProcessor(beans ->
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) beans).removeBeanDefinition(missingBridge));
            context.refresh();
            return context;
        }
    }

    private record LocalReference<T>(Class<T> type, T product) implements FactoryBean<T> {
        @Override public T getObject() { return product; }
        @Override public Class<?> getObjectType() { return type; }
        @Override public boolean isSingleton() { return true; }
    }

    @Test void credentialIsScopedAndNotInheritedOrGuessable() throws Exception {
        assertThrows(RagException.class, ListingRagCredentialContext::current);
        ListingRagCredentialContext.withCredential(new RagCredentialContext("token"), () -> {
            assertEquals("token", ListingRagCredentialContext.current().accessToken());
            Thread thread = new Thread(() -> assertThrows(RagException.class, ListingRagCredentialContext::current));
            thread.start(); try { thread.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
            return null;
        });
        assertThrows(RagException.class, ListingRagCredentialContext::current);
        assertThrows(RagException.class, () -> ListingRagCredentialContext.bearer("gateway-userId:7"));
    }
}
