package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.TurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the question-cursor fix against the real model (Groq openai/gpt-oss-120b — GROQ_API_KEY
 * must be exported): the bug was in how the model read the prompt, so unit tests of the cursor
 * arithmetic alone can't show it's fixed. Each question has one distinctive keyword so the
 * assertions don't depend on exact wording.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TurnDecisionServiceTest {

    private static final List<InterviewQuestionDto> QUESTIONS = List.of(
            new InterviewQuestionDto("technical", "How do you decide how many partitions a Kafka topic needs?", "JD: Kafka"),
            new InterviewQuestionDto("technical", "How would you tune a slow PostgreSQL query?", "JD: Postgres"),
            new InterviewQuestionDto("technical", "How do you version a public REST API?", "JD: REST"),
            new InterviewQuestionDto("behavioral", "Tell me about an on-call incident you handled.", "JD: on-call"),
            new InterviewQuestionDto("behavioral", "Describe a disagreement with a product manager.", "JD: product"),
            new InterviewQuestionDto("role-specific", "How would you design a new payments microservice?", "JD: design"),
            new InterviewQuestionDto("role-specific", "Which alerts would you set up for a Spring Boot service?", "JD: observability"));

    @Autowired
    private TurnDecisionService turnDecisionService;

    @Test
    void openingExchangeAsksQuestionOneAndLeavesTheCursorOnIt() {
        InterviewSessionState opening = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS);

        TurnDecision decision = decide("Hi, I'm ready to start.", opening);

        assertThat(decision.responseText()).containsIgnoringCase("partition").doesNotContainIgnoringCase("PostgreSQL");
        assertThat(opening.withTurn(record("Hi, I'm ready to start.", decision)).currentQuestionIndex()).isZero();
    }

    @Test
    void aCompleteAnswerAdvancesToTheNextQuestionInsteadOfReAskingTheCurrentOne() {
        InterviewSessionState afterOpening = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS)
                .withTurn(new TurnRecord("Hi, I'm ready.", "advance",
                        "Welcome! How do you decide how many partitions a Kafka topic needs?"));

        String answer = "I size partitions from target throughput divided by per-consumer throughput, add headroom "
                + "for growth since increasing partitions later breaks key ordering, and keep the count well under "
                + "broker limits. That's my complete answer on that one, happy to move on.";
        TurnDecision decision = decide(answer, afterOpening);

        assertThat(decision.action()).isEqualTo("advance");
        assertThat(decision.responseText()).containsIgnoringCase("PostgreSQL").doesNotContainIgnoringCase("how many partitions");
        assertThat(afterOpening.withTurn(record(answer, decision)).currentQuestionIndex()).isEqualTo(1);
    }

    @Test
    void answeringTheLastQuestionEndsTheInterview() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS);
        String[] asked = QUESTIONS.stream().map(InterviewQuestionDto::questionText).toArray(String[]::new);
        state = state.withTurn(new TurnRecord("Hi.", "advance", asked[0]));
        for (int i = 1; i < asked.length; i++) {
            state = state.withTurn(new TurnRecord("A thorough answer to question " + i + ".", "advance", asked[i]));
        }
        assertThat(state.currentQuestionIndex()).isEqualTo(6); // the alerts question has been asked

        TurnDecision decision = decide("I'd alert on p99 latency, error rate, consumer lag and connection pool "
                + "saturation, with runbooks linked from each alert. No more to add, thank you.", state);

        assertThat(decision.action()).isEqualTo("end");
    }

    @Test
    void atTheFollowUpCapAVagueAnswerMovesOnInsteadOfProbingAgain() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS)
                .withTurn(new TurnRecord("Hi.", "advance", QUESTIONS.get(0).questionText()))
                .withTurn(new TurnRecord("Solid answer on partitions.", "advance", QUESTIONS.get(1).questionText()))
                .withTurn(new TurnRecord("Hmm, I'd probably add an index?", "follow_up", "Which index, and how would you confirm it helped?"))
                .withTurn(new TurnRecord("Not sure, maybe look at the plan.", "follow_up", "What would you look for in the plan?"))
                .withTurn(new TurnRecord("I don't really remember.", "follow_up", "Have you used EXPLAIN ANALYZE before?"));
        assertThat(state.currentQuestionIndex()).isEqualTo(1);
        assertThat(state.followUpCountForCurrentQuestion()).isEqualTo(3);

        // Vague again — exactly the kind of answer that invites another probe.
        TurnDecision decision = decide("Maybe? I'm not sure, sorry.", state);

        assertThat(decision.action()).isEqualTo("advance");
        assertThat(decision.responseText()).containsIgnoringCase("version");
        InterviewSessionState after = state.withTurn(record("Maybe? I'm not sure, sorry.", decision));
        assertThat(after.currentQuestionIndex()).isEqualTo(2);
        assertThat(after.followUpCountForCurrentQuestion()).isZero();
    }

    @Test
    void theLastQuestionAtItsFollowUpCapEnds() {
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS);
        state = state.withTurn(new TurnRecord("Hi.", "advance", QUESTIONS.get(0).questionText()));
        for (int i = 1; i < QUESTIONS.size(); i++) {
            state = state.withTurn(new TurnRecord("A thorough answer.", "advance", QUESTIONS.get(i).questionText()));
        }
        for (int i = 0; i < 3; i++) {
            state = state.withTurn(new TurnRecord("Something vague.", "follow_up", "Can you be more specific about alerts?"));
        }

        TurnDecision decision = decide("Still not sure, sorry.", state);

        assertThat(decision.action()).isEqualTo("end");
    }

    private TurnDecision decide(String transcript, InterviewSessionState state) {
        return turnDecisionService.decideNextTurn(transcript, state).block(Duration.ofSeconds(90));
    }

    private static TurnRecord record(String transcript, TurnDecision decision) {
        return new TurnRecord(transcript, decision.action(), decision.responseText());
    }
}
