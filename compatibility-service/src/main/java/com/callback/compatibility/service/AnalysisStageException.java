package com.callback.compatibility.service;

/**
 * A pipeline failure tagged with the stage it happened in, so the FAILED reason can name the
 * dependency that broke. The stage is what tells them apart: Ollama and Groq errors both surface
 * as the same Spring AI exception types, so the exception alone can't say which one failed.
 */
class AnalysisStageException extends RuntimeException {

    enum Stage {
        /** Indexing and searching the resume: Ollama embeddings and the pgvector database. */
        RESUME_SEARCH,
        /** Extracting requirements and scoring: the Groq chat model. */
        AI_MODEL
    }

    private final Stage stage;

    AnalysisStageException(Stage stage, RuntimeException cause) {
        super(stage + " failed: " + cause.getMessage(), cause);
        this.stage = stage;
    }

    Stage stage() {
        return stage;
    }
}
