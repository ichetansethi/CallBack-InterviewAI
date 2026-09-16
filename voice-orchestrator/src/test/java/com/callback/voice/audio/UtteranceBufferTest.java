package com.callback.voice.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class UtteranceBufferTest {

    private final UtteranceBuffer buffer = new UtteranceBuffer(new VoiceActivityDetector());

    @Test
    void doesNotFlagEndOfUtteranceWhileOnlySilenceHasArrived() {
        for (int i = 0; i < 50; i++) {
            assertThat(buffer.append(silentChunk(20))).isFalse();
        }
    }

    @Test
    void flagsEndOfUtteranceAfterSpeechFollowedByEnoughTrailingSilence() {
        // 400ms of speech (>= MIN_SPEECH_MS_BEFORE_FLUSH), then silence until it crosses SILENCE_HOLD_MS.
        boolean endOfUtterance = false;
        for (int i = 0; i < 20; i++) { // 20 * 20ms = 400ms speech
            endOfUtterance = buffer.append(loudChunk(20));
        }
        assertThat(endOfUtterance).isFalse();

        for (int i = 0; i < 40 && !endOfUtterance; i++) { // up to 800ms silence
            endOfUtterance = buffer.append(silentChunk(20));
        }

        assertThat(endOfUtterance).isTrue();
    }

    @Test
    void flushAsWavProducesAValidHeaderAndResetsState() {
        buffer.append(loudChunk(20));
        byte[] wav = buffer.flushAsWav();

        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4)).isEqualTo("WAVE");
        assertThat(wav.length).isGreaterThan(44);

        // A fresh append right after flush must not immediately re-trigger end-of-utterance.
        assertThat(buffer.append(silentChunk(20))).isFalse();
    }

    private static ByteBuffer silentChunk(int durationMs) {
        return pcm16(new short[samplesFor(durationMs)]);
    }

    private static ByteBuffer loudChunk(int durationMs) {
        short[] samples = new short[samplesFor(durationMs)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (i % 2 == 0 ? 10_000 : -10_000);
        }
        return pcm16(samples);
    }

    private static int samplesFor(int durationMs) {
        return 16_000 * durationMs / 1000;
    }

    private static ByteBuffer pcm16(short[] samples) {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            buf.putShort(s);
        }
        buf.flip();
        return buf;
    }

}
