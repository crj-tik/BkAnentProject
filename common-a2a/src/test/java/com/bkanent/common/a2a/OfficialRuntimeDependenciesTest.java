package com.bkanent.common.a2a;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.common.rpc.RagSpaceAdminRpcService;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.serialize.SerializationException;
import org.apache.dubbo.common.serialize.fastjson2.Fastjson2SecurityManager;
import org.apache.dubbo.common.serialize.support.PreferSerializationProviderImpl;
import org.apache.dubbo.common.utils.AllowClassNotifyListener;
import org.apache.dubbo.common.utils.SerializeCheckStatus;
import org.apache.dubbo.common.utils.SerializeSecurityConfigurator;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.remoting.http.RestClient;
import org.apache.dubbo.remoting.http.config.HttpClientConfig;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Offline checks only: no application context, provider export, reference.get(), or HTTP/RPC call. */
class OfficialRuntimeDependenciesTest {
    @TempDir Path temporary;

    @Test void patchPreservesUpstreamDependenciesAndSharedModuleOwnsRequiredHttpRuntime() throws Exception {
        Map<String, Element> declared = dependencies("a2a-sdk-transport-patch/pom.xml");
        check(declared.keySet().equals(java.util.Set.of("io.github.a2asdk:a2a-java-sdk-client",
                "io.github.a2asdk:a2a-java-sdk-common", "io.github.a2asdk:a2a-java-sdk-spec")),
                "Official SDK patch must preserve upstream dependencies without Dubbo runtime dependencies");
        for (String artifact : java.util.List.of("client", "common", "spec")) {
            Element dependency = declared.get("io.github.a2asdk:a2a-java-sdk-" + artifact);
            check("${upstream.version}".equals(text(dependency, "version")), "Upstream SDK version must be preserved");
            check("client".equals(artifact) ? "true".equals(text(dependency, "optional"))
                    : text(dependency, "optional") == null, "Original SDK binary must remain build-only optional");
        }
        Map<String, Element> shared = dependencies("common-a2a/pom.xml");
        check(shared.containsKey("org.apache.dubbo:dubbo-spring-boot-starter"), "Shared module must own Dubbo");
        Element okhttp = shared.get("com.squareup.okhttp3:okhttp");
        check(okhttp != null && "4.12.0".equals(text(okhttp, "version")) && "runtime".equals(text(okhttp, "scope"))
                && text(okhttp, "optional") == null, "OkHttp must reach downstream packaged runtimes");
    }

    @Test void dubboDefaultHttpClientLinksAndConstructsWithoutNetwork() throws Exception {
        for (String type : java.util.List.of("okhttp3.Callback", "okhttp3.OkHttpClient", "okio.Buffer",
                "kotlin.jvm.internal.Intrinsics")) {
            Class.forName(type).getDeclaredMethods();
        }
        Class<?> implementation = Class.forName("org.apache.dubbo.remoting.http.restclient.OKHttpRestClient");
        implementation.getDeclaredMethods();
        RestClient client = (RestClient) implementation.getConstructor(HttpClientConfig.class)
                .newInstance(new HttpClientConfig());
        try {
            check(client != null, "Official Dubbo OkHttp client must construct without missing dependencies");
        } finally {
            client.close();
        }
    }

