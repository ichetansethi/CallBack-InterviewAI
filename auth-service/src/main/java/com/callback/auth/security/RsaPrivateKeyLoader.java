package com.callback.auth.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Reads keys/private_key.pem from auth-service's own classpath. Never shared with
 * common-security or any other module — this is the one place the private key is parsed.
 */
public final class RsaPrivateKeyLoader {

    private static final String PRIVATE_KEY_RESOURCE = "/keys/private_key.pem";

    private RsaPrivateKeyLoader() {
    }

    public static PrivateKey loadFromClasspath() {
        try (InputStream in = RsaPrivateKeyLoader.class.getResourceAsStream(PRIVATE_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + PRIVATE_KEY_RESOURCE + " on the classpath");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to load RSA private key", e);
        }
    }
}
