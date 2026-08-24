package com.callback.jd.service;

import com.callback.jd.exception.FileStorageException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path rootDir = Paths.get("./data/resumes");

    public LocalFileStorageService() {
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not initialize storage directory", e);
        }
    }

    @Override
    public String store(MultipartFile file, String ownerEmail) {
        String storageKey = UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
        try {
            Files.copy(file.getInputStream(), rootDir.resolve(storageKey));
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file", e);
        }
        return storageKey;
    }

    @Override
    public Resource load(String storageKey) {
        try {
            return new UrlResource(rootDir.resolve(storageKey).toUri());
        } catch (MalformedURLException e) {
            throw new FileStorageException("Failed to load file", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(rootDir.resolve(storageKey));
        } catch (IOException e) {
            throw new FileStorageException("Failed to delete file", e);
        }
    }

    private String sanitize(String filename) {
        return filename == null ? "unnamed" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