    @Test void providerDefaultSupportsExplicitFastjson2AndJava17PrincipalRoundTrip() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            String preference = new PreferSerializationProviderImpl(framework).getPreferSerialization();
            check("fastjson2,hessian2".equals(preference), "Checked-in Dubbo provider defaults must support fastjson2");
            URL provider = URL.valueOf("dubbo://127.0.0.1:20881").setScopeModel(framework)
                    .addParameter("prefer.serialization", preference);
            CodecSupport.checkSerialization("fastjson2", provider);
            URL direct = URL.valueOf("dubbo://127.0.0.1:20881?serialization=fastjson2").setScopeModel(framework);
            var serialization = CodecSupport.getSerialization(direct);
            check(serialization.getContentTypeId() == 23, "Direct reference must select official fastjson2 wire ID 23");
            // fastjson2 2.0.52 crashed inside JSONB readInstant before the allowlist could judge the
            // principal; 2.0.58 decodes it, so trust must come from the same exact-class registration
            // pattern production uses for declared RPC payloads (KI-113), not an implicit allow.
            var module = framework.newApplication().newModule();
            module.getBeanFactory().getBean(SerializeSecurityManager.class)
                    .addToAllowed(AuthenticatedPrincipal.class.getName());
            var bytes = new ByteArrayOutputStream();
            var output = serialization.serialize(direct, bytes);
            output.writeObject(new AuthenticatedPrincipal(7L));
            output.flushBuffer();
            var input = serialization.deserialize(direct, new ByteArrayInputStream(bytes.toByteArray()));
            check(new AuthenticatedPrincipal(7L).equals(input.readObject(AuthenticatedPrincipal.class)),
                    "Java 17 auth principal record must round-trip without add-opens");
        } finally {
            framework.destroy();
        }
    }

    @Test void declaredRagFailuresRegisterExactClassesAndRoundTripThroughOfficialThrowableWire() throws Exception {
        for (Class<?> contract : List.of(RagPrincipalRpcService.class, RagRetrievalRpcService.class,
                RagIndexRpcService.class, RagSpaceAdminRpcService.class)) {
            for (Method method : contract.getDeclaredMethods()) {
                check(Arrays.equals(method.getExceptionTypes(), new Class<?>[]{RagException.class}),
                        "Every RAG RPC method must explicitly declare the safe unchecked failure contract");
            }
            FrameworkModel framework = new FrameworkModel();
            try {
                var module = framework.newApplication().newModule();
                var configurator = module.getBeanFactory().getBean(SerializeSecurityConfigurator.class);
                var security = framework.getBeanFactory().getBean(SerializeSecurityManager.class);
                var observed = new ObservedSerializationSecurity();
                security.registerListener(observed);
                URL direct = URL.valueOf("dubbo://127.0.0.1:20898?serialization=fastjson2").setScopeModel(module);
                Serialization serialization = CodecSupport.getSerialization(direct);
                check(serialization.getContentTypeId() == 23, "Offline wire must select official fastjson2");
                byte[] notReady = throwableWire(serialization, direct,
                        new RagException(RagErrorCode.SPACE_NOT_READY, "spaceId"));

                configurator.registerInterface(UndeclaredFailureRpc.class);
                check(!observed.allowed.contains(RagException.class.getName()),
                        "An undeclared runtime exception must not be implicitly trusted");
                assertStrictSerializable(observed);
                assertUntrustedThrowableWire(serialization, direct, notReady);

                configurator.registerInterface(contract);
                check(observed.allowed.contains(RagException.class.getName())
                                && observed.allowed.contains(RagErrorCode.class.getName()),
                        "Official interface initialization must register the declared exception and its enum field");
                check(!observed.allowed.contains("com.bkanent.")
                                && !observed.allowed.contains("com.bkanent.common.")
                                && !observed.allowed.contains("com.bkanent.common.rag."),
                        "Trust must remain exact classes, never the application or RAG package");
                assertStrictSerializable(observed);
                RagException readyFailure = (RagException) serialization.deserialize(direct,
                        new ByteArrayInputStream(notReady)).readThrowable();
                check(readyFailure.code() == RagErrorCode.SPACE_NOT_READY && "spaceId".equals(readyFailure.field()),
                        "The same previously rejected bytes must preserve the safe remote failure");

                for (RagErrorCode code : RagErrorCode.values()) {
                    for (String field : Arrays.asList(null, "spaceId", "access-token-MUST-NOT-ECHO")) {
                        RagException original = new RagException(code, field);
                        Throwable result = serialization.deserialize(direct,
                                new ByteArrayInputStream(throwableWire(serialization, direct, original))).readThrowable();
                        check(result instanceof RagException, "Official readThrowable must restore the actual exception type");
                        RagException decoded = (RagException) result;
                        check(decoded.code() == code && Objects.equals(decoded.field(), original.field())
                                        && decoded.getMessage().equals(original.getMessage()),
                                "Final fields and constructor-based decoding must preserve code and sanitized field");
                        check(decoded.getCause() == null && decoded.getSuppressed().length == 0
                                        && !decoded.toString().contains("access-token"),
                                "The public failure must not gain a provider cause or unsanitized field");
                    }
                }
            } finally {
                framework.destroy();
            }
        }
    }

    @Test void declaredRagFailureDoesNotPermitUnknownThrowableOrDisableSerializableChecking() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            var module = framework.newApplication().newModule();
            var security = framework.getBeanFactory().getBean(SerializeSecurityManager.class);
            var observed = new ObservedSerializationSecurity();
            security.registerListener(observed);
            module.getBeanFactory().getBean(SerializeSecurityConfigurator.class)
                    .registerInterface(RagRetrievalRpcService.class);
            URL direct = URL.valueOf("dubbo://127.0.0.1:20898?serialization=fastjson2").setScopeModel(module);
            Serialization serialization = CodecSupport.getSerialization(direct);
            byte[] unknown = throwableWire(serialization, direct, new UntrustedThrowable("OFFLINE_SAFE_FAILURE"));
            assertUntrustedThrowableWire(serialization, direct, unknown);
            check(!observed.allowed.contains(UntrustedThrowable.class.getName()),
                    "Registering a RAG contract must not trust an unrelated Throwable");
            assertStrictSerializable(observed);
            check(framework.getBeanFactory().getBean(Fastjson2SecurityManager.class)
                            .getSecurityFilter().isCheckSerializable(),
                    "The real fastjson2 input/output path must retain Serializable checking");
            try {
                serialization.serialize(direct, new ByteArrayOutputStream()).writeObject(new Object());
                throw new AssertionError("Official writeObject must reject a non-Serializable object");
            } catch (IOException expected) {
                check(expected.getCause() instanceof SerializationException,
                        "Non-Serializable failure must come from the official serialization wrapper");
            }
            assertStrictSerializable(observed);
        } finally {
            framework.destroy();
        }
    }

    private static byte[] throwableWire(Serialization serialization, URL direct, Throwable failure) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(direct, bytes);
        output.writeThrowable(failure);
        output.flushBuffer();
        return bytes.toByteArray();
    }

    private static void assertUntrustedThrowableWire(Serialization serialization, URL direct, byte[] wire) throws Exception {
        try {
            serialization.deserialize(direct, new ByteArrayInputStream(wire)).readThrowable();
            throw new AssertionError("An untrusted Throwable must be rejected by official readThrowable");
        } catch (IOException expected) {
            check(expected.getCause() instanceof SerializationException
                            && expected.getCause().getCause() instanceof IllegalArgumentException,
                    "Official input must preserve IOException -> SerializationException -> IllegalArgumentException");
            check(Arrays.stream(expected.getCause().getCause().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(Fastjson2SecurityManager.Handler.class.getName())
                                    && frame.getMethodName().equals("apply")),
                    "The rejection must originate in the real fastjson2 security handler");
            check(Arrays.stream(expected.getCause().getCause().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals("org.apache.dubbo.common.serialize.fastjson2.FastJson2ObjectInput")
                                    && frame.getMethodName().equals("readObject")),
                    "The regression must exercise actual fastjson2 input rather than constructing a result directly");
        }
    }

    private static void assertStrictSerializable(ObservedSerializationSecurity observed) {
        check(observed.status == SerializeCheckStatus.STRICT && observed.checkSerializable,
                "Official security initialization must keep STRICT and Serializable checking enabled");
    }

    public interface UndeclaredFailureRpc {
        AuthenticatedPrincipal resolve();
    }

    public static final class UntrustedThrowable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UntrustedThrowable(String message) { super(message); }
    }

    private static final class ObservedSerializationSecurity implements AllowClassNotifyListener {
        private Set<String> allowed = Set.of();
        private SerializeCheckStatus status;
        private boolean checkSerializable;
        @Override public void notifyPrefix(Set<String> allowedList, Set<String> disAllowedList) {
            allowed = Set.copyOf(allowedList);
        }
        @Override public void notifyCheckStatus(SerializeCheckStatus status) { this.status = status; }
        @Override public void notifyCheckSerializable(boolean checkSerializable) {
            this.checkSerializable = checkSerializable;
        }
    }

    @Test void actualProbeAddsOnlyFixedSerializationForEveryDirectReferenceAndRejectsQueries() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "Private probe regression requires a real JDK");
        Path classes = Files.createDirectory(temporary.resolve("probe-classes"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var diagnostics = new ByteArrayOutputStream();
        int compiled = compiler.run(null, diagnostics, diagnostics, "--release", "17", "-encoding", "UTF-8",
                "-classpath", classpath, "-d", classes.toString(),
                repository().resolve("scripts/verification/RagRuntimeProbe.java").toString());
        check(compiled == 0, "Actual private probe must compile against the runtime classpath");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> probe = Class.forName("com.bkanent.verification.RagRuntimeProbe", false, loader);
            Method directUrl = probe.getDeclaredMethod("directUrl", String.class);
            directUrl.setAccessible(true);
            for (String endpoint : java.util.List.of("dubbo://127.0.0.1:20881", "dubbo://127.0.0.1:20898",
                    "dubbo://127.0.0.1:20881/com.bkanent.common.rpc.A2aBeaconRpcService")) {
                check((endpoint + "?serialization=fastjson2").equals(directUrl.invoke(null, endpoint)),
                        "Auth, RAG, and beacon direct URLs must use one fixed official serialization parameter");
            }
            for (String invalid : java.util.List.of("dubbo://127.0.0.1:20881?serialization=hessian2",
                    "dubbo://127.0.0.1:20881?serialization=fastjson2", "dubbo://127.0.0.1:20881?",
                    "dubbo://user:password@127.0.0.1:20881", "dubbo://127.0.0.1:20881#fragment",
                    "http://127.0.0.1:20881", "dubbo://127.0.0.1")) {
                try {
                    directUrl.invoke(null, invalid);
                    throw new AssertionError("Probe must reject caller-controlled query, credentials, fragment or invalid endpoint");
                } catch (InvocationTargetException expected) {
                    check(expected.getCause() instanceof com.bkanent.common.rag.RagException rag
                            && rag.code() == com.bkanent.common.rag.RagErrorCode.INVALID_ARGUMENT,
                            "Invalid endpoint must fail closed without echoing it");
                }
            }
            Method timing = probe.getDeclaredMethod("inspectTiming", java.io.PrintStream.class,
                    long.class, int.class, Throwable.class);
            timing.setAccessible(true);
            var bytes = new ByteArrayOutputStream();
            var output = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
            RuntimeException waiting = new RuntimeException("UNTRUSTED_PROVIDER_BODY",
                    new java.util.concurrent.TimeoutException("UNTRUSTED_TIMEOUT_BODY"));
            timing.invoke(null, output, System.nanoTime() - 2_000_000, 30000, waiting);
            String summary = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            check(summary.matches("probe rpcInterface=RagSpaceAdminRpcService method=inspect protocol=dubbo "
                            + "timeoutConfiguredMillis=30000 elapsedMillis=[0-9]+ outcome=FAILED "
                            + "errorCode=DEADLINE_EXCEEDED remoteOutcome=UNCONFIRMED\\R"),
                    "Actual configured consumer wait and elapsed time must be safe numeric evidence, not remote completion");
            check(!summary.contains("UNTRUSTED"), "Provider exception text must never enter timing evidence");
            bytes.reset();
            timing.invoke(null, output, System.nanoTime(), 30000, null);
            check(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains(
                            "outcome=RETURNED errorCode=NONE remoteOutcome=RETURNED"),
                    "A returned inspection is distinct from timeout, not an implied ready or post-auth result");
        }
    }

    private static Path repository() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("a2a-sdk-transport-patch/pom.xml"))) return path;
        }
        throw new AssertionError("Run from the repository or common-a2a module");
    }
    private static Map<String, Element> dependencies(String pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(repository().resolve(pom).toFile());
        Element dependencies = (Element) document.getDocumentElement().getElementsByTagName("dependencies").item(0);
        Map<String, Element> declared = new LinkedHashMap<>();
        for (var node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element dependency) {
                declared.put(text(dependency, "groupId") + ":" + text(dependency, "artifactId"), dependency);
            }
        }
        return declared;
    }
    private static String text(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
