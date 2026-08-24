package com.callback.jd.repository;

import com.callback.jd.model.JobDescription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, UUID> {
    List<JobDescription> findByOwnerEmail(String ownerEmail);
}
