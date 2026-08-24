package com.callback.jd.repository;

import com.callback.jd.model.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    List<Resume> findByOwnerEmail(String ownerEmail);
}
