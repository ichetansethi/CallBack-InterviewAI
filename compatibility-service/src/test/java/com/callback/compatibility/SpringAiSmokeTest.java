package com.callback.compatibility;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the Spring AI + Ollama wiring works at all, before any RAG or scoring logic is
 * built on top of it: a trivial hardcoded prompt through each model, hitting the real local
 * Ollama server (llama3.2:3b for chat, nomic-embed-text for embeddings) — no mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpringAiSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void chatModelRespondsToATrivialPrompt() {
        String response = chatModel.call("Reply with exactly the two words: hello world");

        assertThat(response).isNotBlank();
        System.out.println("ChatModel response: " + response);
    }

    @Test
    void embeddingModelEmbedsATrivialString() {
        float[] embedding = embeddingModel.embed("Senior backend engineer with Java experience");

        assertThat(embedding).isNotEmpty();
        assertThat(embeddingModel.dimensions()).isEqualTo(embedding.length);
        System.out.println("Embedding dimensions: " + embedding.length);
    }
}
