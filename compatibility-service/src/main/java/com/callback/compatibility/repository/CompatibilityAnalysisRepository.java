package com.callback.compatibility.repository;

import com.callback.compatibility.model.CompatibilityAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompatibilityAnalysisRepository extends JpaRepository<CompatibilityAnalysis, UUID> {
    List<CompatibilityAnalysis> findByOwnerEmail(String ownerEmail);
}
