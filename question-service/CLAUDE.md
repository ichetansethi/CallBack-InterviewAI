# question-service

## Reliability

`QuestionGenerationService` calls Groq (via Spring AI's OpenAI-compatible client) using function
calling — the model must call the `submitQuestions` tool rather than answer in prose.

Two failure modes get two separate retry mechanisms, deliberately not conflated:

- **Provider rate limiting (HTTP 429):** handled by `ChatModelErrorHandlingConfig` +
  `RateLimitBackOffPolicy`, wrapping only the raw model call. Honors Groq's `Retry-After` header
  when it sends one (confirmed live: it does, as whole seconds); falls back to a fixed 2s/4s/8s
  exponential schedule otherwise. This is a provider-side condition, not a model mistake — waiting
  it out is the correct response, not asking the model again.
- **Malformed/missing tool call** (schema-validation failure): handled by the bounded attempt loop
  in `QuestionGenerationService.generate()`, which just retries the prompt — that's a case where
  asking again can plausibly get a different, correct answer, so backoff time isn't the fix.

See `local_model_reliability_findings` history for where the schema-validation retry design
(single-tool exchanges, self-consistency voting for judgment calls in compatibility-service) came
from — it predates this rate-limit work and follows the same "prove it against the real model,
don't assume" approach.
