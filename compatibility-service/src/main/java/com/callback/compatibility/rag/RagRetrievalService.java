package com.callback.compatibility.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chunks a resume's text and indexes it into the shared, persistent pgvector store, keyed by
 * resumeId metadata — each resume is indexed once and reused across every future analysis
 * against it, instead of being re-chunked/re-embedded per request. Every read is scoped to a
 * single resumeId's chunks: the store holds every resume ever analyzed, so an unscoped search
 * would leak evidence across candidates.
 */
@Service
public class RagRetrievalService {

    private static final String RESUME_ID_KEY = "resumeId";

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public RagRetrievalService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(40)
                .withMinChunkSizeChars(20)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(500)
                .withKeepSeparator(true)
                .build();
    }

    public void indexResumeIfAbsent(UUID resumeId, String resumeText) {
        if (isIndexed(resumeId)) {
            return;
        }
        Document source = new Document(resumeText, Map.of(RESUME_ID_KEY, resumeId.toString()));
        List<Document> chunks = splitter.split(source);
        vectorStore.add(chunks);
    }

    public boolean isIndexed(UUID resumeId) {
        List<Document> existing = vectorStore.similaritySearch(SearchRequest.builder()
                .query("resume content")
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(RESUME_ID_KEY + " == '" + resumeId + "'")
                .build());
        return !existing.isEmpty();
    }

    public List<Document> retrieveEvidence(UUID resumeId, String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(RESUME_ID_KEY + " == '" + resumeId + "'")
                .build());
    }
}
