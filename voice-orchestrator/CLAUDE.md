# voice-orchestrator

## Known gaps

**No persistence to session-history-service.** `session-history-service` is next in the build
order and doesn't exist yet, so there's nowhere to hand off a finished session's transcript or
feedback when a connection ends. For now, `SessionHistoryPersister.onConnectionEnded` (called from
`VoiceWebSocketHandler.handle`'s `doFinally`, so it fires on normal completion, error, or
cancellation alike) only logs that the gap exists — it does not call out to anything. The full
transcript already lives in Redis for the session's TTL (`InterviewSessionRepository`), so nothing
is lost in the meantime; it just isn't durably persisted past that TTL. Replace the log line with a
real call once session-history-service exists.
