package com.bkanent.common.memory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.AllowClassNotifyListener;
import org.apache.dubbo.common.utils.SerializeCheckStatus;
import org.apache.dubbo.common.utils.SerializeSecurityConfigurator;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the real v2 interface registration: every method declares the MemoryException
 * envelope, official fastjson2 registers exactly the declared family, and the wire
 * round-trips every error code. In-memory official serialization only, no provider.
 */
class MemoryV2RpcExceptionEnvelopeTest {

    @Test
    void v2InterfaceDeclarationRegistersTheMemoryExceptionEnvelope() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            var module = framework.newApplication().newModule();
            var configurator = module.getBeanFactory().getBean(SerializeSecurityConfigurator.class);
            var observed = new ObservedSecurity();
            framework.getBeanFactory().getBean(SerializeSecurityManager.class).registerListener(observed);
            URL context = URL.valueOf("dubbo://127.0.0.1:20898?serialization=fastjson2").setScopeModel(module);
            Serialization serialization = CodecSupport.getSerialization(context);

            assertThat(MemoryV2RpcService.class.getDeclaredMethods()).hasSize(14).allSatisfy(method ->
                    assertThat(Arrays.asList((Class<?>[]) method.getExceptionTypes()))
                            .containsExactly(MemoryException.class));
            assertThat(MemoryV2RpcService.CONTRACT_VERSION).isEqualTo(MemoryV2Contract.VERSION);

            configurator.registerInterface(MemoryV2RpcService.class);
            assertThat(observed.allowed).contains(MemoryException.class.getName(),
                    MemoryErrorCode.class.getName(), MemoryException.Field.class.getName());
            assertThat(observed.status).isEqualTo(SerializeCheckStatus.STRICT);
            for (MemoryErrorCode code : MemoryErrorCode.values()) {
                MemoryException restored = (MemoryException) serialization.deserialize(context,
                        new ByteArrayInputStream(wire(serialization, context,
                                new MemoryException(code, MemoryException.Field.GRANT)))).readThrowable();
                assertThat(restored.code()).isEqualTo(code);
                assertThat(restored.field()).isEqualTo(MemoryException.Field.GRANT);
                assertThat(restored.getCause()).isNull();
                assertThat(restored.getSuppressed()).isEmpty();
            }
        } finally {
            framework.destroy();
        }
    }

    private byte[] wire(Serialization serialization, URL context, Throwable failure) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(context, bytes);
        output.writeThrowable(failure);
        output.flushBuffer();
        return bytes.toByteArray();
    }

    private static final class ObservedSecurity implements AllowClassNotifyListener {
        private Set<String> allowed = Set.of();
        private SerializeCheckStatus status;
        @Override public void notifyPrefix(Set<String> allowed, Set<String> denied) { this.allowed = Set.copyOf(allowed); }
        @Override public void notifyCheckStatus(SerializeCheckStatus status) { this.status = status; }
        @Override public void notifyCheckSerializable(boolean value) { }
    }
}
