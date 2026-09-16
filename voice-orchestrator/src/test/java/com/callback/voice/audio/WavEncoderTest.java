package com.callback.voice.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class WavEncoderTest {

    @Test
    void headerFieldsMatchInputParameters() {
        byte[] pcm = new byte[]{1, 2, 3, 4, 5, 6};
        byte[] wav = WavEncoder.encode(pcm, 16_000, 1, 16);

        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(header.getInt(4)).isEqualTo(36 + pcm.length);
        assertThat(new String(wav, 8, 4)).isEqualTo("WAVE");
        assertThat(new String(wav, 12, 4)).isEqualTo("fmt ");
        assertThat(header.getShort(22)).isEqualTo((short) 1); // channels
        assertThat(header.getInt(24)).isEqualTo(16_000); // sample rate
        assertThat(header.getShort(34)).isEqualTo((short) 16); // bits per sample
        assertThat(new String(wav, 36, 4)).isEqualTo("data");
        assertThat(header.getInt(40)).isEqualTo(pcm.length);
        assertThat(wav.length).isEqualTo(44 + pcm.length);

        byte[] trailingData = new byte[pcm.length];
        System.arraycopy(wav, 44, trailingData, 0, pcm.length);
        assertThat(trailingData).isEqualTo(pcm);
    }

}
