package com.callback.voice.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * KNOWN GAP: session-history-service is next in the build order and doesn't exist yet, so there's
 * nowhere to hand off a finished session's transcript/feedback. Until it does, the full transcript
 * already lives in Redis for the session's TTL (see InterviewSessionRepository) — this class only
 * makes that gap visible instead of leaving it silent. Replace this log line with a real call to
 * session-history-service once it exists; delete this class at that point.
 */
@Component
public class SessionHistoryPersister {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryPersister.class);

    public void onConnectionEnded(InterviewSessionState state) {
        log.info("Connection ended for session {} after {} turn(s); transcript remains in Redis "
                        + "only — persistence to session-history-service is not implemented yet.",
                state.sessionId(), state.turnCount());
    }

}
