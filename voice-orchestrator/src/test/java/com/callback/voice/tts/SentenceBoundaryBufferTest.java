package com.callback.voice.tts;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SentenceBoundaryBufferTest {

    @Test
    void emitsACompleteSentenceAssoonAsATerminalDeltaArrives() {
        Flux<String> deltas = Flux.just("Tell ", "me ", "about ", "yourself.", " What ", "brings ", "you ", "here?");

        StepVerifier.create(SentenceBoundaryBuffer.buffer(deltas))
                .expectNext("Tell me about yourself.")
                .expectNext("What brings you here?")
                .verifyComplete();
    }

    @Test
    void flushesATrailingPartialSentenceOnCompletion() {
        Flux<String> deltas = Flux.just("This never ", "ends with punctuation");

        StepVerifier.create(SentenceBoundaryBuffer.buffer(deltas))
                .expectNext("This never ends with punctuation")
                .verifyComplete();
    }

    @Test
    void emptySourceProducesNoSentences() {
        StepVerifier.create(SentenceBoundaryBuffer.buffer(Flux.empty()))
                .verifyComplete();
    }

}
