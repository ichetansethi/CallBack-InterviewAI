package com.callback.voice.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Prepends a standard 44-byte PCM WAV header to raw samples — no external audio library needed. */
public final class WavEncoder {

    private static final int HEADER_SIZE = 44;

    private WavEncoder() {
    }

    public static byte[] encode(byte[] pcmData, int sampleRateHz, int channels, int bitsPerSample) {
        int byteRate = sampleRateHz * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16); // PCM fmt chunk size
        header.putShort((short) 1); // audio format: 1 = PCM
        header.putShort((short) channels);
        header.putInt(sampleRateHz);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes());
        header.putInt(dataSize);

        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_SIZE + dataSize);
        out.writeBytes(header.array());
        out.writeBytes(pcmData);
        return out.toByteArray();
    }

}
