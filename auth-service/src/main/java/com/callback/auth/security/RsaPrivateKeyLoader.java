package com.callback.auth.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RS256 signing key. Never shared with common-security or any other module — this is
 * the one place the private key is parsed.
 *
 * <p>{@link #load()} reads the file named by the JWT_PRIVATE_KEY_PATH environment variable (so
 * deployed images can mount the key instead of packaging it), and falls back to
 * keys/private_key.pem on auth-service's own classpath for local runs.
 */
public final class RsaPrivateKeyLoader {

    static final String PATH_ENV_VAR = "JWT_PRIVATE_KEY_PATH";
    private static final String PRIVATE_KEY_RESOURCE = "/keys/private_key.pem";

    private RsaPrivateKeyLoader() {
    }

    public static PrivateKey load() {
        String path = System.getenv(PATH_ENV_VAR);
        return path == null || path.isBlank() ? loadFromClasspath() : loadFromFile(Path.of(path));
    }

    public static PrivateKey loadFromFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException(PATH_ENV_VAR + " points to a missing file: " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read RSA private key from " + path, e);
        }
    }

    public static PrivateKey loadFromClasspath() {
        try (InputStream in = RsaPrivateKeyLoader.class.getResourceAsStream(PRIVATE_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + PRIVATE_KEY_RESOURCE + " on the classpath");
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RSA private key", e);
        }
    }

    private static PrivateKey parse(InputStream in) throws IOException {
        try {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to parse RSA private key", e);
        }
    }
}
