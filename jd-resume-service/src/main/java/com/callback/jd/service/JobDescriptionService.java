package com.callback.jd.service;

import com.callback.jd.DTO.JobDescriptionRequest;
import com.callback.jd.DTO.JobDescriptionResponse;
import com.callback.jd.exception.JobDescriptionNotFoundException;
import com.callback.jd.model.JobDescription;
import com.callback.jd.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class JobDescriptionService {

    private final JobDescriptionRepository repository;

    public JobDescriptionService(JobDescriptionRepository repository) {
        this.repository = repository;
    }

    public JobDescriptionResponse create(JobDescriptionRequest request, String ownerEmail) {
        JobDescription jd = new JobDescription(ownerEmail, request.rawText(), request.role(), request.company());
        JobDescription saved = repository.save(jd);
        return toResponse(saved);
    }

    public JobDescriptionResponse getById(UUID id, String callerEmail) {
        return toResponse(findOwned(id, callerEmail));
    }

    public String getRawText(UUID id, String callerEmail) {
        return findOwned(id, callerEmail).getRawText();
    }

    public List<JobDescriptionResponse> listForUser(String ownerEmail) {
        return repository.findByOwnerEmail(ownerEmail).stream().map(this::toResponse).toList();
    }

    private JobDescription findOwned(UUID id, String callerEmail) {
        JobDescription jd = repository.findById(id)
                .orElseThrow(() -> new JobDescriptionNotFoundException(id));

        if (!jd.getOwnerEmail().equals(callerEmail)) {
            // deliberately the SAME exception as "doesn't exist" — see note below
            throw new JobDescriptionNotFoundException(id);
        }

        return jd;
    }

    private JobDescriptionResponse toResponse(JobDescription jd) {
        return new JobDescriptionResponse(jd.getId(), jd.getRole(), jd.getCompany(), jd.getCreatedAt());
    }
}
