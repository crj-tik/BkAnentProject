import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local development identity bootstrap. Private keys never go into generated env files. */
public final class ServiceIdentityKeygen {
    private static final List<String> SERVICES = List.of(
            "auth-service", "gateway", "agent-service", "listing-master-service", "customer-service",
            "marketing-content-service", "business-service", "business-review-service",
            "notification-service", "media-worker-service", "interview-service",
            "knowledge-service", "platform-operations-service");
    private static final Path ROOT = Path.of(".secrets", "service-identities");

    private ServiceIdentityKeygen() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            bootstrap();
            return;
        }
        if (args.length == 3 && "--rotate".equals(args[0])) {
            rotate(args[1], args[2]);
            return;
        }
        throw new IllegalArgumentException("Usage: java scripts/security/ServiceIdentityKeygen.java [--rotate <service-id> <new-key-id>]");
    }

    private static void bootstrap() throws Exception {
        Files.createDirectories(ROOT);
        StringBuilder publicEnv = new StringBuilder("# Generated public Ed25519 keys; safe to mount as configuration.\n");
        for (String service : SERVICES) {
            Path directory = ROOT.resolve(service);
            KeyPair pair = ensurePair(directory);
            publicEnv.append(envPrefix(service)).append("_IDENTITY_PUBLIC_KEY=")
                    .append(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())).append('\n');
        }
        Path envFile = ROOT.resolve("public-keys.env");
        writeAtomically(envFile, publicEnv.toString());
        restrictFile(envFile, false);
        System.out.println("Created or verified " + SERVICES.size() + " local service identity pairs under .secrets/service-identities; private key material was not printed.");
    }

    private static void rotate(String service, String keyId) throws Exception {
        if (!SERVICES.contains(service) || keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException("Rotation requires a registered service id and a simple new key id");
        }
        Path directory = ROOT.resolve(service).resolve(keyId);
        if (Files.exists(directory.resolve("private-key.pem")) || Files.exists(directory.resolve("public-key.pem"))) {
            throw new IllegalStateException("The target rotation key already exists; refusing to overwrite it");
        }
        KeyPair pair = generatePair();
        writePair(directory, pair);
        System.out.println("Created a rotation pair at .secrets/service-identities/" + service + "/" + keyId
                + ". Enroll public-key.pem with each verifier before switching the caller; private material was not printed.");
    }

    private static KeyPair ensurePair(Path directory) throws Exception {
        Path privateFile = directory.resolve("private-key.pem");
        Path publicFile = directory.resolve("public-key.pem");
        boolean privateExists = Files.exists(privateFile);
        boolean publicExists = Files.exists(publicFile);
        if (privateExists != publicExists) {
            throw new IllegalStateException("Incomplete identity pair at " + directory + "; refusing to replace either key");
        }
        if (!privateExists) {
            KeyPair generated = generatePair();
            writePair(directory, generated);
            return generated;
        }
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(readPem(privateFile, "PRIVATE KEY")));
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(readPem(publicFile, "PUBLIC KEY")));
        if (!matches(privateKey, publicKey)) throw new IllegalStateException("Identity pair does not match at " + directory);
        return new KeyPair(publicKey, privateKey);
    }

    private static KeyPair generatePair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static void writePair(Path directory, KeyPair pair) throws IOException {
        Files.createDirectories(directory);
        Path privateFile = directory.resolve("private-key.pem");
        Path publicFile = directory.resolve("public-key.pem");
        writeAtomically(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        restrictFile(privateFile, true);
        writeAtomically(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        restrictFile(publicFile, false);
    }

    private static boolean matches(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        byte[] challenge = "service-identity-pair-check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        return verifier.verify(signature);
    }

    private static byte[] readPem(Path file, String label) throws IOException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII).trim();
        String start = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.startsWith(start) || !pem.endsWith(end)) throw new IOException("Invalid PEM file: " + file.getFileName());
        String body = pem.substring(start.length(), pem.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String pem(String label, byte[] value) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(value)
                + "\n-----END " + label + "-----\n";
    }

    private static String envPrefix(String service) {
        return service.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictFile(Path file, boolean privateMaterial) throws IOException {
        try {
            Set<PosixFilePermission> permissions = privateMaterial
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // The Windows wrapper applies an explicit ACL after generation.
        }
    }
}
