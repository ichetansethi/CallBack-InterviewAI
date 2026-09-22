package com.callback.voice.client;

import java.util.UUID;

/**
 * The question set doesn't exist, or exists but isn't owned by the caller — question-service
 * deliberately returns the same 404 for both cases (see QuestionSetService.findOwned there) so a
 * candidate can't tell the difference between "no such question set" and "that's someone else's."
 * Only ever raised for a genuine 404 — see QuestionServiceClient for why other 4xx statuses (e.g.
 * a 401 from a bad/expired forwarded token) are deliberately NOT folded into this exception.
 */
public class QuestionSetNotFoundException extends RuntimeException {

    public QuestionSetNotFoundException(UUID questionSetId) {
        super("Question set not found or not owned by caller: " + questionSetId);
    }

}
