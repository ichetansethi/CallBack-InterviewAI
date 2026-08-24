package com.callback.jd.service;

import com.callback.jd.DTO.ResumeResponse;
import com.callback.jd.exception.InvalidFileException;
import com.callback.jd.exception.ResumeNotFoundException;
import com.callback.jd.model.Resume;
import com.callback.jd.repository.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ResumeService {

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final ResumeRepository repository;
    private final FileStorageService fileStorageService;

    public ResumeService(ResumeRepository repository, FileStorageService fileStorageService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    public ResumeResponse upload(MultipartFile file, String ownerEmail) {
        validate(file);
        String storageKey = fileStorageService.store(file, ownerEmail);
        Resume resume = new Resume(ownerEmail, file.getOriginalFilename(), storageKey, file.getContentType(), file.getSize());
        return toResponse(repository.save(resume));
    }

    public ResumeResponse getMetadata(UUID id, String callerEmail) {
        Resume resume = repository.findById(id)
                .orElseThrow(() -> new ResumeNotFoundException(id));
        if (!resume.getOwnerEmail().equals(callerEmail)) {
            throw new ResumeNotFoundException(id); // same 404-for-both pattern as JD
        }
        return toResponse(resume);
    }

    public List<ResumeResponse> listForUser(String ownerEmail) {
        return repository.findByOwnerEmail(ownerEmail).stream().map(this::toResponse).toList();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidFileException("File exceeds maximum size of 5MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Only PDF and DOCX files are allowed");
        }
    }

    private ResumeResponse toResponse(Resume r) {
        return new ResumeResponse(r.getId(), r.getOriginalFilename(), r.getContentType(), r.getFileSizeBytes(), r.getUploadedAt());
        // note: storageKey deliberately excluded — internal detail, not client-facing
    }
}
