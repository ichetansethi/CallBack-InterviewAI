package com.callback.compatibility.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns free-text JD prose into a short list of discrete requirements, each of which becomes its
 * own retrieval query later — this is what lets evidence (and later, suggestions) be grounded per
 * requirement instead of against the JD as one undifferentiated blob.
 */
@Service
public class RequirementExtractor {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractor.class);
    private static final int MAX_ATTEMPTS = 4;

    private final ChatClient chatClient;
    private final RateLimitBackoff backoff;

    @Autowired
    public RequirementExtractor(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder.build(), new RateLimitBackoff());
    }

    RequirementExtractor(ChatClient chatClient, RateLimitBackoff backoff) {
        this.chatClient = chatClient;
        this.backoff = backoff;
    }

    /** Retries like CompatibilityScorer does: a 429 here is as likely as anywhere in the pipeline. */
    public List<String> extract(String jobDescriptionText) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return extractOnce(jobDescriptionText);
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Requirement extraction attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.toString());
                if (attempt < MAX_ATTEMPTS) {
                    backoff.waitBeforeRetry(e);
                }
            }
        }
        throw lastFailure;
    }

    private List<String> extractOnce(String jobDescriptionText) {
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
