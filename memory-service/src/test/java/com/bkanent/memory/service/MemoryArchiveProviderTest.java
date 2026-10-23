package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.rpc.MemoryV2RpcServiceImpl;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.annotation.DubboService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryArchiveProviderTest {
    @Test void providerIsRegisteredByDefaultAndPinsTheNativeInterface() throws Exception {
        var bootstrapFixture = new MemoryArchiveServiceTest.Fixture();
        var bootstrapSharing = new MemorySharingAccessService(bootstrapFixture.security, new MemoryAccessPolicy(),
                bootstrapFixture.sharing, bootstrapFixture.sources, bootstrapFixture.transactions);
        var bootstrapPreferences = new PreferenceHistoryService(bootstrapFixture.store, bootstrapFixture.security,
                bootstrapFixture.sources, new com.bkanent.memory.config.MemoryPreferenceProperties(), bootstrapFixture.transactions);
        var bootstrapMatter = new MemoryMatterService(bootstrapFixture.store, bootstrapFixture.security,
                bootstrapFixture.sources, bootstrapFixture.transactions);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MemoryArchiveService.class, () -> bootstrapFixture.service);
            context.registerBean(MemorySharingAccessService.class, () -> bootstrapSharing);
            context.registerBean(MemoryV2RequestSecurity.class, () -> bootstrapFixture.security);
            context.registerBean(MemoryMatterService.class, () -> bootstrapMatter);
            context.registerBean(PreferenceHistoryService.class, () -> bootstrapPreferences);
            context.register(MemoryV2RpcServiceImpl.class);
            context.refresh();
            assertTrue(context.containsBeanDefinition("memoryV2RpcServiceImpl"));
        }
        var f = new MemoryArchiveServiceTest.Fixture();
        var sharing = new MemorySharingAccessService(f.security, new MemoryAccessPolicy(), f.sharing, f.sources, f.transactions);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MemoryArchiveService.class, () -> f.service);
            context.registerBean(MemorySharingAccessService.class, () -> sharing);
            context.registerBean(MemoryV2RequestSecurity.class, () -> f.security);
            context.registerBean(MemoryMatterService.class, () -> new MemoryMatterService(f.store, f.security, f.sources, f.transactions));
            context.registerBean(PreferenceHistoryService.class, () -> new PreferenceHistoryService(f.store, f.security, f.sources,
                    new com.bkanent.memory.config.MemoryPreferenceProperties(), f.transactions));
            context.register(MemoryV2RpcServiceImpl.class);
            context.refresh();
            assertNotNull(context.getBean(MemoryV2RpcService.class));
        }
        DubboService binding = MemoryV2RpcServiceImpl.class.getAnnotation(DubboService.class);
        assertEquals(MemoryV2Contract.DUBBO_GROUP, binding.group());
        assertEquals(MemoryV2Contract.DUBBO_SERVICE_VERSION, binding.version());
        assertArrayEquals(new String[]{"dubbo"}, binding.protocol());
    }

    @Test void nativeDubboProviderAndConsumerInvokeRealArchiveWithoutAnyContainerOrRegistry() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        var sharing = new MemorySharingAccessService(f.security, new MemoryAccessPolicy(), f.sharing, f.sources, f.transactions);
        var preferences = new PreferenceHistoryService(f.store, f.security, f.sources, new com.bkanent.memory.config.MemoryPreferenceProperties(), f.transactions);
        var implementation = new MemoryV2RpcServiceImpl(f.service, sharing, f.security, preferences, new MemoryMatterService(f.store, f.security, f.sources, f.transactions));
        var provider = new ProviderConfig();
        var application = new ApplicationConfig("memory-archive-fixture-" + UUID.randomUUID());
        application.setQosEnable(false);
        provider.setApplication(application);
        provider.setRegistry(new RegistryConfig("N/A"));
        provider.setProtocol(new ProtocolConfig("dubbo", -1));
        var service = MemoryV2ProviderBinder.bind(implementation, provider);
        service.setScope("local");
        var reference = new org.apache.dubbo.config.ReferenceConfig<MemoryV2RpcService>();
        reference.setInterface(MemoryV2RpcService.class);
        reference.setGroup(MemoryV2Contract.DUBBO_GROUP);
        reference.setVersion(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        reference.setProtocol("dubbo");
        reference.setApplication(application);
        reference.setRegistry(new RegistryConfig("N/A"));
        reference.setInjvm(true);
        try {
            service.export();
            var rpc = reference.get();
            var message = MemoryMatterServiceTest.message(MemoryArchiveServiceTest.CONTEXT, 1, new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "native RPC matter"));
            var permitRequest = f.permitRequest(List.of(message), List.of(), "rpc-permit");
            var permit = rpc.requestReceivePermit(permitRequest, f.sign(permitRequest));
            var delivery = f.request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, e -> new MemoryDeliveryRequest(e, MemoryArchiveServiceTest.CONTEXT, message.association(), permit.permitId(), List.of(message)));
            assertEquals(1, rpc.deliverMessages(delivery, f.signService(delivery)).version().lastSequence());
            var read = f.request(MemoryV2Contract.OPERATION_WORKING_SET, envelope -> new MemoryWorkingSetRequest(envelope, MemoryArchiveServiceTest.CONTEXT));
            assertEquals(List.of(message), rpc.readWorkingSet(read, f.sign(read)).messages());
            var writer = f.request(MemoryV2Contract.OPERATION_WRITER, e -> new MemoryWriterRequest(e, MemoryArchiveServiceTest.CONTEXT, MemoryWriterRequest.Action.ACQUIRE, "run-1", 0));
            var authority = rpc.manageWriter(writer, f.sign(writer)).authority();
            var matterWrite = f.request(MemoryV2Contract.OPERATION_MATTER_WRITE, e -> new MemoryMatterWriteRequest(e, MemoryArchiveServiceTest.CONTEXT, message.matterIntent(), new MemorySourceDependency(message.source(), null), 1, authority));
            var matterReceipt = rpc.transitionMatter(matterWrite, f.sign(matterWrite));
            assertEquals(MemoryMatter.State.ACTIVE, matterReceipt.matter().state());
            assertEquals(matterReceipt, rpc.transitionMatter(matterWrite, f.sign(matterWrite)));
            var write = f.request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE, e -> new MemoryPreferenceWriteRequest(e, MemoryArchiveServiceTest.CONTEXT, 0,
                    new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE, PreferenceTransition.Change.OBSERVATION,
                            PreferenceTransition.Polarity.NEGATIVE, MemoryJsonValue.number("100"), null, null, null, null, List.of(), .8,
                            "extract-v1", new MemorySourceDependency(message.source(), null))));
            var client = new com.bkanent.memory.runtime.MemoryRpcClient(rpc, MemoryArchiveServiceTest.CALLER, "fixture-key", f.identityPair.getPrivate(),
                    () -> new com.bkanent.memory.runtime.MemoryRpcClient.Actor(new com.bkanent.common.rpc.AuthenticatedPrincipal(42L), java.time.Instant.now().getEpochSecond()+60));
            var accepted = client.submitPreference(MemoryArchiveServiceTest.CONTEXT, 0, write.candidate());
            var asOf = java.time.Instant.now();
            var history = f.request(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL, e -> new MemoryPreferenceDetailRequest(e, MemoryArchiveServiceTest.CONTEXT,
                    accepted.blockId(), null, 10, asOf, null));
            assertEquals(PreferenceTransition.Polarity.NEGATIVE, client.preferenceHistory(MemoryArchiveServiceTest.CONTEXT,
                    accepted.blockId(), null, 10, history.asOf(), null).effectiveState().polarity());
            var delete = f.request(MemoryV2Contract.OPERATION_WITHDRAW, envelope -> new MemoryWithdrawalRequest(envelope, MemoryArchiveServiceTest.CONTEXT, "event-1", true));
            assertEquals(MemoryState.DELETION_PENDING, rpc.withdrawSource(delete, f.sign(delete)).state());
        } finally {
            reference.destroy();
            service.unexport();
        }
    }
}
