package com.bkanent.common.a2a;

import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.support.PreferSerializationProviderImpl;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
