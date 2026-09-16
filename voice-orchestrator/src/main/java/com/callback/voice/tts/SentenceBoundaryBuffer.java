package com.callback.voice.tts;

import reactor.core.publisher.Flux;

/**
 * Buffers a stream of text deltas (e.g. token-by-token LLM output) into complete sentences, so a
 * consumer can start synthesizing/speaking sentence 1 without waiting for the full response to
 * finish generating. Flushes any trailing partial text once the source completes, so a response
 * that ends mid-sentence (no terminal punctuation) isn't silently dropped.
 */
public final class SentenceBoundaryBuffer {

    private SentenceBoundaryBuffer() {
    }

    public static Flux<String> buffer(Flux<String> textDeltas) {
        StringBuilder pending = new StringBuilder();

        Flux<String> sentences = textDeltas.handle((delta, sink) -> {
            pending.append(delta);
            if (endsSentence(delta)) {
                sink.next(pending.toString().trim());
                pending.setLength(0);
            }
        });

        return sentences.concatWith(Flux.defer(() -> {
            String remainder = pending.toString().trim();
            return remainder.isEmpty() ? Flux.empty() : Flux.just(remainder);
        }));
    }

    private static boolean endsSentence(String delta) {
        String trimmed = delta.stripTrailing();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!");
    }

}
