package com.callback.compatibility.scoring;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns free-text JD prose into a short list of discrete requirements, each of which becomes its
 * own retrieval query later — this is what lets evidence (and later, suggestions) be grounded per
 * requirement instead of against the JD as one undifferentiated blob.
 */
@Service
public class RequirementExtractor {

    private final ChatClient chatClient;

    public RequirementExtractor(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<String> extract(String jobDescriptionText) {
        RequirementList result = chatClient.prompt()
                .system("""
                        You extract discrete requirements from a job description. Each requirement should
                        be a short, self-contained phrase describing one specific skill, technology, years
                        of experience, or qualification — not a summary of the whole JD. Split compound
                        requirements into separate entries.""")
                .user("JOB DESCRIPTION:\n" + jobDescriptionText)
                .call()
                .entity(RequirementList.class);

        return result == null ? List.of() : result.requirements();
    }
}
