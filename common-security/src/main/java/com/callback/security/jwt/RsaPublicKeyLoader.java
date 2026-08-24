package com.callback.security.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Reads keys/public_key.pem from this jar's own classpath and builds an RSA PublicKey from it. */
public final class RsaPublicKeyLoader {

    private static final String PUBLIC_KEY_RESOURCE = "/keys/public_key.pem";

    private RsaPublicKeyLoader() {
    }

    public static PublicKey loadFromClasspath() {
        try (InputStream in = RsaPublicKeyLoader.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + PUBLIC_KEY_RESOURCE + " on the classpath");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to load RSA public key", e);
        }
    }
}
