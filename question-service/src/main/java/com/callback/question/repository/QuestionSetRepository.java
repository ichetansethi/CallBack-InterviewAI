package com.callback.question.repository;

import com.callback.question.model.QuestionSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionSetRepository extends JpaRepository<QuestionSet, UUID> {
    List<QuestionSet> findByOwnerEmail(String ownerEmail);
}
