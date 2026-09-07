package com.callback.jd.service;

import org.springframework.web.multipart.MultipartFile;

public interface TextExtractionService {
    /**
     * Best-effort text extraction based on the file's content type.
     * Returns null if the content type is unsupported or extraction fails —
     * extraction failures must never block an upload.
     */
    String extractText(MultipartFile file);
}
