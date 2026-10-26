package com.finora.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Throw-away P-256 key pairs generated fresh for each test JVM and written to a temporary directory. They exist only
 * to exercise token verification; no key is stored in the repository.
 */
public final class TestKeys {

    private static final Map<String, KeyPair> PAIRS = new HashMap<>();
    private static Path dir;

    private TestKeys() {
    }

    public static synchronized KeyPair pair(String name) {
        return PAIRS.computeIfAbsent(name, n -> {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                return generator.generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public static ECPrivateKey privateKey(String name) {
        return (ECPrivateKey) pair(name).getPrivate();
    }

    public static ECPublicKey publicKey(String name) {
        return (ECPublicKey) pair(name).getPublic();
    }

    public static synchronized Path publicPem(String name) {
        return write(name + ".pub.pem", "PUBLIC KEY", pair(name).getPublic().getEncoded());
    }

    public static synchronized Path privatePem(String name) {
        return write(name + ".key.pem", "PRIVATE KEY", pair(name).getPrivate().getEncoded());
    }

    private static Path write(String file, String label, byte[] der) {
        try {
            if (dir == null) {
                dir = Files.createTempDirectory("finora-test-keys");
                dir.toFile().deleteOnExit();
            }
            Path path = dir.resolve(file);
            String pem = "-----BEGIN " + label + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                    + "\n-----END " + label + "-----\n";
            Files.writeString(path, pem, StandardCharsets.US_ASCII);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
