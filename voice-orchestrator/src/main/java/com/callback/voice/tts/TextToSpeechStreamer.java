package com.callback.voice.tts;

import com.callback.voice.client.PiperClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Synthesizes text sentence-by-sentence and streams each sentence's audio to the client as soon
 * as it's ready — the pipelining mitigation: playback of sentence 1 can start while sentence 2 is
 * still being synthesized, rather than waiting for the whole response.
 *
 * <p>Deliberately concatMap, not flatMap, for the synthesize+send step: sentences must reach the
 * client in order (flatMap gives no ordering guarantee across concurrent synthesize calls of
 * different latency), and WebSocketSession.send() must not be invoked concurrently with itself on
 * the same session.
 */
@Component
public class TextToSpeechStreamer {

    private final PiperClient piperClient;

    public TextToSpeechStreamer(PiperClient piperClient) {
        this.piperClient = piperClient;
    }

    /** Speaks an already-complete piece of text (e.g. a non-streamed model response). */
    public Mono<Void> speak(WebSocketSession session, String text) {
        return speakSentences(session, Flux.fromIterable(SentenceSplitter.split(text)));
    }

    /** Speaks a genuinely streaming source of text deltas, buffered into sentences as they complete. */
    public Mono<Void> speakStream(WebSocketSession session, Flux<String> textDeltas) {
        return speakSentences(session, SentenceBoundaryBuffer.buffer(textDeltas));
    }

    private Mono<Void> speakSentences(WebSocketSession session, Flux<String> sentences) {
        return sentences
                .concatMap(sentence -> piperClient.synthesize(sentence)
                        .flatMap(audioBytes -> session.send(Mono.just(session.binaryMessage(factory -> factory.wrap(audioBytes))))))
                .then();
    }

}
