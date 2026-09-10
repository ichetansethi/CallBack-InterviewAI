package com.callback.question.service;

import com.callback.question.DTO.InterviewQuestionResponse;
import com.callback.question.DTO.QuestionBatch;
import com.callback.question.DTO.QuestionGenerateRequest;
import com.callback.question.DTO.QuestionSetResponse;
import com.callback.question.client.CompatibilityClient;
import com.callback.question.client.JdResumeClient;
import com.callback.question.exception.QuestionSetNotFoundException;
import com.callback.question.generation.QuestionGenerationService;
import com.callback.question.model.InterviewQuestion;
import com.callback.question.model.QuestionSet;
import com.callback.question.repository.InterviewQuestionRepository;
import com.callback.question.repository.QuestionSetRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full question-generation flow: fetch the JD text (forwarding the caller's
 * token), optionally fetch a compatibility analysis's uncovered requirements when one was
 * requested, generate a batch via function calling, then persist the set and its questions.
 *
 * <p>Built in two proven stages: plain JD-only generation first (see QuestionSetServiceTest),
 * then this gap-tailoring branch added on top — never skipping straight to the combined path
 * unverified.
 */
@Service
public class QuestionSetService {

    private final JdResumeClient jdResumeClient;
    private final CompatibilityClient compatibilityClient;
    private final QuestionGenerationService questionGenerationService;
    private final QuestionSetRepository questionSetRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;

    public QuestionSetService(JdResumeClient jdResumeClient,
                               CompatibilityClient compatibilityClient,
                               QuestionGenerationService questionGenerationService,
                               QuestionSetRepository questionSetRepository,
                               InterviewQuestionRepository interviewQuestionRepository) {
        this.jdResumeClient = jdResumeClient;
        this.compatibilityClient = compatibilityClient;
        this.questionGenerationService = questionGenerationService;
        this.questionSetRepository = questionSetRepository;
        this.interviewQuestionRepository = interviewQuestionRepository;
    }

    public QuestionSetResponse generate(QuestionGenerateRequest request, String ownerEmail, String bearerToken) {
        String jdText = jdResumeClient.getJdText(request.jdId(), bearerToken);
        List<String> uncoveredRequirements = request.compatibilityAnalysisId() != null
                ? compatibilityClient.getAnalysis(request.compatibilityAnalysisId(), bearerToken).uncoveredRequirements()
                : List.of();

        QuestionBatch generated = questionGenerationService.generate(jdText, uncoveredRequirements);

        QuestionSet qs = new QuestionSet(ownerEmail, request.jdId(), request.compatibilityAnalysisId());
        QuestionSet saved = questionSetRepository.save(qs);

        List<InterviewQuestion> questions = generated.questions().stream()
                .map(gq -> new InterviewQuestion(saved, gq.category(), gq.questionText(), gq.rationale()))
                .toList();
        interviewQuestionRepository.saveAll(questions);

        return toResponse(saved, questions);
    }

    public QuestionSetResponse getById(UUID id, String callerEmail) {
        QuestionSet qs = findOwned(id, callerEmail);
        return toResponse(qs, questionsFor(qs));
    }

    public List<QuestionSetResponse> listForUser(String callerEmail) {
        return questionSetRepository.findByOwnerEmail(callerEmail).stream()
                .map(qs -> toResponse(qs, questionsFor(qs)))
                .toList();
    }

    private List<InterviewQuestion> questionsFor(QuestionSet qs) {
        return interviewQuestionRepository.findByQuestionSetIdOrderByOrderIndexAsc(qs.getId());
    }

    private QuestionSet findOwned(UUID id, String callerEmail) {
        QuestionSet qs = questionSetRepository.findById(id)
                .orElseThrow(() -> new QuestionSetNotFoundException(id));

        if (!qs.getOwnerEmail().equals(callerEmail)) {
            // deliberately the SAME exception as "doesn't exist" — mirrors
            // CompatibilityAnalysisService.findOwned, to avoid leaking existence of another
            // user's question set.
            throw new QuestionSetNotFoundException(id);
        }

        return qs;
    }

    private QuestionSetResponse toResponse(QuestionSet saved, List<InterviewQuestion> questions) {
        List<InterviewQuestionResponse> questionResponses = questions.stream()
                .map(q -> new InterviewQuestionResponse(q.getCategory(), q.getQuestionText(), q.getRationale()))
                .toList();
        return new QuestionSetResponse(saved.getId(), saved.getJdId(), saved.getCreatedAt(), questionResponses);
    }
}
