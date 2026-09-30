# voice-orchestrator

## Known gaps

**Only completed interviews reach session-history-service.** A session is published to the
`session-completed` Kafka topic (`SessionCompletedPublisher`) only when the model chooses `"end"`.
Interviews that are abandoned (disconnect, never resumed) just expire from Redis after the TTL with
no history record. See ARCHITECTURE.md → "Hand-off to session-history-service".
