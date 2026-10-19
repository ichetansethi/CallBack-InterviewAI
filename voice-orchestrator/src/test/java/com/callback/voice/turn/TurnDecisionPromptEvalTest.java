package com.callback.voice.turn;

import com.callback.voice.DTO.InterviewQuestionDto;
import com.callback.voice.DTO.TurnDecision;
import com.callback.voice.session.InterviewSessionState;
import com.callback.voice.session.TurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt eval for the advance / follow_up / end rubric, against the real model (Groq; GROQ_API_KEY
 * must be exported). Opt-in only — run with {@code RUN_PROMPT_EVAL=true mvn test
 * -Dtest=TurnDecisionPromptEvalTest} — so it stays out of the default build: it makes
 * CASES × RUNS Groq calls and Groq's 8,000 TPM limit applies. Calls are spaced
 * {@code PROMPT_EVAL_SPACING_SECONDS} apart (default 20s), so a full run takes about nine minutes (27 calls).
 *
 * <p>Model output is not deterministic, so each case runs three times and passes when at least
 * two of the three agree with the expectation; the per-run actions are printed either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RUN_PROMPT_EVAL", matches = "true")
class TurnDecisionPromptEvalTest {

    private static final int RUNS = 3;
    private static final int MIN_PASSES = 2;
    private static final long SPACING_MS = Long.parseLong(
            System.getenv().getOrDefault("PROMPT_EVAL_SPACING_SECONDS", "20")) * 1000;

    private static final String QUESTION = "How would you tune a slow PostgreSQL query?";
    private static final List<InterviewQuestionDto> QUESTIONS = List.of(
            new InterviewQuestionDto("technical", "How do you decide how many partitions a Kafka topic needs?", "JD: Kafka"),
            new InterviewQuestionDto("technical", QUESTION, "JD: Postgres"),
            new InterviewQuestionDto("technical", "How do you version a public REST API?", "JD: REST"));

    private record Case(String name, String answer, Predicate<TurnDecision> expected, String expectation) {
    }

    private static final List<Case> CASES = List.of(
            new Case("strong-1",
                    "I start with EXPLAIN ANALYZE to see where the time goes. Last quarter a report query was doing a "
                            + "sequential scan over 40 million rows; adding a composite index on (account_id, created_at) "
                            + "cut it from 12 seconds to 80 milliseconds. The tradeoff was slower writes on that table.",
                    d -> d.action().equals("advance"), "advance"),
            new Case("strong-2",
                    "First I check the plan for bad row estimates. In one case stale statistics made the planner pick a "
                            + "nested loop on 2 million rows, so I ran ANALYZE and rewrote a correlated subquery as a join, "
                            + "which dropped latency by about 90 percent.",
                    d -> d.action().equals("advance"), "advance"),
            new Case("strong-3",
                    "I'd look at the plan first, then decide between an index and a rewrite. I chose a partial index on "
                            + "unpaid orders because only 3 percent of rows matched, which kept the index small, though it "
                            + "only helps queries with that exact filter.",
                    d -> d.action().equals("advance"), "advance"),
            new Case("strong-middle",
                    "I'd begin with pg_stat_statements to find the queries costing the most total time, then read the "
                            + "plan. On a checkout service I found an OR across two columns defeating the index; splitting it "
                            + "into a UNION ALL brought it from 900 milliseconds to 15. I'd confirm with a before-and-after "
                            + "benchmark on production-sized data.",
                    d -> d.action().equals("advance"), "advance, never end"),
            new Case("vague-1",
                    "I'd just try to make it faster, maybe add some indexes or something like that.",
                    d -> d.action().equals("follow_up") && namesMissingPiece(d), "follow_up naming the missing piece"),
            new Case("vague-2",
                    "Well, I would look at the query and see what's wrong with it. Databases can be slow for a lot of reasons.",
                    d -> d.action().equals("follow_up") && namesMissingPiece(d), "follow_up naming the missing piece"),
            new Case("vague-3",
                    "I usually optimize it by using best practices and making sure everything is efficient.",
                    d -> d.action().equals("follow_up") && namesMissingPiece(d), "follow_up naming the missing piece"),
            new Case("off-topic",
                    "Honestly I've been really into hiking lately, I did a ten mile trail last weekend and the weather was great.",
                    d -> !d.action().equals("end"), "follow_up or advance, not end"),
            new Case("stop-cue",
                    "Actually, I need to stop here, I have another meeting. Could we end the interview now? Thanks.",
                    d -> d.action().equals("end"), "end"));

    @Autowired
    private TurnDecisionService turnDecisionService;

    @Test
    void rubricProducesTheExpectedActions() throws InterruptedException {
        // Mid-interview, on question 2 with no follow-ups yet: follow_up, advance and end are all allowed.
        InterviewSessionState state = InterviewSessionState.newSession("s", "a@test.com", QUESTIONS)
                .withTurn(new TurnRecord("Hi.", "advance", QUESTIONS.get(0).questionText()))
                .withTurn(new TurnRecord("Partitions follow target throughput over per-consumer throughput, plus headroom.",
                        "advance", QUESTION));
        assertThat(TurnActionPolicy.allowedActions(state)).containsExactly("follow_up", "advance", "end");

        List<String> failures = new ArrayList<>();
        for (Case c : CASES) {
            int passes = 0;
            List<String> seen = new ArrayList<>();
            for (int run = 0; run < RUNS; run++) {
                TurnDecision decision = turnDecisionService.decideNextTurn(c.answer(), state).block(Duration.ofSeconds(120));
                seen.add(decision.action());
                if (c.expected().test(decision)) {
                    passes++;
                } else {
                    System.out.printf("  [%s run %d] unexpected: %s \"%s\"%n", c.name(), run + 1, decision.action(), decision.responseText());
                }
                Thread.sleep(SPACING_MS);
            }
            System.out.printf("EVAL %-10s expected %-34s got %s -> %d/%d%n", c.name(), c.expectation(), seen, passes, RUNS);
            if (passes < MIN_PASSES) {
                failures.add(c.name() + " (" + passes + "/" + RUNS + ", got " + seen + ")");
            }
        }
        assertThat(failures).as("cases below %d/%d", MIN_PASSES, RUNS).isEmpty();
    }

    /** A usable probe: asks something, doesn't just repeat the original question. */
    private static boolean namesMissingPiece(TurnDecision d) {
        String text = d.responseText().toLowerCase();
        return !text.contains(QUESTION.toLowerCase().replace("?", "")) && text.contains("?");
    }
}
