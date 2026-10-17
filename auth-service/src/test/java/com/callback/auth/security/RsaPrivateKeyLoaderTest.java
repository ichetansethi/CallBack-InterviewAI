package com.callback.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaPrivateKeyLoaderTest {

    @Test
    void loadsKeyFromFile(@TempDir Path dir) throws Exception {
        Path copy = dir.resolve("key.pem");
        try (var in = RsaPrivateKeyLoader.class.getResourceAsStream("/keys/private_key.pem")) {
            Files.write(copy, in.readAllBytes());
        }
        assertThat(RsaPrivateKeyLoader.loadFromFile(copy).getEncoded())
                .isEqualTo(RsaPrivateKeyLoader.loadFromClasspath().getEncoded());
    }

    @Test
    void missingFileNamesTheEnvVar(@TempDir Path dir) {
        assertThatThrownBy(() -> RsaPrivateKeyLoader.loadFromFile(dir.resolve("nope.pem")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");
    }
}
