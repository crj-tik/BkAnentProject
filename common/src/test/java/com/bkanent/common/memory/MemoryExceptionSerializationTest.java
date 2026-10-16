package com.bkanent.common.memory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.serialize.SerializationException;
import org.apache.dubbo.common.serialize.fastjson2.Fastjson2SecurityManager;
import org.apache.dubbo.common.utils.AllowClassNotifyListener;
import org.apache.dubbo.common.utils.SerializeCheckStatus;
import org.apache.dubbo.common.utils.SerializeSecurityConfigurator;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** In-memory official serialization only. Fixture declarations are not implemented/exported RPCs. */
class MemoryExceptionSerializationTest {
    private static final String FOREIGN_THROWABLE = "untrusted.memory.fixture.ForeignFailure";

    @TempDir Path temporary;

    @Test void officialStrictRegistrationRequiresTheExplicitSafeExceptionDeclaration() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            var module = framework.newApplication().newModule();
            var configurator = module.getBeanFactory().getBean(SerializeSecurityConfigurator.class);
            var security = framework.getBeanFactory().getBean(SerializeSecurityManager.class);
            var observed = new ObservedSecurity();
            security.registerListener(observed);
            URL context = URL.valueOf("dubbo://127.0.0.1:20898?serialization=fastjson2").setScopeModel(module);
            Serialization serialization = CodecSupport.getSerialization(context);
            assertThat(serialization.getContentTypeId()).isEqualTo((byte) 23);
            configurator.registerInterface(UndeclaredFixture.class);
            byte[] original = wire(serialization, context,
                    new MemoryException(MemoryErrorCode.FORBIDDEN, MemoryException.Field.IDENTITY));
            assertThat(observed.allowed).doesNotContain(MemoryException.class.getName());
            assertStrictSerializable(observed);
            assertUntrustedThrowableWire(serialization, context, original, MemoryException.class.getName());
            configurator.registerInterface(DeclaredFixture.class);
            assertThat(observed.allowed)
                    .contains(MemoryException.class.getName(), MemoryErrorCode.class.getName(), MemoryException.Field.class.getName())
                    .doesNotContain("com.bkanent.", "com.bkanent.common.", "com.bkanent.common.memory.");
            assertStrictSerializable(observed);
            for (MemoryErrorCode code : MemoryErrorCode.values()) {
                for (MemoryException.Field field : MemoryException.Field.values()) {
                    MemoryException restored = (MemoryException) serialization.deserialize(context,
                            new ByteArrayInputStream(wire(serialization, context, new MemoryException(code, field)))).readThrowable();
                    assertThat(restored.code()).isEqualTo(code);
                    assertThat(restored.field()).isEqualTo(field);
                    assertThat(restored.getMessage()).isEqualTo(code.name() + " (" + field.name() + ")");
                    assertThat(restored.getCause()).isNull();
                    assertThat(restored.getSuppressed()).isEmpty();
                }
                MemoryException restored = (MemoryException) serialization.deserialize(context,
                        new ByteArrayInputStream(wire(serialization, context, new MemoryException(code)))).readThrowable();
                assertThat(restored.code()).isEqualTo(code);
                assertThat(restored.field()).isNull();
            }
        } finally {
            framework.destroy();
        }
    }

    @Test void safeExceptionDeclarationDoesNotTrustForeignThrowableOrDisableSerializableChecking() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            var module = framework.newApplication().newModule();
            var configurator = module.getBeanFactory().getBean(SerializeSecurityConfigurator.class);
            var observed = new ObservedSecurity();
            framework.getBeanFactory().getBean(SerializeSecurityManager.class).registerListener(observed);
            URL context = URL.valueOf("dubbo://127.0.0.1:20898?serialization=fastjson2").setScopeModel(module);
            Serialization serialization = CodecSupport.getSerialization(context);
            assertThat(serialization).isSameAs(framework.getExtensionLoader(Serialization.class).getExtension("fastjson2"));
            var manager = framework.getBeanFactory().getBean(Fastjson2SecurityManager.class);
            assertStrictSerializable(observed);
            assertThatThrownBy(() -> manager.getSecurityFilter().apply(ForeignFailure.class.getName(), Throwable.class, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            configurator.registerInterface(DeclaredFixture.class);
            assertStrictSerializable(observed);
            assertThat(observed.allowed).contains(MemoryException.class.getName())
                    .doesNotContain(ForeignFailure.class.getName(), FOREIGN_THROWABLE,
                            "com.bkanent.", "com.bkanent.common.", "com.bkanent.common.memory.");
            // The original nested fixture is not registered, but its enclosing test name starts
            // with MemoryException. Fastjson2's accepted-name prefix matching therefore trusts it.
            assertThat(observed.allowed.stream().filter(ForeignFailure.class.getName()::startsWith).toList())
                    .containsExactly(MemoryException.class.getName());
            assertThat(manager.getSecurityFilter().apply(ForeignFailure.class.getName(), Throwable.class, 0))
                    .isEqualTo(ForeignFailure.class);
            assertThat(serialization.deserialize(context,
                    new ByteArrayInputStream(wire(serialization, context, new ForeignFailure()))).readThrowable())
                    .isInstanceOf(ForeignFailure.class);

            try (var loader = foreignThrowableLoader()) {
                Class<?> foreignType = Class.forName(FOREIGN_THROWABLE, true, loader);
                Throwable failure = (Throwable) foreignType.getConstructor().newInstance();
                assertThat(observed.allowed.stream().filter(FOREIGN_THROWABLE::startsWith).toList()).isEmpty();
                ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(loader);
                    assertUntrustedThrowableWire(serialization, context, wire(serialization, context, failure), FOREIGN_THROWABLE);
                } finally {
                    Thread.currentThread().setContextClassLoader(originalLoader);
                }
            }
            assertThat(manager.getSecurityFilter().isCheckSerializable()).isTrue();
            assertThatThrownBy(() -> serialization.serialize(context, new ByteArrayOutputStream()).writeObject(new Object()))
                    .isInstanceOf(IOException.class).hasCauseInstanceOf(SerializationException.class);
            assertStrictSerializable(observed);
            assertThat(observed.allowed.stream().filter(FOREIGN_THROWABLE::startsWith).toList()).isEmpty();
        } finally {
            framework.destroy();
        }
    }

    private URLClassLoader foreignThrowableLoader() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("External untrusted fixture requires the project's Java 17 JDK").isNotNull();
        Path source = temporary.resolve("ForeignFailure.java");
        Files.writeString(source, """
                package untrusted.memory.fixture;
                public final class ForeignFailure extends RuntimeException {
                    private static final long serialVersionUID = 1L;
                    public ForeignFailure() { super("OFFLINE_FAILURE"); }
                }
                """, StandardCharsets.UTF_8);
        var diagnostics = new ByteArrayOutputStream();
        int compiled = compiler.run(null, diagnostics, diagnostics, "--release", "17", "-encoding", "UTF-8",
                "-d", temporary.toString(), source.toString());
        assertThat(compiled).as("External fixture compilation: %s", diagnostics.toString(StandardCharsets.UTF_8)).isZero();
        return new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, getClass().getClassLoader());
    }

    private void assertUntrustedThrowableWire(Serialization serialization, URL context, byte[] bytes, String typeName) {
        Throwable failure = catchThrowable(() -> serialization.deserialize(context,
                new ByteArrayInputStream(bytes)).readThrowable());
        assertThat(failure).isInstanceOf(IOException.class).hasCauseInstanceOf(SerializationException.class);
        Throwable rejected = failure.getCause().getCause();
        assertThat(rejected).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(typeName).hasMessageContaining("STRICT").hasMessageContaining("not in allow list");
        assertThat(rejected.getStackTrace()).anySatisfy(frame -> {
            assertThat(frame.getClassName()).isEqualTo(Fastjson2SecurityManager.Handler.class.getName());
            assertThat(frame.getMethodName()).isEqualTo("apply");
        }).anySatisfy(frame -> {
            assertThat(frame.getClassName()).isEqualTo("org.apache.dubbo.common.serialize.fastjson2.FastJson2ObjectInput");
            assertThat(frame.getMethodName()).isEqualTo("readObject");
        });
    }

    private void assertStrictSerializable(ObservedSecurity observed) {
        assertThat(observed.status).isEqualTo(SerializeCheckStatus.STRICT);
        assertThat(observed.checkSerializable).isTrue();
    }

    private byte[] wire(Serialization serialization, URL context, Throwable failure) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(context, bytes);
        output.writeThrowable(failure);
        output.flushBuffer();
        return bytes.toByteArray();
    }

    public interface UndeclaredFixture { MemoryContextKey read(); }
    public interface DeclaredFixture { MemoryContextKey read() throws MemoryException; }
    /** Retained only to demonstrate the original fixture's accepted-name prefix collision. */
    public static final class ForeignFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ForeignFailure() { super("OFFLINE_FAILURE"); }
    }
    private static final class ObservedSecurity implements AllowClassNotifyListener {
        private Set<String> allowed = Set.of();
        private SerializeCheckStatus status;
        private boolean checkSerializable;
        @Override public void notifyPrefix(Set<String> allowed, Set<String> denied) { this.allowed = Set.copyOf(allowed); }
        @Override public void notifyCheckStatus(SerializeCheckStatus status) { this.status = status; }
        @Override public void notifyCheckSerializable(boolean value) { checkSerializable = value; }
    }
}
