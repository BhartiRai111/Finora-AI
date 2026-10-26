package com.finora.commons.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads P-256 EC keys from PEM files (PKCS#8 private, X.509 public). Fails fast with an actionable message. */
public final class KeyLoader {

    private KeyLoader() {
    }

    public static ECPublicKey loadPublicKey(Path path) {
        try {
            byte[] der = pemToDer(path);
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            requireP256(key.getParams().getOrder().bitLength(), path);
            return key;
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("Cannot load EC public key from " + path
                    + " (generate dev keys with tools/gen-dev-keys.sh)", e);
        }
    }

    public static ECPrivateKey loadPrivateKey(Path path) {
        try {
            byte[] der = pemToDer(path);
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            requireP256(key.getParams().getOrder().bitLength(), path);
            return key;
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("Cannot load EC private key from " + path
                    + " (generate dev keys with tools/gen-dev-keys.sh)", e);
        }
    }

    private static void requireP256(int orderBits, Path path) {
        if (orderBits != 256) {
            throw new IllegalStateException("Key at " + path + " is not a P-256 key");
        }
    }

    private static byte[] pemToDer(Path path) throws IOException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
