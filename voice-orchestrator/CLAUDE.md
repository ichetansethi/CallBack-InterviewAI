# voice-orchestrator

## Known gaps

**Only completed interviews reach session-history-service.** A session is published to the
`session-completed` Kafka topic (`SessionCompletedPublisher`) only when the model chooses `"end"`.
Interviews that are abandoned (disconnect, never resumed) just expire from Redis after the TTL with
no history record. See ARCHITECTURE.md → "Hand-off to session-history-service".

## Local Piper

`PiperClient` targets the official piper-tts server, not a custom wrapper:
`python -m piper.http_server --host 127.0.0.1 --port 8091 -m voices/en_US-lessac-medium.onnx`
(from the `piper-env` venv). Pass `--port 8091` explicitly: the server defaults to 5000, which
macOS AirPlay already holds. Contract: `POST /synthesize {"text": "..."}` → 16-bit mono WAV
(labelled `text/html`); `GET /info` identifies the server. `PiperClientLiveTest` checks this through
the real client and skips when nothing is listening.
