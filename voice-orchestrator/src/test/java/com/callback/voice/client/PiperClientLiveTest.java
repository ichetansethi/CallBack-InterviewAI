package com.callback.voice.client;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract test against the official Piper server (piper-tts's {@code python -m piper.http_server},
 * on piper.base-url), through the real PiperClient bean. Skipped when nothing is listening there.
 *
 * <p>Guards against having only ever tested against a hand-written stand-in: the official server
 * answers /synthesize with WAV bytes labelled {@code text/html}, and identifies itself via
 * {@code GET /info}, which a stand-in wouldn't have.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PiperClientLiveTest {

    private static final String BASE_URL = "http://localhost:8091"; // matches application.yml piper.base-url

    @Autowired
    private PiperClient piperClient;

    @BeforeAll
    static void requireOfficialPiperServer() {
        String info;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/info")).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            info = response.statusCode() == 200 ? response.body() : "";
        } catch (Exception e) {
            info = "";
        }
        assumeTrue(info.contains("\"voice\""), "official piper.http_server not running on " + BASE_URL);
    }

    @Test
    void synthesizeReturnsAPlayableWavFromTheOfficialServer() {
        byte[] audio = piperClient.synthesize("Thanks, that is all for today.").block(Duration.ofSeconds(20));

        assertThat(audio).isNotNull().hasSizeGreaterThan(44);
        assertThat(new String(audio, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(audio, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        ByteBuffer header = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(header.getShort(20)).isEqualTo((short) 1);   // PCM
        assertThat(header.getShort(22)).isEqualTo((short) 1);   // mono
        assertThat(header.getShort(34)).isEqualTo((short) 16);  // 16-bit
    }
}
