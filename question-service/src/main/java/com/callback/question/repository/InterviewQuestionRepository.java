package com.callback.question.repository;

import com.callback.question.model.InterviewQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {
    List<InterviewQuestion> findByQuestionSetIdOrderByOrderIndexAsc(UUID questionSetId);
}
