package com.callback.voice.websocket;

import com.callback.security.jwt.JwtValidator;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.audio.UtteranceBuffer;
import com.callback.voice.audio.VoiceActivityDetector;
import com.callback.voice.client.QuestionServiceClient;
import com.callback.voice.client.QuestionSetNotFoundException;
import com.callback.voice.client.WhisperClient;
import com.callback.voice.session.InterviewSessionRepository;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.SessionHistoryPersister;
import com.callback.voice.session.TurnRecord;
import com.callback.voice.tts.TextToSpeechStreamer;
import com.callback.voice.turn.TurnDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class VoiceWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private final JwtValidator jwtValidator;
    private final InterviewSessionRepository sessionRepository;
    private final VoiceActivityDetector voiceActivityDetector;
    private final WhisperClient whisperClient;
    private final TurnDecisionService turnDecisionService;
    private final TextToSpeechStreamer textToSpeechStreamer;
    private final SessionHistoryPersister sessionHistoryPersister;
    private final QuestionServiceClient questionServiceClient;

    public VoiceWebSocketHandler(JwtValidator jwtValidator,
                                 InterviewSessionRepository sessionRepository,
                                 VoiceActivityDetector voiceActivityDetector,
                                 WhisperClient whisperClient,
                                 TurnDecisionService turnDecisionService,
                                 TextToSpeechStreamer textToSpeechStreamer,
                                 SessionHistoryPersister sessionHistoryPersister, QuestionServiceClient questionServiceClient) {
        this.jwtValidator = jwtValidator;
        this.sessionRepository = sessionRepository;
        this.voiceActivityDetector = voiceActivityDetector;
        this.whisperClient = whisperClient;
        this.turnDecisionService = turnDecisionService;
        this.textToSpeechStreamer = textToSpeechStreamer;
        this.sessionHistoryPersister = sessionHistoryPersister;
        this.questionServiceClient = questionServiceClient;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = extractQueryParam(session, "token");
        if (token == null || !jwtValidator.isValid(token)) {
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
        }

        String questionSetIdRaw = extractQueryParam(session, "questionSetId");
        if (questionSetIdRaw == null) {
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("questionSetId required"));
        }
        UUID questionSetId;
        try {
            questionSetId = UUID.fromString(questionSetIdRaw);
        } catch (IllegalArgumentException e) {
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("questionSetId must be a UUID"));
        }

        String ownerEmail = jwtValidator.extractEmail(token);
        // The resumable identity of an interview is (who + which question set), not a client-chosen
        // id: reconnecting with the same token and questionSetId always lands on the same Redis
        // session, so there's no separate sessionId for the client to track or pass back. Because
        // this key embeds ownerEmail, a different user can never collide with someone else's
        // session even if they guess a questionSetId — they'd just get their own (and
        // question-service's own ownership check below would reject them, since only the real
        // owner's token can ever successfully fetch that question set to populate a session).
        String sessionId = ownerEmail + "::" + questionSetId;
        String bearerToken = "Bearer " + token;

        return questionServiceClient.getQuestionSet(questionSetId, bearerToken)
                .flatMap(qs -> sessionRepository.loadOrCreate(sessionId, ownerEmail, qs.questions()))
                .flatMap(state -> runInterview(session, state))
                .onErrorResume(QuestionSetNotFoundException.class, e ->
                        session.close(CloseStatus.POLICY_VIOLATION.withReason("question set not found or not owned")))
                .onErrorResume(e -> {
                    log.error("Failed to initialize session for question set {}: {}", questionSetId, e.toString());
                    return session.close(CloseStatus.SERVER_ERROR);
                });
    }

    private Mono<Void> runInterview(WebSocketSession session, InterviewSessionState state) {
        AtomicReference<InterviewSessionState> stateRef = new AtomicReference<>(state);
        UtteranceBuffer utteranceBuffer = new UtteranceBuffer(voiceActivityDetector);

        return sendSessionAck(session, state.sessionId())
                .then(session.receive()
                        .concatMap(message -> handleIncomingAudioChunk(session, message, stateRef, utteranceBuffer))
                        .then())
                // Fires on normal completion, error, or cancellation alike — the one place that
                // reliably sees "this connection is over," regardless of why.
                .doFinally(signalType -> sessionHistoryPersister.onConnectionEnded(stateRef.get()));
    }

    private Mono<Void> sendSessionAck(WebSocketSession session, String sessionId) {
        return session.send(Mono.just(session.textMessage("{\"sessionId\":\"" + sessionId + "\"}")));
    }

    private String extractQueryParam(WebSocketSession session, String name) {
        return UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
                .build()
                .getQueryParams()
                .getFirst(name);
    }

    private Mono<Void> handleIncomingAudioChunk(WebSocketSession session, WebSocketMessage message,
                                                 AtomicReference<InterviewSessionState> stateRef,
                                                 UtteranceBuffer utteranceBuffer) {
        if (message.getType() != WebSocketMessage.Type.BINARY) {
            // Only binary frames carry audio; anything else (e.g. a future text control message) is ignored for now.
            return Mono.empty();
        }

        boolean endOfUtterance = utteranceBuffer.append(message.getPayload().asByteBuffer());
        if (!endOfUtterance) {
            return Mono.empty();
        }

        byte[] utteranceWav = utteranceBuffer.flushAsWav();
        return whisperClient.transcribe(utteranceWav)
                .flatMap(transcript -> processTurn(session, transcript, stateRef))
                // One failed utterance (Whisper/Groq/Piper down, a bad transcription) shouldn't
                // tear down the whole interview connection — log it, tell the candidate, and wait
                // for the next one. Turn state was never committed for this attempt (see
                // processTurn), so the candidate's next utterance is answering the same question.
                .onErrorResume(e -> {
                    log.error("Turn processing failed on session {}: {}", stateRef.get().sessionId(), e.toString());
                    return sendFallbackMessage(session)
                            .onErrorResume(sendFailure -> {
                                log.error("Failed to deliver fallback message on session {}: {}",
                                        stateRef.get().sessionId(), sendFailure.toString());
                                return Mono.empty();
                            });
                });
    }

    private Mono<Void> sendFallbackMessage(WebSocketSession session) {
        return session.send(Mono.just(session.textMessage(
                "{\"type\":\"error\",\"message\":\"Sorry, something went wrong — could you repeat that?\"}")));
    }

    private Mono<Void> processTurn(WebSocketSession session, String transcript, AtomicReference<InterviewSessionState> stateRef) {
        InterviewSessionState current = stateRef.get();

        // Commit only after the response has actually been synthesized and sent — not before
        // attempting delivery. That way a failure partway through (e.g. Piper is down) leaves the
        // session at its prior turn instead of silently advancing past a question the candidate
        // never heard. This doesn't guarantee the candidate actually heard it (no client-side ack
        // — that's real added complexity: ack timeouts, lost-ack vs. lost-message, retry policy —
        // deliberately not built here), only that the bytes were handed to the WebSocket
        // successfully.
        return turnDecisionService.decideNextTurn(transcript, current)
                .flatMap(decision -> textToSpeechStreamer.speak(session, decision.responseText())
                        .then(saveTurn(current, transcript, decision, stateRef))
                        .then(closeIfInterviewEnded(session, decision)))
                .then();
    }

    private Mono<InterviewSessionState> saveTurn(InterviewSessionState current, String transcript,
                                                  TurnDecision decision, AtomicReference<InterviewSessionState> stateRef) {
        InterviewSessionState updated = current.withTurn(new TurnRecord(transcript, decision.action(), decision.responseText()));
        return sessionRepository.save(updated).doOnNext(stateRef::set);
    }

    // The model closes out the interview itself via the "end" action (see TurnDecisionRecorder).
    // Closing here — after its closing remark has already been spoken and the turn saved — is what
    // makes the session reach a clean end state instead of the connection just sitting open
    // indefinitely once the interview is logically over.
    private Mono<Void> closeIfInterviewEnded(WebSocketSession session, TurnDecision decision) {
        if (!"end".equalsIgnoreCase(decision.action())) {
            return Mono.empty();
        }
        log.info("Interview ended by model decision on session {}; closing connection.",
                session.getHandshakeInfo().getUri());
        return session.close(CloseStatus.NORMAL);
    }

}
