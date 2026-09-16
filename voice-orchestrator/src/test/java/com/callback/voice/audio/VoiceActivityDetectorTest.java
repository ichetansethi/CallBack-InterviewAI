package com.callback.voice.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceActivityDetectorTest {

    private final VoiceActivityDetector detector = new VoiceActivityDetector();

    @Test
    void silentChunkOfZerosIsDetectedAsSilent() {
        ByteBuffer chunk = pcm16(new short[320]); // 320 zero samples ~= 20ms at 16kHz

        assertThat(detector.isSilent(chunk)).isTrue();
    }

    @Test
    void loudChunkIsNotSilent() {
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (i % 2 == 0 ? 10_000 : -10_000);
        }

        assertThat(detector.isSilent(pcm16(samples))).isFalse();
    }

    @Test
    void emptyChunkIsTreatedAsSilent() {
        assertThat(detector.isSilent(ByteBuffer.allocate(0))).isTrue();
    }

    @Test
    void chunkJustBelowThresholdIsSilentAndJustAboveIsNot() {
        short[] quiet = new short[320];
        java.util.Arrays.fill(quiet, (short) 100); // RMS = 100, well under the 500 threshold
        assertThat(detector.isSilent(pcm16(quiet))).isTrue();

        short[] loud = new short[320];
        java.util.Arrays.fill(loud, (short) 1000); // RMS = 1000, well over the threshold
        assertThat(detector.isSilent(pcm16(loud))).isFalse();
    }

    private static ByteBuffer pcm16(short[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            buffer.putShort(s);
        }
        buffer.flip();
        return buffer;
    }

}
