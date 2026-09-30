package com.callback.question.service;

import com.callback.question.DTO.GeneratedQuestion;
import com.callback.question.DTO.InterviewQuestionResponse;
import com.callback.question.DTO.QuestionBatch;
import com.callback.question.DTO.QuestionGenerateRequest;
import com.callback.question.DTO.QuestionSetResponse;
import com.callback.question.client.CompatibilityClient;
import com.callback.question.client.JdResumeClient;
import com.callback.question.generation.QuestionGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Proves a question set's order survives persistence (real Postgres, callback_question DB). Only
 * the model call and the upstream clients are faked — ordering is a persistence concern, and the
 * generation stage is proven on its own in QuestionGenerationServiceTest.
 *
 * <p>Insertion order alone can't show this: Postgres usually hands rows back in the order they were
 * written, which is exactly how every question being saved with order_index 0 went unnoticed. So
 * the first question is rewritten in place first (a no-op UPDATE writes a new row version at the
 * end of the table), and the read must still return it first — only a real orderIndex can do that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuestionSetOrderingTest {

    @Autowired
    private QuestionSetService questionSetService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private QuestionGenerationService questionGenerationService;

    @MockitoBean
    private JdResumeClient jdResumeClient;

    @MockitoBean
    private CompatibilityClient compatibilityClient;

    private final String owner = "ordering-" + UUID.randomUUID() + "@example.com";

    private static final List<GeneratedQuestion> BATCH = IntStream.range(0, 5)
            .mapToObj(i -> new GeneratedQuestion("technical", "Question " + (i + 1), "rationale " + (i + 1)))
            .toList();

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from interview_question where question_set_id in (select id from question_set where owner_email = ?)", owner);
        jdbc.update("delete from question_set where owner_email = ?", owner);
    }

    @Test
    void persistsEachQuestionsPositionAndReadsBackInThatOrderRegardlessOfPhysicalRowOrder() {
        when(jdResumeClient.getJdText(any(), any())).thenReturn("JD text");
        when(questionGenerationService.generate(any(), anyList())).thenReturn(new QuestionBatch(BATCH));

        QuestionSetResponse created = questionSetService.generate(
                new QuestionGenerateRequest(UUID.randomUUID(), null), owner, "Bearer t");

        List<Integer> stored = jdbc.queryForList(
                "select order_index from interview_question where question_set_id = ? order by question_text",
                Integer.class, created.id());
        assertThat(stored).containsExactly(0, 1, 2, 3, 4);

        jdbc.update("update interview_question set rationale = rationale where question_set_id = ? and order_index = 0",
                created.id());
        List<String> physicalOrder = jdbc.queryForList(
                "select question_text from interview_question where question_set_id = ?", String.class, created.id());
        assertThat(physicalOrder.get(physicalOrder.size() - 1))
                .as("the no-op UPDATE must move Question 1 to the end of the heap, or this test proves nothing")
                .isEqualTo("Question 1");

        List<String> expected = BATCH.stream().map(GeneratedQuestion::questionText).toList();
        assertThat(created.questions()).extracting(InterviewQuestionResponse::questionText).containsExactlyElementsOf(expected);
        assertThat(questionSetService.getById(created.id(), owner).questions())
                .extracting(InterviewQuestionResponse::questionText).containsExactlyElementsOf(expected);
        assertThat(questionSetService.listForUser(owner)).singleElement()
                .satisfies(qs -> assertThat(qs.questions()).extracting(InterviewQuestionResponse::questionText)
                        .containsExactlyElementsOf(expected));
    }
}
