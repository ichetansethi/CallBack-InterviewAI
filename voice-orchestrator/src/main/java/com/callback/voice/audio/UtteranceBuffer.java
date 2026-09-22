package com.callback.voice.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Accumulates one utterance's raw PCM audio for a single WebSocket connection. Not a Spring bean:
 * a fresh instance is created per connection in VoiceWebSocketHandler, since utterance state
 * (buffered bytes, running silence/speech duration) is inherently per-connection, not shared.
 */
public class UtteranceBuffer {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;

    // How long trailing silence must hold before the utterance counts as finished. Confirmed via
    // live testing that 700ms was too short: a candidate pausing mid-thought to think for over
    // 700ms (a normal, natural pause) got flushed as if they were done talking, producing two
    // fragmentary utterances and an interviewer response to a half-formed sentence. Raised to
    // 1500ms — comfortably past a natural thinking pause without being long enough to feel like a
    // dead connection. Trade-off: this adds up to ~800ms more latency to every genuine end-of-turn
    // detection, on top of the pipeline's own STT/decision/TTS latency (see ARCHITECTURE.md,
    // "Audio processing details" and "Testing" sections). A fixed energy-based VAD threshold can't
    // fully solve this — telling "still thinking" from "done talking" from silence duration alone
    // is inherently a guess; a semantic/ML VAD is the real fix, not attempted here.
    private static final long SILENCE_HOLD_MS = 1500;

    // Guards against flushing on leading silence before the candidate has said anything.
    private static final long MIN_SPEECH_MS_BEFORE_FLUSH = 300;

    private final VoiceActivityDetector voiceActivityDetector;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private long silenceMs = 0;
    private long speechMs = 0;

    public UtteranceBuffer(VoiceActivityDetector voiceActivityDetector) {
        this.voiceActivityDetector = voiceActivityDetector;
    }

    /** Appends a chunk and returns true once trailing silence marks the utterance as complete. */
    public boolean append(ByteBuffer chunk) {
        byte[] bytes = new byte[chunk.remaining()];
        chunk.duplicate().get(bytes);
        pcm.write(bytes, 0, bytes.length);

        long chunkMs = (bytes.length / BYTES_PER_SAMPLE) * 1000L / SAMPLE_RATE_HZ;
        if (voiceActivityDetector.isSilent(chunk)) {
            silenceMs += chunkMs;
        } else {
            silenceMs = 0;
            speechMs += chunkMs;
        }
        return speechMs >= MIN_SPEECH_MS_BEFORE_FLUSH && silenceMs >= SILENCE_HOLD_MS;
    }

    public byte[] flushAsWav() {
        byte[] pcmBytes = pcm.toByteArray();
        pcm.reset();
        speechMs = 0;
        silenceMs = 0;
        return WavEncoder.encode(pcmBytes, SAMPLE_RATE_HZ, 1, 16);
    }

}
