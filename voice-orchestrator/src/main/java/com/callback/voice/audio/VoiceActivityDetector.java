package com.callback.voice.audio;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Energy-based VAD: RMS amplitude of a chunk below a fixed threshold counts as silence. Assumes
 * 16-bit signed little-endian PCM samples (the format whisper.cpp expects on the other end).
 * Deliberately the simplest viable version — a proper ML VAD model is a later upgrade, not a
 * blocker for a working pipeline.
 */
@Component
public class VoiceActivityDetector {

    private static final int SILENCE_RMS_THRESHOLD = 500;

    public boolean isSilent(ByteBuffer pcm16Chunk) {
        ByteBuffer buffer = pcm16Chunk.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int sampleCount = buffer.remaining() / 2;
        if (sampleCount == 0) {
            return true;
        }

        long sumOfSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            short sample = buffer.getShort();
            sumOfSquares += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumOfSquares / sampleCount);
        return rms < SILENCE_RMS_THRESHOLD;
    }

}
