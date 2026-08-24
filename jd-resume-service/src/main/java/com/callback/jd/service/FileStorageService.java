package com.callback.jd.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String store(MultipartFile file, String ownerEmail); // returns storage key
    Resource load(String storageKey);
    void delete(String storageKey);
}
